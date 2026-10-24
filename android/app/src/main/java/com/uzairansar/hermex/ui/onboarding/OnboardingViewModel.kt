package com.uzairansar.hermex.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.uzairansar.hermex.ui.SavedStatePolicy
import com.uzairansar.hermex.ui.setBoundedString
import com.uzairansar.hermex.core.runSuspendCatching
import com.uzairansar.hermex.core.network.parseCustomHeaderLines
import com.uzairansar.hermex.data.repository.AuthRepository
import com.uzairansar.hermex.data.repository.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val password: String = "",
    val customHeadersText: String = "",
    val isBusy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val isPasswordRequired: Boolean = true,
    val isConnected: Boolean = false,
)

class OnboardingViewModel(
    private val authRepository: AuthRepository,
    private val savedStateHandle: SavedStateHandle? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OnboardingUiState> = _state

    fun updateServerUrl(value: String) = _state.update {
        val boundedValue = SavedStatePolicy.boundedInput(value)
        savedStateHandle?.setBoundedString(SAVED_SERVER_URL, boundedValue)
        it.copy(
            serverUrl = boundedValue,
            message = null,
            messageIsError = false,
            isPasswordRequired = true,
        )
    }

    fun updatePassword(value: String) = _state.update {
        it.copy(password = value, message = null, messageIsError = false)
    }

    fun updateCustomHeadersText(value: String) = _state.update {
        it.copy(customHeadersText = value, message = null, messageIsError = false)
    }

    fun testConnection() {
        val snapshot = _state.value
        val headers = parseHeadersOrShowMessage(snapshot.customHeadersText) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null, messageIsError = false) }
            runSuspendCatching { authRepository.testConnection(snapshot.serverUrl, headers) }
                .onSuccess { status ->
                    val passkeyOnly = status.authEnabled == true && status.passwordAuthEnabled == false
                    _state.update {
                        it.copy(
                            isBusy = false,
                            message = if (passkeyOnly) {
                                "This server signs in with passkeys, which Hermex doesn't support yet."
                            } else if (status.authEnabled == true) {
                                "Connection ok. Password required."
                            } else {
                                "Connection ok. Password not required."
                            },
                            messageIsError = passkeyOnly,
                            isPasswordRequired = status.authEnabled != false && status.passwordAuthEnabled != false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            message = error.message ?: "Connection failed.",
                            messageIsError = true,
                        )
                    }
                }
        }
    }

    fun connect() {
        val snapshot = _state.value
        val headers = parseHeadersOrShowMessage(snapshot.customHeadersText) ?: return
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, message = null, messageIsError = false) }
            runSuspendCatching { authRepository.configure(snapshot.serverUrl, snapshot.password, headers) }
                .onSuccess {
                    _state.update { it.copy(isBusy = false, isConnected = true) }
                }
                .onFailure { error ->
                    val message = error.message ?: "Sign in failed."
                    _state.update {
                        it.copy(
                            isBusy = false,
                            message = message,
                            messageIsError = true,
                            isPasswordRequired = it.isPasswordRequired ||
                                message.contains("password", ignoreCase = true),
                        )
                    }
                }
        }
    }

    /** Вход по QR/ссылке: MURF `murf://pair` или DeepSeek Harness `?auth=`. */
    fun pairWithQr(raw: String) {
        val payload = com.uzairansar.hermex.agent.PairPayloadParser.parse(raw)
        if (payload == null) {
            _state.update {
                it.copy(
                    message = com.uzairansar.hermex.agent.tr(
                        "Это не QR привязки (нужен murf://pair или ссылка Harness с ?auth=).",
                        "This is not a pairing QR (need murf://pair or a Harness ?auth= link).",
                    ),
                    messageIsError = true,
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, serverUrl = payload.server.toString(), message = null, messageIsError = false) }
            val result = runSuspendCatching {
                when (payload.protocol) {
                    com.uzairansar.hermex.agent.PairProtocol.HermesMurf ->
                        authRepository.pairWithCode(payload.server.toString(), payload.code)
                    com.uzairansar.hermex.agent.PairProtocol.DshRemote ->
                        authRepository.pairWithDshAuth(payload.server.toString(), payload.code)
                }
            }
            result
                .onSuccess {
                    val note = if (payload.protocol == com.uzairansar.hermex.agent.PairProtocol.DshRemote) {
                        com.uzairansar.hermex.agent.tr(
                            "Harness привязан (cookie). Чат Hermex к Harness не подключается — откройте веб-UI сервера.",
                            "Harness paired (cookie). Hermex chat APIs are Hermes-only — open the server web UI.",
                        )
                    } else null
                    _state.update { it.copy(isBusy = false, isConnected = true, message = note, messageIsError = false) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            message = com.uzairansar.hermex.agent.tr("Привязка не удалась: ", "Pairing failed: ") +
                                (error.message ?: error.javaClass.simpleName),
                            messageIsError = true,
                        )
                    }
                }
        }
    }

    private fun parseHeadersOrShowMessage(text: String) =
        runCatching { parseCustomHeaderLines(text) }
            .onFailure {
                _state.update {
                    it.copy(
                        message = "Could not parse custom headers.",
                        messageIsError = true,
                    )
                }
            }
            .getOrNull()

    private fun initialState(): OnboardingUiState {
        val authState = authRepository.state.value
        val savedServer = (authState as? AuthState.LoggedOut)?.server?.toString().orEmpty()
        val activeServerId = authRepository.servers.value.activeServerId
        val savedHeaders = activeServerId
            ?.let(authRepository::customHeaders)
            .orEmpty()
            .joinToString("\n") { header -> "${header.name}: ${header.value}" }
        return OnboardingUiState(
            serverUrl = SavedStatePolicy.boundedInput(
                savedStateHandle?.get<String>(SAVED_SERVER_URL) ?: savedServer.ifBlank { DEFAULT_SERVER_URL },
            ),
            customHeadersText = savedHeaders,
        )
    }

    private companion object {
        const val SAVED_SERVER_URL = "onboarding_server_url"
    }
}

private val DEFAULT_SERVER_URL: String = com.uzairansar.hermex.BuildConfig.DEFAULT_SERVER_URL
