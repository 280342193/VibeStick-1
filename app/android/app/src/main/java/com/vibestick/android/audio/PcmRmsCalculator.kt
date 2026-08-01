package com.vibestick.android.audio

import kotlin.math.sqrt

object PcmRmsCalculator {
    fun rms(
        pcm: ByteArray,
        length: Int = pcm.size,
    ): Float {
        val usableBytes = length.coerceIn(0, pcm.size) and -2
        if (usableBytes == 0) return 0f

        var squareSum = 0.0
        var index = 0
        while (index < usableBytes) {
            val low = pcm[index].toInt() and 0xff
            val high = pcm[index + 1].toInt() shl 8
            val sample = (high or low).toShort().toInt()
            squareSum += sample.toDouble() * sample.toDouble()
            index += 2
        }

        val sampleCount = usableBytes / 2
        return (sqrt(squareSum / sampleCount) / 32_768.0)
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    fun fromSamples(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var squareSum = 0.0
        samples.forEach { sample ->
            squareSum += sample.toDouble() * sample.toDouble()
        }
        return (sqrt(squareSum / samples.size) / 32_768.0)
            .coerceIn(0.0, 1.0)
            .toFloat()
    }
}
