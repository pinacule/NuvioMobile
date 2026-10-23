package com.nuvio.app.features.simkl

import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimklApiClientTest {
    @Test
    fun `response classification retries only documented transient statuses`() {
        assertEquals(SimklResponseAction.SUCCESS, classifySimklResponse(200))
        assertEquals(SimklResponseAction.REAUTHENTICATE, classifySimklResponse(401))
        // Transient set matches the mutation layer's queueable failures:
        // 408, 429, and the whole 5xx range (504 included).
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(408))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(429))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(500))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(501))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(502))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(503))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(504))
        assertEquals(SimklResponseAction.RETRY, classifySimklResponse(599))
        assertEquals(SimklResponseAction.FAIL, classifySimklResponse(400))
        assertEquals(SimklResponseAction.FAIL, classifySimklResponse(404))
        assertEquals(SimklResponseAction.FAIL, classifySimklResponse(409))
        assertEquals(SimklResponseAction.SOFT_SUCCESS, classifySimklResponse(409, true))
        assertEquals(SimklResponseAction.FAIL, classifySimklResponse(412))
    }

    @Test
    fun `retry after accepts the http date form`() {
        // Wed, 21 Oct 2015 07:28:00 GMT == 1_445_412_480_000 ms.
        val now = 1_445_412_480_000L
        assertEquals(30_000L, parseRetryAfterMs("Wed, 21 Oct 2015 07:28:30 GMT", now))
        assertEquals(0L, parseRetryAfterMs("Wed, 21 Oct 2015 07:27:30 GMT", now))
        assertEquals(45_000L, parseRetryAfterMs("45", now))
        assertEquals(null, parseRetryAfterMs("not-a-date", now))
        assertEquals(null, parseRetryAfterMs(null, now))
        // The date form feeds the same max(exponential, retryAfter) rule.
        assertEquals(
            30_000L,
            retryDelayMs(
                attempt = 0,
                retryAfterHeader = "Wed, 21 Oct 2015 07:28:30 GMT",
                jitterMs = 0L,
                nowEpochMs = now,
            ),
        )
        assertEquals(
            1_000L,
            retryDelayMs(
                attempt = 0,
                retryAfterHeader = "Wed, 21 Oct 2015 07:27:30 GMT",
                jitterMs = 0L,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun `retry schedule uses exponential backoff and respects retry after`() {
        assertEquals(1_000L, retryDelayMs(0, null, 0L))
        assertEquals(2_000L, retryDelayMs(1, null, 0L))
        assertEquals(4_000L, retryDelayMs(2, null, 0L))
        assertEquals(8_000L, retryDelayMs(3, null, 0L))
        assertEquals(16_000L, retryDelayMs(4, null, 0L))
        assertEquals(30_250L, retryDelayMs(0, "30", 250L))
        assertEquals(60_000L, retryDelayMs(0, "120", 1_000L))
    }

    @Test
    fun `callers cannot override mandatory application metadata`() {
        val url = buildSimklApiUrl(
            path = "/sync/activities",
            query = mapOf(
                "client_id" to "spoofed",
                "app-name" to "spoofed",
                "app-version" to "spoofed",
            ),
        )

        assertFalse("spoofed" in url)
    }

    @Test
    fun `every request carries metadata query and required headers`() = runBlocking {
        val engine = RecordingEngine(response(200))
        val harness = TestHarness(engine)

        harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/sync/activities",
            ),
        )

        val request = engine.requests.single()
        assertTrue("client_id=" in request.url)
        assertTrue("app-name=" in request.url)
        assertTrue("app-version=" in request.url)
        assertEquals("Bearer token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
        assertTrue(request.headers.getValue("User-Agent").contains('/'))
    }

    @Test
    fun `bodyless post sends json content type without inventing a payload`() = runBlocking {
        val engine = RecordingEngine(response(200))
        val harness = TestHarness(engine)

        harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/users/settings",
            ),
        )

        val request = engine.requests.single()
        assertEquals("", request.body)
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test
    fun `sync response diagnostics exclude response bodies and query values`() {
        val body = """{"movies":[{"movie":{"title":"Private title"}}]}"""
        val request = SimklApiRequest(
            method = SimklHttpMethod.GET,
            path = "/sync/all-items/",
            query = mapOf("date_from" to "2026-07-22T08:48:07Z"),
        )

        val message = simklSyncResponseLogMessage(
            request = request,
            response = response(
                status = 200,
                body = body,
                headers = mapOf("Content-Type" to "application/json"),
            ),
            attempt = 1,
        )

        assertTrue("queryKeys=[date_from]" in message)
        assertTrue("bodyChars=${body.length}" in message)
        assertFalse("Private title" in message)
        assertFalse("2026-07-22T08:48:07Z" in message)
    }

    @Test
    fun `single use unauthenticated posts keep metadata and never retry`() = runBlocking {
        val engine = RecordingEngine(response(503), response(200))
        val harness = TestHarness(engine)

        assertFailsWith<SimklApiException> {
            harness.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/oauth/token",
                    body = "{}",
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        }

        val request = engine.requests.single()
        assertTrue("client_id=" in request.url)
        assertTrue("app-name=" in request.url)
        assertTrue("app-version=" in request.url)
        assertTrue(request.headers.getValue("User-Agent").contains('/'))
        assertFalse("Authorization" in request.headers)
        assertTrue(harness.sleeps.isEmpty())
        assertFalse(harness.wasUnauthorized)
    }

    @Test
    fun `unauthenticated 401 does not invalidate an existing session`() = runBlocking {
        val engine = RecordingEngine(response(401))
        val harness = TestHarness(engine)

        assertFailsWith<SimklApiException> {
            harness.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/oauth/token",
                    body = "{}",
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        }

        assertFalse(harness.wasUnauthorized)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `authenticated requests are serialized at documented method rates`() = runBlocking {
        val engine = RecordingEngine(response(200), response(200), response(200), response(200))
        val harness = TestHarness(engine)

        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/one"))
        harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/two"))
        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/three", body = "{}"))
        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/four", body = "{}"))

        assertEquals(listOf(100L, 1_000L), harness.sleeps)
        assertEquals(listOf(0L, 100L, 100L, 1_100L), engine.requests.map { it.atEpochMs })
    }

    @Test
    fun `post cooldown starts after the previous response completes`() = runBlocking {
        val engine = RecordingEngine(response(200), response(200))
        val harness = TestHarness(engine, responseDurationMs = 400L)

        harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/oauth/token",
                body = "{}",
                requiresAuthentication = false,
                retryPolicy = SimklRetryPolicy.NEVER,
            ),
        )
        harness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/users/settings"))

        assertEquals(listOf(1_000L), harness.sleeps)
        assertEquals(listOf(0L, 1_400L), engine.requests.map { it.atEpochMs })
    }

    @Test
    fun `transient responses retry sequentially and deterministic errors do not`() = runBlocking {
        val transientEngine = RecordingEngine(response(503), response(502), response(200))
        val transientHarness = TestHarness(transientEngine)

        transientHarness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/retry"))

        assertEquals(listOf(1_000L, 2_000L), transientHarness.sleeps)
        assertEquals(3, transientEngine.requests.size)

        val deterministicEngine = RecordingEngine(response(400, """{"error":"wrong_parameter","code":400}"""))
        val deterministicHarness = TestHarness(deterministicEngine)
        val error = assertFailsWith<SimklApiException> {
            deterministicHarness.client.execute(SimklApiRequest(SimklHttpMethod.POST, "/bad", body = "{}"))
        }
        assertEquals("wrong_parameter", error.errorCode)
        assertEquals(1, deterministicEngine.requests.size)
    }

    @Test
    fun `transient failures stop after five total attempts`() = runBlocking {
        val engine = RecordingEngine(
            response(503),
            response(503),
            response(503),
            response(503),
            response(503),
            response(200),
        )
        val harness = TestHarness(engine)

        assertFailsWith<SimklApiException> {
            harness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/unavailable"))
        }

        assertEquals(5, engine.requests.size)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), harness.sleeps)
    }

    @Test
    fun `sync write lock is retried once and other bad requests are not`() = runBlocking {
        val lockedEngine = RecordingEngine(
            response(400, """{"error":"rate_limit","error_description":"Another sync is in progress"}"""),
            response(200),
        )
        val lockedHarness = TestHarness(lockedEngine)

        lockedHarness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                body = "{}",
                retryPolicy = SimklRetryPolicy.SYNC_WRITE,
            ),
        )

        assertEquals(2, lockedEngine.requests.size)
        assertEquals(listOf(3_000L), lockedHarness.sleeps)

        val invalidEngine = RecordingEngine(
            response(400, """{"error":"wrong_parameter"}"""),
            response(200),
        )
        val invalidHarness = TestHarness(invalidEngine)

        assertFailsWith<SimklApiException> {
            invalidHarness.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/sync/history",
                    body = "{}",
                    retryPolicy = SimklRetryPolicy.SYNC_WRITE,
                ),
            )
        }

        assertEquals(1, invalidEngine.requests.size)
    }

    @Test
    fun `retry after and unauthorized handling are applied once`() = runBlocking {
        val retryEngine = RecordingEngine(
            response(429, headers = mapOf("Retry-After" to "3")),
            response(200),
        )
        val retryHarness = TestHarness(retryEngine)
        retryHarness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/limited"))
        assertEquals(listOf(3_000L), retryHarness.sleeps)

        val unauthorizedEngine = RecordingEngine(response(401))
        val unauthorizedHarness = TestHarness(unauthorizedEngine)
        assertFailsWith<SimklApiException> {
            unauthorizedHarness.client.execute(SimklApiRequest(SimklHttpMethod.GET, "/private"))
        }
        assertTrue(unauthorizedHarness.wasUnauthorized)
        assertEquals(1, unauthorizedEngine.requests.size)
    }

    @Test
    fun `a request in retry backoff does not stall unrelated requests`() = runBlocking {
        val engine = object : SimklHttpEngine {
            private val counts = mutableMapOf<String, Int>()

            override suspend fun execute(
                method: String,
                url: String,
                headers: Map<String, String>,
                body: String,
            ): RawHttpResponse {
                val key = if ("/flaky" in url) "flaky" else "steady"
                val count = (counts[key] ?: 0) + 1
                counts[key] = count
                return if (key == "flaky" && count == 1) response(503) else response(200)
            }
        }
        var now = 0L
        var sleepCalls = 0
        val backoffEntered = CompletableDeferred<Unit>()
        val releaseBackoff = CompletableDeferred<Unit>()
        val client = SimklApiClient(
            engine = engine,
            accessToken = { "token" },
            onUnauthorized = {},
            nowEpochMs = { now },
            sleep = { delayMs ->
                sleepCalls += 1
                now += delayMs
                if (sleepCalls == 1) {
                    // Park the first caller (the flaky request's retry
                    // backoff) until the test releases it.
                    backoffEntered.complete(Unit)
                    releaseBackoff.await()
                }
            },
            retryJitterMs = { 0L },
        )

        val flaky = async {
            client.execute(SimklApiRequest(SimklHttpMethod.GET, "/flaky"))
        }
        backoffEntered.await()

        // The flaky request is parked in its backoff sleep; an unrelated
        // request must still complete. (Before the pacing rework, one
        // mutex was held across every sleep, so this call never started
        // until the backoff — and all retries — had finished.)
        val steady = client.execute(SimklApiRequest(SimklHttpMethod.GET, "/steady"))
        assertEquals(200, steady.status)

        releaseBackoff.complete(Unit)
        assertEquals(200, flaky.await().status)
    }

    @Test
    fun `duplicate scrobble stop is a soft success`() = runBlocking {
        val engine = RecordingEngine(response(409))
        val harness = TestHarness(engine)

        val result = harness.client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/scrobble/stop",
                body = "{}",
                scrobbleStopConflictIsSuccess = true,
            ),
        )

        assertEquals(409, result.status)
        assertTrue(result.isSoftSuccess)
        assertFalse(harness.wasUnauthorized)
    }

    private class TestHarness(
        engine: RecordingEngine,
        responseDurationMs: Long = 0L,
    ) {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        var wasUnauthorized = false
        val client = SimklApiClient(
            engine = engine.also { recording ->
                recording.now = { now }
                recording.onResponse = { now += responseDurationMs }
            },
            accessToken = { "token" },
            onUnauthorized = { wasUnauthorized = true },
            nowEpochMs = { now },
            sleep = { delayMs ->
                sleeps += delayMs
                now += delayMs
            },
            retryJitterMs = { 0L },
        )
    }

    private class RecordingEngine(vararg responses: RawHttpResponse) : SimklHttpEngine {
        private val queuedResponses = responses.toMutableList()
        val requests = mutableListOf<RecordedRequest>()
        var now: () -> Long = { 0L }
        var onResponse: () -> Unit = {}

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String,
        ): RawHttpResponse {
            requests += RecordedRequest(method, url, headers, body, now())
            return queuedResponses.removeAt(0).also { onResponse() }
        }
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
        val atEpochMs: Long,
    )

    private companion object {
        fun response(
            status: Int,
            body: String = "{}",
            headers: Map<String, String> = emptyMap(),
        ) = RawHttpResponse(
            status = status,
            statusText = "",
            url = "https://api.simkl.com/test",
            body = body,
            headers = headers,
        )
    }
}
