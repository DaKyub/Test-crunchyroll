package com.dakyub.crunchymal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.Graph
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.crunchyroll.CrConfig
import com.dakyub.crunchymal.data.crunchyroll.PollResult
import com.dakyub.crunchymal.ui.components.TvTextField
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface LoginState {
    data object Loading : LoginState
    data object NeedsCredentials : LoginState
    data class Code(val userCode: String, val url: String) : LoginState
    data class Error(val message: String) : LoginState
}

class LoginViewModel(private val graph: Graph) : ViewModel() {
    val state = MutableStateFlow<LoginState>(LoginState.Loading)
    private var job: Job? = null

    init {
        start()
    }

    fun saveCredentials(basic: String, userAgent: String) {
        graph.settings.basicAuthOverride = basic
        graph.settings.userAgentOverride = userAgent
        start()
    }

    fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            if (!graph.settings.hasClientCredentials) {
                state.value = LoginState.NeedsCredentials
                return@launch
            }
            state.value = LoginState.Loading
            while (true) {
                val code = try {
                    graph.auth.requestDeviceCode()
                } catch (e: Exception) {
                    state.value = LoginState.Error("Impossible d'obtenir un code : ${e.message}")
                    return@launch
                }
                state.value = LoginState.Code(code.userCode, code.verificationUri.ifBlank { CrConfig.ACTIVATE_URL })
                val deadline = System.currentTimeMillis() + code.expiresIn * 1000
                val interval = code.interval.coerceIn(3, 30) * 1000
                var renew = false
                while (!renew && System.currentTimeMillis() < deadline) {
                    delay(interval)
                    when (val result = runCatching { graph.auth.pollDeviceToken(code.deviceCode) }
                        .getOrElse { PollResult.Pending }) {
                        PollResult.Success -> return@launch // AppRoot bascule automatiquement
                        PollResult.Pending -> Unit
                        PollResult.Expired -> renew = true
                        is PollResult.Error -> {
                            state.value = LoginState.Error(result.message)
                            return@launch
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen() {
    val graph = LocalGraph.current
    val vm = viewModel { LoginViewModel(graph) }
    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(48.dp),
        ) {
            Text("CrunchyMAL", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            when (val s = state) {
                LoginState.Loading -> Text("Demande d'un code d'activation…")
                LoginState.NeedsCredentials -> CredentialsForm(
                    initialBasic = graph.settings.basicAuthOverride,
                    initialUa = graph.settings.userAgentOverride,
                    onSave = vm::saveCredentials,
                )
                is LoginState.Code -> {
                    Text("Sur ton téléphone ou ordinateur, va sur", style = MaterialTheme.typography.titleMedium)
                    Text(s.url, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("et saisis le code :", style = MaterialTheme.typography.titleMedium)
                    Text(
                        s.userCode,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 8.sp,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(horizontal = 32.dp, vertical = 12.dp),
                    )
                    Text(
                        "La connexion se fera automatiquement une fois le code validé.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                is LoginState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Text(
                        "Si l'erreur persiste, les identifiants client de l'app TV ont peut-être changé (voir README).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { vm.start() }) { Text("Réessayer") }
                        OutlinedButton(onClick = { vm.state.value = LoginState.NeedsCredentials }) {
                            Text("Modifier les identifiants client")
                        }
                    }
                }
            }
            OutlinedButton(onClick = { graph.providers.set(setOf(Provider.ADN)) }) {
                Text("Continuer avec ADN uniquement (sans Crunchyroll)")
            }
        }
    }
}

@Composable
private fun CredentialsForm(initialBasic: String, initialUa: String, onSave: (String, String) -> Unit) {
    var basic by remember { mutableStateOf(initialBasic) }
    var ua by remember { mutableStateOf(initialUa) }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(720.dp),
    ) {
        Text(
            "Identifiants client de l'app Android TV Crunchyroll requis (voir README).",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        TvTextField(basic, { basic = it }, "Basic xxxxxxxx=  (base64 de client_id:client_secret)")
        TvTextField(ua, { ua = it }, "User-Agent (optionnel, valeur par défaut sinon)")
        Button(onClick = { onSave(basic, ua) }, enabled = basic.isNotBlank()) { Text("Enregistrer et continuer") }
    }
}
