package com.hpu.browseguard

import com.hpu.browseguard.data.EngineUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineUrlTest {

    @Test
    fun stripsTrailingSlash() {
        assertEquals("http://127.0.0.1:8730", EngineUrl.normalize("http://127.0.0.1:8730/"))
    }

    @Test
    fun acceptsEmulatorHost() {
        assertEquals("http://10.0.2.2:8730", EngineUrl.normalize("  http://10.0.2.2:8730  "))
    }

    @Test
    fun acceptsHttpsForTlsDeployment() {
        assertEquals("https://engine.lan:8443", EngineUrl.normalize("https://engine.lan:8443/"))
    }

    @Test
    fun rejectsPathQueryAndFragment() {
        assertNull(EngineUrl.normalize("http://127.0.0.1:8730/v1"))
        assertNull(EngineUrl.normalize("http://127.0.0.1:8730?token=abc"))
        assertNull(EngineUrl.normalize("http://127.0.0.1:8730#frag"))
    }

    @Test
    fun rejectsNonHttpSchemeAndBlank() {
        assertNull(EngineUrl.normalize("file:///etc/passwd"))
        assertNull(EngineUrl.normalize("127.0.0.1:8730"))
        assertNull(EngineUrl.normalize("   "))
    }
}
