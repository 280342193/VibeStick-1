package com.vibestick.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRmsCalculatorTest {
    @Test
    fun silenceHasZeroRms() {
        assertEquals(0f, PcmRmsCalculator.rms(ByteArray(320)), 0.001f)
    }

    @Test
    fun louderSamplesProduceHigherRms() {
        val quiet = PcmRmsCalculator.fromSamples(shortArrayOf(500, -500))
        val loud = PcmRmsCalculator.fromSamples(shortArrayOf(12_000, -12_000))

        assertTrue(loud > quiet)
    }

    @Test
    fun signedLittleEndianSamplesAreNormalized() {
        val almostFullScale = byteArrayOf(0xff.toByte(), 0x7f)

        assertEquals(1f, PcmRmsCalculator.rms(almostFullScale), 0.001f)
    }

    @Test
    fun unmatchedFinalByteIsIgnored() {
        val sampleWithTrailingByte = byteArrayOf(0xf4.toByte(), 0x01, 0x55)

        assertEquals(
            PcmRmsCalculator.fromSamples(shortArrayOf(500)),
            PcmRmsCalculator.rms(sampleWithTrailingByte),
            0.0001f,
        )
    }
}
