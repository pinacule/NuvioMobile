package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimklPkceTest {
    @Test
    fun `base64 url encoding is unpadded and url safe`() {
        assertEquals("", byteArrayOf().base64UrlWithoutPadding())
        assertEquals("Zg", "f".encodeToByteArray().base64UrlWithoutPadding())
        assertEquals("Zm8", "fo".encodeToByteArray().base64UrlWithoutPadding())
        assertEquals("Zm9v", "foo".encodeToByteArray().base64UrlWithoutPadding())
        assertEquals("-_8", byteArrayOf(0xfb.toByte(), 0xff.toByte()).base64UrlWithoutPadding())
    }

    @Test
    fun `pkce material uses compliant verifier and S256 challenge`() {
        val material = createSimklPkceMaterial(
            verifierEntropy = ByteArray(32) { it.toByte() },
            stateEntropy = ByteArray(32) { (it + 32).toByte() },
        )

        assertEquals(43, material.verifier.length)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", material.verifier)
        assertEquals("6oZqdX5MOLq_qBJ8vppAnT4fk6AP8UiP9zX8-Rev_9A", material.challenge)
        assertTrue(material.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertFalse('=' in material.verifier)
        assertEquals(43, material.challenge.length)
        assertTrue(material.state.length >= 22)
    }

    @Test
    fun `oauth state comparison requires an exact value`() {
        assertTrue(constantTimeEquals("same-state", "same-state"))
        assertFalse(constantTimeEquals("same-state", "different-state"))
        assertFalse(constantTimeEquals("same-state", "same-state-extra"))
    }

    @Test
    fun `authorization URL uses browser host and exact S256 method`() {
        val url = buildSimklAuthorizationUrl(
            clientId = "client id",
            redirectUri = "nuvio://auth/simkl",
            appName = "nuvio",
            appVersion = "1.2.3",
            material = SimklPkceMaterial("verifier", "challenge", "state"),
        )

        assertTrue(url.startsWith("https://simkl.com/oauth2/authorize?"))
        assertTrue("client_id=client+id" in url || "client_id=client%20id" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("redirect_uri=nuvio%3A%2F%2Fauth%2Fsimkl" in url)
    }

    @Test
    fun `callback parser rejects other routes and missing state`() {
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/trakt?code=a&state=b", "nuvio://auth/simkl"),
        )
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/simkl?code=a", "nuvio://auth/simkl"),
        )
        assertEquals(
            SimklAuthCallback.AuthorizationCode(code = "a", state = "b"),
            parseSimklAuthCallback("nuvio://auth/simkl?code=a&state=b", "nuvio://auth/simkl"),
        )
    }

    @Test
    fun `incomplete simkl callbacks are ignored so a pending authorization survives`() {
        // Blank/missing code: ignored (NotSimkl is the repository's no-op
        // result), never Invalid, so the pending authorization stays intact.
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/simkl?state=b", "nuvio://auth/simkl"),
        )
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/simkl?code=&state=b", "nuvio://auth/simkl"),
        )
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/simkl?code=%20&state=b", "nuvio://auth/simkl"),
        )
        // Blank/missing state.
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/simkl?code=a&state=", "nuvio://auth/simkl"),
        )
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/simkl?code=a&state=%20", "nuvio://auth/simkl"),
        )
        // Bare callback with no parameters at all.
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/simkl?", "nuvio://auth/simkl"),
        )
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback("nuvio://auth/simkl", "nuvio://auth/simkl"),
        )
    }

    @Test
    fun `callback state must match the pending state to complete authorization`() {
        val pendingState = "pending-state"
        // Wrong state with a code present still parses as a code callback,
        // but the constant-time guard rejects it against the pending state,
        // so the repository ignores it without touching the pending flow.
        val forged = parseSimklAuthCallback(
            "nuvio://auth/simkl?code=a&state=attacker-state",
            "nuvio://auth/simkl",
        )
        assertEquals(
            SimklAuthCallback.AuthorizationCode(code = "a", state = "attacker-state"),
            forged,
        )
        assertFalse(constantTimeEquals((forged as SimklAuthCallback.AuthorizationCode).state, pendingState))
        // The legitimate callback carries the exact pending state and passes.
        val legitimate = parseSimklAuthCallback(
            "nuvio://auth/simkl?code=a&state=$pendingState",
            "nuvio://auth/simkl",
        )
        assertEquals(
            SimklAuthCallback.AuthorizationCode(code = "a", state = pendingState),
            legitimate,
        )
        assertTrue(constantTimeEquals((legitimate as SimklAuthCallback.AuthorizationCode).state, pendingState))
    }

    @Test
    fun `pending authorization expires after five minutes`() {
        assertFalse(isSimklAuthorizationExpired(1_000L, 301_000L))
        assertTrue(isSimklAuthorizationExpired(1_000L, 301_001L))
        assertTrue(isSimklAuthorizationExpired(null, 1_000L))
    }

    @Test
    fun `backwards clock movement does not expire a pending authorization`() {
        // The device clock jumping back (or a start timestamp recorded
        // ahead of the current clock) means no time has elapsed against
        // the pending flow yet — it must survive.
        assertFalse(isSimklAuthorizationExpired(2_000L, 1_000L))
        assertFalse(isSimklAuthorizationExpired(2_000L, 2_000L))
    }

    @Test
    fun `oauth error callbacks parse as invalid only with a state`() {
        assertEquals(
            SimklAuthCallback.Invalid(state = "b"),
            parseSimklAuthCallback(
                "nuvio://auth/simkl?error=access_denied&state=b",
                "nuvio://auth/simkl",
            ),
        )
        // An error without a state cannot be tied to the pending flow.
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback(
                "nuvio://auth/simkl?error=access_denied",
                "nuvio://auth/simkl",
            ),
        )
        // Errors on other routes are not Simkl callbacks at all.
        assertIs<SimklAuthCallback.NotSimkl>(
            parseSimklAuthCallback(
                "nuvio://auth/trakt?error=access_denied&state=b",
                "nuvio://auth/simkl",
            ),
        )
        // A blank error parameter is not an error callback.
        assertEquals(
            SimklAuthCallback.AuthorizationCode(code = "a", state = "b"),
            parseSimklAuthCallback(
                "nuvio://auth/simkl?error=&code=a&state=b",
                "nuvio://auth/simkl",
            ),
        )
    }
}
