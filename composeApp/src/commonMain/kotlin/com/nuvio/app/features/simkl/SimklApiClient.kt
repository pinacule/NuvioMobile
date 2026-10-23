package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.random.Random

private const val SIMKL_MAX_RESPONSE_BODY_BYTES = 8 * 1024 * 1024
private val simklSyncLog = Logger.withTag("SimklSync")

internal enum class SimklHttpMethod {
    GET,
    POST,
    DELETE,
}

internal enum class SimklRetryPolicy {
    TRANSIENT_FAILURES,
    SYNC_WRITE,
    NEVER,
}

internal data class SimklApiRequest(
    val method: SimklHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: String = "",
    val requiresAuthentication: Boolean = true,
    val retryPolicy: SimklRetryPolicy = SimklRetryPolicy.TRANSIENT_FAILURES,
    val scrobbleStopConflictIsSuccess: Boolean = false,
)

internal data class SimklApiResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
    val isSoftSuccess: Boolean = false,
)

internal class SimklApiException(
    val status: Int?,
    val errorCode: String?,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal fun interface SimklHttpEngine {
    suspend fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
    ): RawHttpResponse
}

internal class SimklApiClient(
    private val engine: SimklHttpEngine,
    private val accessToken: () -> String?,
    private val onUnauthorized: () -> Unit,
    private val nowEpochMs: () -> Long = SimklPlatformClock::nowEpochMs,
    private val sleep: suspend (Long) -> Unit = { delayMs -> delay(delayMs) },
    private val retryJitterMs: () -> Long = { Random.nextLong(RETRY_JITTER_BOUND_MS + 1L) },
) {
    // Guards only the pacing timestamps below. It is never held across a
    // sleep or a network call: one request backing off (or waiting out a
    // rate-limit interval) must not stall every other Simkl call behind
    // it. Pacing is enforced by reserving start slots — see
    // awaitPacingSlot.
    private val pacingMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var nextGetAtEpochMs = 0L
    private var nextPostAtEpochMs = 0L

    suspend fun execute(request: SimklApiRequest): SimklApiResponse {
        val token = if (request.requiresAuthentication) {
            accessToken()?.takeIf(String::isNotBlank)
                ?: throw SimklApiException(
                    status = 401,
                    errorCode = "authentication_required",
                    message = "Simkl authentication is required",
                )
        } else {
            null
        }

        val maxAttempts = when (request.retryPolicy) {
            SimklRetryPolicy.TRANSIENT_FAILURES,
            SimklRetryPolicy.SYNC_WRITE,
            -> MAX_ATTEMPTS
            SimklRetryPolicy.NEVER -> 1
        }
        var syncWriteLockRetried = false
        for (attempt in 0 until maxAttempts) {
            val response = try {
                awaitPacingSlot(request.method)
                try {
                    engine.execute(
                        method = request.method.name,
                        url = buildSimklApiUrl(request.path, request.query),
                        headers = simklRequestHeaders(
                            accessToken = token,
                            contentTypeJson = request.method == SimklHttpMethod.POST,
                        ),
                        body = request.body,
                    )
                } finally {
                    recordPacingCompletion(request.method)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt == maxAttempts - 1) {
                    throw SimklApiException(
                        status = null,
                        errorCode = "transport_failure",
                        message = "Simkl request failed",
                        cause = error,
                    )
                }
                sleep(
                    retryDelayMs(
                        attempt = attempt,
                        retryAfterHeader = null,
                        jitterMs = retryJitterMs(),
                        nowEpochMs = nowEpochMs(),
                    ),
                )
                continue
            }

            logSyncReadResponse(
                request = request,
                response = response,
                attempt = attempt + 1,
            )

            if (
                request.retryPolicy == SimklRetryPolicy.SYNC_WRITE &&
                response.status == 400 &&
                !syncWriteLockRetried &&
                response.errorCode(json) == "rate_limit" &&
                attempt < maxAttempts - 1
            ) {
                syncWriteLockRetried = true
                sleep(SYNC_WRITE_LOCK_RETRY_DELAY_MS)
                continue
            }

            when (classifySimklResponse(response.status, request.scrobbleStopConflictIsSuccess)) {
                SimklResponseAction.SUCCESS -> return response.toApiResponse()
                SimklResponseAction.SOFT_SUCCESS -> {
                    return response.toApiResponse(isSoftSuccess = true)
                }
                SimklResponseAction.REAUTHENTICATE -> {
                    if (request.requiresAuthentication) onUnauthorized()
                    throw response.toApiException(json)
                }
                SimklResponseAction.FAIL -> throw response.toApiException(json)
                SimklResponseAction.RETRY -> {
                    if (attempt == maxAttempts - 1) throw response.toApiException(json)
                    sleep(
                        retryDelayMs(
                            attempt = attempt,
                            retryAfterHeader = response.headers.headerValue("retry-after"),
                            jitterMs = retryJitterMs(),
                            nowEpochMs = nowEpochMs(),
                        ),
                    )
                }
            }
        }

        error("Simkl request loop completed without a response")
    }

    /**
     * Reserves the next pacing slot for [method] under a short lock, then
     * waits for the slot OUTSIDE the lock. Reserving advances the
     * next-allowed timestamp immediately, so concurrent callers line up
     * on successive slots instead of all waking at the same instant;
     * [recordPacingCompletion] can push the next slot further out when a
     * request runs long, preserving the completion-to-start spacing the
     * intervals were tuned with (GET 100 ms, POST/DELETE 1 s).
     */
    private suspend fun awaitPacingSlot(method: SimklHttpMethod) {
        val waitMs = pacingMutex.withLock {
            val now = nowEpochMs()
            val scheduledAt = max(nextPacingSlotEpochMs(method), now)
            setNextPacingSlotEpochMs(method, scheduledAt + pacingIntervalMs(method))
            scheduledAt - now
        }
        if (waitMs > 0L) sleep(waitMs)
    }

    private fun recordPacingCompletion(method: SimklHttpMethod) {
        // Best-effort bookkeeping: if a reservation is in flight elsewhere
        // the slot floor it set already spaces this completion, so a
        // skipped extension only ever relaxes spacing after a long-running
        // request, never tightens it below the reserved interval.
        if (!pacingMutex.tryLock()) return
        try {
            val completedAt = nowEpochMs()
            setNextPacingSlotEpochMs(
                method,
                max(nextPacingSlotEpochMs(method), completedAt + pacingIntervalMs(method)),
            )
        } finally {
            pacingMutex.unlock()
        }
    }

    private fun nextPacingSlotEpochMs(method: SimklHttpMethod): Long = when (method) {
        SimklHttpMethod.GET -> nextGetAtEpochMs
        SimklHttpMethod.POST, SimklHttpMethod.DELETE -> nextPostAtEpochMs
    }

    private fun setNextPacingSlotEpochMs(method: SimklHttpMethod, epochMs: Long) {
        when (method) {
            SimklHttpMethod.GET -> nextGetAtEpochMs = epochMs
            SimklHttpMethod.POST, SimklHttpMethod.DELETE -> nextPostAtEpochMs = epochMs
        }
    }

    private fun pacingIntervalMs(method: SimklHttpMethod): Long = when (method) {
        SimklHttpMethod.GET -> GET_INTERVAL_MS
        SimklHttpMethod.POST, SimklHttpMethod.DELETE -> POST_INTERVAL_MS
    }

    private companion object {
        const val GET_INTERVAL_MS = 100L
        const val POST_INTERVAL_MS = 1_000L
        const val MAX_ATTEMPTS = 5
        const val SYNC_WRITE_LOCK_RETRY_DELAY_MS = 3_000L
        const val RETRY_JITTER_BOUND_MS = 1_000L
    }
}

private fun logSyncReadResponse(
    request: SimklApiRequest,
    response: RawHttpResponse,
    attempt: Int,
) {
    if (request.method != SimklHttpMethod.GET || !request.path.startsWith("/sync/")) return

    simklSyncLog.d { simklSyncResponseLogMessage(request, response, attempt) }
}

internal fun simklSyncResponseLogMessage(
    request: SimklApiRequest,
    response: RawHttpResponse,
    attempt: Int,
): String {
    val queryKeys = request.query.keys.sorted().joinToString(prefix = "[", postfix = "]")
    val contentType = response.headers.headerValue("content-type") ?: "<missing>"
    return "Simkl HTTP response: method=${request.method} path=${request.path} " +
        "queryKeys=$queryKeys status=${response.status} attempt=$attempt " +
        "contentType=$contentType bodyChars=${response.body.length}"
}

internal object SimklApi {
    val client: SimklApiClient by lazy {
        SimklApiClient(
            engine = SimklHttpEngine { method, url, headers, body ->
                httpRequestRaw(
                    method = method,
                    url = url,
                    headers = headers,
                    body = body,
                    maxResponseBodyBytes = SIMKL_MAX_RESPONSE_BODY_BYTES,
                )
            },
            accessToken = SimklAuthRepository::authorizedAccessToken,
            onUnauthorized = SimklAuthRepository::onUnauthorizedResponse,
        )
    }
}

internal enum class SimklResponseAction {
    SUCCESS,
    SOFT_SUCCESS,
    REAUTHENTICATE,
    RETRY,
    FAIL,
}

internal fun classifySimklResponse(
    status: Int,
    scrobbleStopConflictIsSuccess: Boolean = false,
): SimklResponseAction = when {
    status in 200..299 -> SimklResponseAction.SUCCESS
    status == 409 && scrobbleStopConflictIsSuccess -> SimklResponseAction.SOFT_SUCCESS
    status == 401 -> SimklResponseAction.REAUTHENTICATE
    // Transient set aligned with the mutation layer's queueable-failure
    // classification (SimklMutationRepository.isSimklQueueableFailure):
    // request timeout, rate limiting, and any server error are worth
    // retrying; everything else is a deterministic failure.
    status == 408 || status == 429 || status in 500..599 -> SimklResponseAction.RETRY
    else -> SimklResponseAction.FAIL
}

internal fun retryDelayMs(
    attempt: Int,
    retryAfterHeader: String?,
    jitterMs: Long,
    nowEpochMs: Long = SimklPlatformClock.nowEpochMs(),
): Long {
    require(attempt in 0..4) { "Retry attempt must be between 0 and 4" }
    val exponentialDelayMs = 1_000L shl attempt
    val retryAfterMs = parseRetryAfterMs(retryAfterHeader, nowEpochMs) ?: 0L
    return (max(exponentialDelayMs, retryAfterMs) + jitterMs.coerceIn(0L, 1_000L))
        .coerceAtMost(60_000L)
}

/**
 * Parses a Retry-After header value in either of its HTTP forms:
 * delta-seconds ("120") or an HTTP-date ("Wed, 21 Oct 2015 07:28:00 GMT",
 * resolved against [nowEpochMs]). Returns null when the value matches
 * neither form.
 */
internal fun parseRetryAfterMs(headerValue: String?, nowEpochMs: Long): Long? {
    val value = headerValue?.trim()?.takeIf(String::isNotEmpty) ?: return null
    value.toLongOrNull()?.let { seconds -> return seconds.coerceAtLeast(0L) * 1_000L }
    parseHttpDateEpochMs(value)?.let { dateEpochMs ->
        return (dateEpochMs - nowEpochMs).coerceAtLeast(0L)
    }
    // A delta-seconds value followed by junk after a comma (some proxies
    // merge repeated headers) still yields its leading number.
    return value.substringBefore(',').trim().toLongOrNull()
        ?.coerceAtLeast(0L)
        ?.times(1_000L)
}

private val HTTP_DATE_MONTHS = mapOf(
    "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "May" to 5, "Jun" to 6,
    "Jul" to 7, "Aug" to 8, "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12,
)

private val HTTP_DATE_PATTERN = Regex(
    """^[A-Za-z]{3}, (\d{2}) ([A-Za-z]{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$""",
)

/**
 * Parses an IMF-fixdate (the only HTTP-date form servers generate in
 * practice) to epoch milliseconds, or null when the value is not a valid
 * date. Mirrors the hand-rolled UTC math in parseSimklUtcEpochMs.
 */
internal fun parseHttpDateEpochMs(value: String): Long? {
    val match = HTTP_DATE_PATTERN.matchEntire(value.trim()) ?: return null
    val day = match.groupValues[1].toIntOrNull() ?: return null
    val month = HTTP_DATE_MONTHS[match.groupValues[2]] ?: return null
    val year = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null
    if (year !in 1970..2100 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    val monthDays = httpDateDaysInMonths(year)
    if (day !in 1..monthDays[month - 1]) return null

    var days = 0L
    for (currentYear in 1970 until year) {
        days += if (httpDateIsLeapYear(currentYear)) 366L else 365L
    }
    for (monthIndex in 0 until month - 1) days += monthDays[monthIndex]
    days += day - 1L
    return (((days * 24L + hour) * 60L + minute) * 60L + second) * 1_000L
}

private fun httpDateIsLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

private fun httpDateDaysInMonths(year: Int): IntArray =
    if (httpDateIsLeapYear(year)) {
        intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    } else {
        intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    }

private fun RawHttpResponse.toApiResponse(isSoftSuccess: Boolean = false): SimklApiResponse =
    SimklApiResponse(
        status = status,
        body = body,
        headers = headers,
        isSoftSuccess = isSoftSuccess,
    )

private fun RawHttpResponse.toApiException(json: Json): SimklApiException {
    val envelope = errorEnvelope(json)
    return SimklApiException(
        status = status,
        errorCode = envelope?.error,
        message = envelope?.message?.takeIf(String::isNotBlank)
            ?: envelope?.errorDescription?.takeIf(String::isNotBlank)
            ?: envelope?.error?.takeIf(String::isNotBlank)
            ?: "Simkl request failed with HTTP $status",
    )
}

private fun RawHttpResponse.errorCode(json: Json): String? = errorEnvelope(json)?.error

private fun RawHttpResponse.errorEnvelope(json: Json): SimklErrorEnvelope? =
    body.takeIf(String::isNotBlank)?.let { payload ->
        runCatching { json.decodeFromString<SimklErrorEnvelope>(payload) }.getOrNull()
    }

private fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

@Serializable
private data class SimklErrorEnvelope(
    val error: String? = null,
    val code: Int? = null,
    val message: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)
