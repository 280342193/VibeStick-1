package com.vibestick.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecordingValidatorTest {
    @Test
    fun emptyRecordingIsRejected() {
        val result = AudioRecordingValidator.validate(ByteArray(0))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AudioRecordingException.Empty)
    }

    @Test
    fun recordingShorterThanSevenTenthsOfASecondIsRejected() {
        val result = AudioRecordingValidator.validate(ByteArray(22_399))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AudioRecordingException.TooShort)
    }

    @Test
    fun minimumLengthRecordingIsAccepted() {
        val pcm = ByteArray(22_400)

        val result = AudioRecordingValidator.validate(pcm)

        assertTrue(result.isSuccess)
        assertEquals(pcm.size, result.getOrThrow().size)
    }
}
