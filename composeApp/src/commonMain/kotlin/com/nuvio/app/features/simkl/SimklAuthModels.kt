package com.nuvio.app.features.simkl

import kotlinx.serialization.Serializable

enum class SimklConnectionMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED,
}

enum class SimklAuthError {
    MISSING_CLIENT_ID,
    INVALID_CALLBACK,
    INVALID_CALLBACK_STATE,
    AUTHORIZATION_EXPIRED,
    TOKEN_EXCHANGE_FAILED,
    INVALID_TOKEN_RESPONSE,
    AUTHORIZATION_REVOKED,
    REAUTHENTICATION_REQUIRED,
    AUTHORIZATION_DENIED,
}

data class SimklAuthUiState(
    val mode: SimklConnectionMode = SimklConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val username: String? = null,
    val accountId: Long? = null,
    val tokenExpiresAtEpochMs: Long? = null,
    val pendingAuthorizationStartedAtEpochMs: Long? = null,
    val needsReauthentication: Boolean = false,
    val error: SimklAuthError? = null,
)

@Serializable
internal data class SimklStoredAuthState(
    val username: String? = null,
    val accountId: Long? = null,
    val hasFetchedUserSettings: Boolean = false,
    val settingsActivityWatermark: String? = null,
    val tokenExpiresAtEpochMs: Long? = null,
    val pendingAuthorizationState: String? = null,
    val pendingAuthorizationStartedAtEpochMs: Long? = null,
    // Set when the server rejects the stored token (401) or the session
    // otherwise can no longer be trusted: Simkl traffic stops until the
    // user reconnects, but the token, this metadata and the local sync
    // snapshot are all kept — only an explicit disconnect wipes them.
    val needsReauthentication: Boolean = false,
) {
    val hasPendingAuthorization: Boolean
        get() = !pendingAuthorizationState.isNullOrBlank()
}

internal enum class SimklSettingsRefreshAction {
    NONE,
    RECORD_WATERMARK,
    FETCH,
}

internal fun simklSettingsRefreshAction(
    state: SimklStoredAuthState,
    activityWatermark: String?,
): SimklSettingsRefreshAction = when {
    activityWatermark.isNullOrBlank() -> SimklSettingsRefreshAction.NONE
    activityWatermark == state.settingsActivityWatermark -> SimklSettingsRefreshAction.NONE
    state.settingsActivityWatermark == null && state.hasFetchedUserSettings -> {
        SimklSettingsRefreshAction.RECORD_WATERMARK
    }
    else -> SimklSettingsRefreshAction.FETCH
}

internal sealed interface SimklAuthCallback {
    data class AuthorizationCode(
        val code: String,
        val state: String,
    ) : SimklAuthCallback

    /**
     * Simkl answered the authorization request with an `error`
     * parameter (for example the user denied access). Carries the
     * callback's state so the repository can verify it belongs to the
     * pending flow before acting on it.
     */
    data class Invalid(
        val state: String,
    ) : SimklAuthCallback

    data object NotSimkl : SimklAuthCallback
}
