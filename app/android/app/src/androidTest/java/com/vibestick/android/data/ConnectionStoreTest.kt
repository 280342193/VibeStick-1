package com.vibestick.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStoreTest {
    @Test
    fun blankSharedTokenUsesStablePerDeviceToken() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("vibestick_connection", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        val firstStore = ConnectionStore(context)
        val generated = firstStore.token()
        val recreatedStore = ConnectionStore(context)

        assertTrue(generated.matches(Regex("[0-9a-f]{64}")))
        assertEquals(generated, recreatedStore.token())

        recreatedStore.saveToken("shared-token")
        assertEquals("shared-token", recreatedStore.token())

        recreatedStore.saveToken("")
        assertNotEquals("", recreatedStore.token())
        assertEquals(generated, recreatedStore.token())
    }
}
