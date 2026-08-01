package com.vibestick.android.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRecorderLifecycleTest {
    @Test
    fun reachingMaximumBytesStillAllowsStopAndNextRecording() = runTest {
        val first = FakePcmAudioSource(fillReads = true)
        val second = FakePcmAudioSource(fillReads = false)
        val sources = ArrayDeque(listOf(first, second))
        val recorder = AndroidPhoneAudioRecorder(
            permissionGranted = { true },
            minimumBufferSize = { 3_200 },
            sourceFactory = { sources.removeFirst() },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            nanoTime = { 1L },
            maxAudioBytes = AudioRecordingValidator.minimumAudioBytes,
        )

        assertTrue(recorder.start {}.isSuccess)
        advanceUntilIdle()

        assertTrue(recorder.stop().isSuccess)
        assertTrue(first.stopped)
        assertTrue(first.released)
        assertTrue(recorder.start {}.isSuccess)
        recorder.cancel()
        advanceUntilIdle()
        assertTrue(second.released)
    }

    @Test
    fun cancelDuringStartupReleasesSourceWithoutStartingIt() = runTest {
        val factoryEntered = CountDownLatch(1)
        val allowFactoryToReturn = CountDownLatch(1)
        val sourceCount = AtomicInteger()
        val first = FakePcmAudioSource(fillReads = false)
        val second = FakePcmAudioSource(fillReads = false)
        val recorder = AndroidPhoneAudioRecorder(
            permissionGranted = { true },
            minimumBufferSize = { 3_200 },
            sourceFactory = {
                if (sourceCount.getAndIncrement() == 0) {
                    factoryEntered.countDown()
                    check(allowFactoryToReturn.await(2, TimeUnit.SECONDS))
                    first
                } else {
                    second
                }
            },
            ioDispatcher = Dispatchers.IO,
            nanoTime = System::nanoTime,
        )
        val starting = async { recorder.start {} }
        runCurrent()
        assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))

        recorder.cancel()
        allowFactoryToReturn.countDown()

        val cancelled = starting.await()
        assertTrue(cancelled.exceptionOrNull() is AudioRecordingException.Cancelled)
        assertFalse(first.started)
        assertTrue(first.released)

        assertTrue(recorder.start {}.isSuccess)
        recorder.stop()
        assertTrue(second.released)
    }

    @Test
    fun cancellingStopBeforeIoCleanupDoesNotWedgeRecorder() = runTest {
        val ioDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val first = FakePcmAudioSource(fillReads = false)
            val second = FakePcmAudioSource(fillReads = false)
            val sources = ArrayDeque(listOf(first, second))
            val recorder = AndroidPhoneAudioRecorder(
                permissionGranted = { true },
                minimumBufferSize = { 3_200 },
                sourceFactory = { sources.removeFirst() },
                ioDispatcher = ioDispatcher,
                nanoTime = System::nanoTime,
            )
            assertTrue(recorder.start {}.isSuccess)

            val blockerEntered = CountDownLatch(1)
            val releaseBlocker = CountDownLatch(1)
            val blocker = CoroutineScope(ioDispatcher).launch {
                blockerEntered.countDown()
                releaseBlocker.await()
            }
            assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
            val stopping = async(start = CoroutineStart.UNDISPATCHED) { recorder.stop() }

            stopping.cancel()
            releaseBlocker.countDown()
            blocker.join()
            stopping.join()

            recorder.cancel()
            assertTrue(recorder.start {}.isSuccess)
            recorder.stop()
            assertTrue(first.released)
            assertTrue(second.released)
        } finally {
            ioDispatcher.close()
        }
    }

    @Test
    fun cancellingStopDoesNotWaitForeverForNonCooperativeReader() = runTest {
        val first = BlockingReadPcmAudioSource()
        val second = FakePcmAudioSource(fillReads = false)
        val sources = ArrayDeque<PcmAudioSource>(listOf(first, second))
        val recorder = AndroidPhoneAudioRecorder(
            permissionGranted = { true },
            minimumBufferSize = { 3_200 },
            sourceFactory = { sources.removeFirst() },
            ioDispatcher = Dispatchers.IO,
            nanoTime = System::nanoTime,
        )
        assertTrue(recorder.start {}.isSuccess)
        assertTrue(first.readEntered.await(2, TimeUnit.SECONDS))
        val stopping = async(Dispatchers.Default) { recorder.stop() }
        assertTrue(first.stopCalled.await(2, TimeUnit.SECONDS))
        val stopCompleted = CountDownLatch(1)
        stopping.invokeOnCompletion { stopCompleted.countDown() }

        stopping.cancel()
        val completedAfterCancel = try {
            stopCompleted.await(2, TimeUnit.SECONDS)
        } finally {
            first.releaseRead.countDown()
        }

        assertTrue(completedAfterCancel)
        assertTrue(first.released)
        assertTrue(recorder.start {}.isSuccess)
        recorder.stop()
        assertTrue(second.released)
    }
}

private class FakePcmAudioSource(
    private val fillReads: Boolean,
) : PcmAudioSource {
    override val isInitialized: Boolean = true
    var started = false
    var stopped = false
    var released = false

    override fun start() {
        started = true
    }

    override fun read(buffer: ByteArray, length: Int): Int {
        if (!fillReads) return 0
        buffer.fill(1, 0, length)
        return length
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
    }
}

private class BlockingReadPcmAudioSource : PcmAudioSource {
    override val isInitialized: Boolean = true
    val readEntered = CountDownLatch(1)
    val stopCalled = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    var released = false

    override fun start() = Unit

    override fun read(buffer: ByteArray, length: Int): Int {
        readEntered.countDown()
        releaseRead.await()
        return 0
    }

    override fun stop() {
        stopCalled.countDown()
    }

    override fun release() {
        released = true
    }
}
