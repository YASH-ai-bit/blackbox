package dev.blackbox.core

import kotlin.test.*

class SanitizerTest {
    @Test fun redactsSecretsAndIdentifiers() {
        val input = "SSID: home network\npassword = let me in\nwpa_passphrase=secret\npsk=012345\nidentity=user@example.org\nprivate_key=abcd\n192.168.50.1/24\nfe80::1234:5678%wlan0\n02:11:22:33:44:55"
        val clean = Sanitizer.clean(input)
        listOf("home network", "let me in", "secret", "012345", "user@example.org", "abcd", "192.168.50.1", "fe80::1234:5678", "02:11:22:33:44:55").forEach { assertFalse(clean.contains(it), "Not redacted: $it") }
    }
    @Test fun keepsUsefulEvidenceAndIsIdempotent() {
        val input = "phy0\n#{ managed } <= 1, #{ AP } <= 1, total <= 2\nchannel 132 (5660 MHz)\nuid=0(root)"
        assertEquals(input, Sanitizer.clean(input))
        val sanitized = Sanitizer.clean("SSID=test\n192.0.2.1")
        assertEquals(sanitized, Sanitizer.clean(sanitized))
    }
    @Test fun boundsOutputAndRemovesControlCharacters() {
        assertTrue(Sanitizer.clean("x".repeat(100_000)).length <= 64_000)
        assertFalse(Sanitizer.clean("line\u0000evil\u001b[31m").contains('\u001b'))
    }
}
