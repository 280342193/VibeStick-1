package com.vibestick.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val sampleRate = 16_000
private const val channelCount = 1
private const val bytesPerSample = 2
private const val minimumDurationMillis = 700
private const val maximumAudioBytes = 1_920_000
private const val readChunkBytes = 1_600
private const val amplitudeIntervalNanos = 50_000_000L

sealed class AudioRecordingException(message: String) : Exception(message) {
    class PermissionDenied : AudioRecordingException("Microphone permission is required")

    class Initialization(message: String) : AudioRecordingException(message)

    class Empty : AudioRecordingException("Recording did not contain audio")

    class TooShort(
        val actualBytes: Int,
        val minimumBytes: Int,
    ) : AudioRecordingException("Recording was shorter than 0.7 seconds")

    class ReadFailed(message: String) : AudioRecordingException(message)

    class AlreadyRecording : AudioRecordingException("A recording is already active")

    class Cancelled : AudioRecordingException("Recording startup was cancelled")

    class NotRecording : AudioRecordingException("No recording is active")
}

object AudioRecordingValidator {
    const val minimumAudioBytes: Int =
        sampleRate * channelCount * bytesPerSample * minimumDurationMillis / 1_000

    fun validate(pcm: ByteArray): Result<ByteArray> = when {
        pcm.isEmpty() -> Result.failure(AudioRecordingException.Empty())
        pcm.size < minimumAudioBytes -> Result.failure(
            AudioRecordingException.TooShort(
                actualBytes = pcm.size,
                minimumBytes = minimumAudioBytes,
            ),
        )
        else -> Result.success(pcm)
    }
}

interface PhoneAudioRecorder {
    suspend fun start(onAmplitude: (Float) -> Unit): Result<Unit>

    suspend fun stop(): Result<ByteArray>

    fun cancel()
}

class AndroidPhoneAudioRecorder internal constructor(
    private val permissionGranted: () -> Boolean,
    private val minimumBufferSize: () -> Int,
    private val sourceFactory: (Int) -> PcmAudioSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long,
    private val maxAudioBytes: Int = maximumAudioBytes,
) : PhoneAudioRecorder {
    constructor(context: Context) : this(
        permissionGranted = microphonePermissionChecker(context.applicationContext),
        minimumBufferSize = {
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        },
        sourceFactory = ::createPcmAudioSource,
        ioDispatcher = Dispatchers.IO,
        nanoTime = System::nanoTime,
        maxAudioBytes = maximumAudioBytes,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var pendingStartup: RecordingStartup? = null
    private var activeSession: ActiveRecording? = null

    override suspend fun start(onAmplitude: (Float) -> Unit): Result<Unit> =
        withContext(ioDispatcher) {
            if (!permissionGranted()) {
                return@withContext Result.failure(AudioRecordingException.PermissionDenied())
            }
            val startup = synchronized(lock) {
                if (activeSession != null || pendingStartup != null) {
                    null
                } else {
                    RecordingStartup().also { pendingStartup = it }
                }
            }
            if (startup == null) {
                return@withContext Result.failure(AudioRecordingException.AlreadyRecording())
            }

            var source: PcmAudioSource? = null
            var handedOff = false
            try {
                val minimum = runCatching { minimumBufferSize() }.getOrElse {
                    return@withContext Result.failure(
                        AudioRecordingException.Initialization(
                            it.message ?: "Could not query the microphone buffer size",
                        ),
                    )
                }
                if (minimum <= 0) {
                    return@withContext Result.failure(
                        AudioRecordingException.Initialization("Unsupported microphone format"),
                    )
                }
                if (startup.cancelled.get()) {
                    return@withContext Result.failure(AudioRecordingException.Cancelled())
                }

                source = try {
                    sourceFactory(maxOf(minimum, readChunkBytes * 2))
                } catch (_: SecurityException) {
                    return@withContext Result.failure(AudioRecordingException.PermissionDenied())
                } catch (error: RuntimeException) {
                    return@withContext Result.failure(
                        AudioRecordingException.Initialization(
                            error.message ?: "Could not initialize the microphone",
                        ),
                    )
                }
                val createdSource = source
                if (startup.cancelled.get()) {
                    return@withContext Result.failure(AudioRecordingException.Cancelled())
                }
                if (!createdSource.isInitialized) {
                    return@withContext Result.failure(
                        AudioRecordingException.Initialization("Microphone initialization failed"),
                    )
                }

                try {
                    createdSource.start()
                } catch (_: SecurityException) {
                    return@withContext Result.failure(AudioRecordingException.PermissionDenied())
                } catch (error: RuntimeException) {
                    return@withContext Result.failure(
                        AudioRecordingException.Initialization(
                            error.message ?: "Could not start the microphone",
                        ),
                    )
                }
                currentCoroutineContext().ensureActive()
                if (startup.cancelled.get()) {
                    return@withContext Result.failure(AudioRecordingException.Cancelled())
                }

                val session = ActiveRecording(createdSource)
                session.readerJob = scope.launch(start = CoroutineStart.LAZY) {
                    readPcm(session, onAmplitude)
                }
                val accepted = synchronized(lock) {
                    if (
                        pendingStartup === startup &&
                        !startup.cancelled.get() &&
                        activeSession == null
                    ) {
                        pendingStartup = null
                        activeSession = session
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    return@withContext Result.failure(AudioRecordingException.Cancelled())
                }

                handedOff = true
                session.readerJob.start()
                Result.success(Unit)
            } finally {
                synchronized(lock) {
                    if (pendingStartup === startup) pendingStartup = null
                }
                if (!handedOff) {
                    source?.let {
                        runCatching { it.stop() }
                        runCatching { it.release() }
                    }
                }
            }
        }

    override suspend fun stop(): Result<ByteArray> {
        val session = synchronized(lock) {
            val current = activeSession
                ?: return Result.failure(AudioRecordingException.NotRecording())
            if (!current.finalizationClaimed.compareAndSet(false, true)) {
                return Result.failure(AudioRecordingException.NotRecording())
            }
            current.stopRequested.set(true)
            activeSession = null
            current
        }

        return try {
            withContext(ioDispatcher) {
                runCatching { session.source.stop() }
                session.readerJob.join()
                session.readFailure.get()?.let { return@withContext Result.failure(it) }
                AudioRecordingValidator.validate(session.output.toByteArray())
            }
        } finally {
            withContext(ioDispatcher + NonCancellable) {
                session.readerJob.cancel()
                runCatching { session.source.stop() }
                runCatching { session.source.release() }
            }
            synchronized(lock) {
                if (activeSession === session) activeSession = null
            }
        }
    }

    override fun cancel() {
        val session = synchronized(lock) {
            pendingStartup?.cancelled?.set(true)
            val current = activeSession ?: return
            if (!current.finalizationClaimed.compareAndSet(false, true)) return
            current.stopRequested.set(true)
            activeSession = null
            current
        } ?: return
        runCatching { session.source.stop() }
        scope.launch {
            session.readerJob.cancelAndJoin()
            runCatching { session.source.release() }
        }
    }

    private suspend fun readPcm(
        session: ActiveRecording,
        onAmplitude: (Float) -> Unit,
    ) {
        val buffer = ByteArray(readChunkBytes)
        var lastAmplitudeAt = 0L
        try {
            while (!session.stopRequested.get() && session.output.size() < maxAudioBytes) {
                val remaining = maxAudioBytes - session.output.size()
                val requested = minOf(buffer.size, remaining)
                val count = try {
                    session.source.read(buffer, requested)
                } catch (error: RuntimeException) {
                    session.readFailure.compareAndSet(
                        null,
                        AudioRecordingException.ReadFailed(
                            error.message ?: "Microphone read failed",
                        ),
                    )
                    break
                }

                when {
                    count > 0 -> {
                        session.output.write(buffer, 0, minOf(count, requested))
                        val now = nanoTime()
                        if (
                            lastAmplitudeAt == 0L ||
                            now - lastAmplitudeAt >= amplitudeIntervalNanos
                        ) {
                            lastAmplitudeAt = now
                            runCatching {
                                onAmplitude(PcmRmsCalculator.rms(buffer, count))
                            }
                        }
                    }
                    count < 0 && !session.stopRequested.get() -> {
                        session.readFailure.compareAndSet(
                            null,
                            AudioRecordingException.ReadFailed(
                                "Microphone read failed with code $count",
                            ),
                        )
                        break
                    }
                    else -> delay(5)
                }
            }
            if (session.output.size() >= maxAudioBytes) {
                session.stopRequested.set(true)
                runCatching { session.source.stop() }
            }
        } finally {
            runCatching { onAmplitude(0f) }
        }
    }
}

private class ActiveRecording(
    val source: PcmAudioSource,
) {
    val output = ByteArrayOutputStream()
    val stopRequested = AtomicBoolean(false)
    val finalizationClaimed = AtomicBoolean(false)
    val readFailure = AtomicReference<AudioRecordingException.ReadFailed?>(null)
    lateinit var readerJob: Job
}

private class RecordingStartup {
    val cancelled = AtomicBoolean(false)
}

internal interface PcmAudioSource {
    val isInitialized: Boolean

    fun start()

    fun read(buffer: ByteArray, length: Int): Int

    fun stop()

    fun release()
}

private class AndroidPcmAudioSource(
    private val audioRecord: AudioRecord,
) : PcmAudioSource {
    override val isInitialized: Boolean
        get() = audioRecord.state == AudioRecord.STATE_INITIALIZED

    override fun start() = audioRecord.startRecording()

    override fun read(buffer: ByteArray, length: Int): Int =
        audioRecord.read(buffer, 0, length, AudioRecord.READ_BLOCKING)

    override fun stop() = audioRecord.stop()

    override fun release() = audioRecord.release()
}

private fun microphonePermissionChecker(context: Context): () -> Boolean = {
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
private fun createPcmAudioSource(bufferSize: Int): PcmAudioSource {
    val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()
    val audioRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        .setAudioFormat(format)
        .setBufferSizeInBytes(bufferSize)
        .build()
    return AndroidPcmAudioSource(audioRecord)
}
