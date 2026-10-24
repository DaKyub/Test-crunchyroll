package com.dakyub.crunchymal.ui.screens

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.dakyub.crunchymal.CrunchyMalApp
import com.dakyub.crunchymal.AdnApp
import com.dakyub.crunchymal.ManifestReader
import com.dakyub.crunchymal.AppAnalyzer
import com.dakyub.crunchymal.data.crunchyroll.ClientCredentials
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.adn.AdnShow
import com.dakyub.crunchymal.data.adn.AdnVideo
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dakyub.crunchymal.LocalGraph
import com.dakyub.crunchymal.ui.components.TvTextField
import com.dakyub.crunchymal.ui.components.QrCode
import com.dakyub.crunchymal.ui.components.UpdateButton
import com.dakyub.crunchymal.ui.components.label
import com.dakyub.crunchymal.update.UpdateState
import com.dakyub.crunchymal.PlexApp
import kotlinx.coroutines.launch

private val Locales = listOf("fr-FR" to "Français", "en-US" to "English")
private val Audios = listOf("ja-JP" to "Japonais", "fr-FR" to "Français", "en-US" to "Anglais")

@Composable
fun SettingsScreen() {
    val graph = LocalGraph.current
    val settings = graph.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var locale by remember { mutableStateOf(settings.locale) }
    var audio by remember { mutableStateOf(settings.preferredAudio) }
    var malId by remember { mutableStateOf(settings.malClientIdOverride) }
    var basic by remember { mutableStateOf(settings.basicAuthOverride) }
    var omdb by remember { mutableStateOf(settings.omdbKey) }
    val malLoggedIn by graph.malAuth.loggedIn.collectAsState()
    var malAuthUrl by remember { mutableStateOf<String?>(null) }
    var malCode by remember { mutableStateOf("") }
    var malSecret by remember { mutableStateOf(settings.malClientSecret) }
    var malStatus by remember { mutableStateOf<String?>(null) }
    var tmdb by remember { mutableStateOf(settings.tmdbKey) }
    var adnUser by remember { mutableStateOf(graph.adn.username) }
    var adnPassword by remember { mutableStateOf("") }
    var adnStatus by remember { mutableStateOf<String?>(null) }
    val adnLoggedIn by graph.adn.loggedIn.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    var adnAnalysis by remember { mutableStateOf<List<String>?>(null) }
    var adnAnalyzing by remember { mutableStateOf(false) }
    var githubToken by remember { mutableStateOf(settings.githubToken) }
    val diagStatus by graph.diagnostics.status.collectAsState()
    val lastDiagnostic by graph.diagnostics.last.collectAsState()

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    /** Envoie un diagnostic sur GitHub (issue) depuis une tâche de fond de l'app. */
    fun sendDiagnostic(title: String, lines: suspend () -> List<String>) = graph.diagnostics.launch(title, lines)

    var ua by remember { mutableStateOf(settings.userAgentOverride) }


    var lastCrash by remember { mutableStateOf(CrunchyMalApp.lastCrash(context)) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        lastCrash?.let { crash ->
            item { Section("Dernier plantage") }
            item { Text(crash, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { sendDiagnostic("Plantage") { crash.lines() } }) { Text("Envoyer sur GitHub") }
                    OutlinedButton(onClick = {
                        CrunchyMalApp.clearCrash(context)
                        lastCrash = null
                    }) { Text("Effacer le rapport") }
                }
            }
        }
        diagStatus?.let { status ->
            item { Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
        item { Section("Mises à jour") }
        item {
            val updateState by graph.updates.state.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Version installée : build ${graph.updates.currentBuild}" + when (updateState) {
                        UpdateState.Checking -> " · vérification…"
                        UpdateState.UpToDate -> " · à jour"
                        else -> updateState.label()?.let { " · $it" } ?: ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { scope.launch { graph.updates.check() } }) { Text("Vérifier") }
                UpdateButton()
            }
        }
        item { Section("Sauvegarde des réglages") }
        item {
            val server by graph.tagServer.state.collectAsState()
            // Gardé même si la section sort de l'écran (la liste se recycle).
            var requested by rememberSaveable { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Garde sur ton téléphone un fichier avec les clés API, les comptes, les prénoms et les options, " +
                        "pour tout remettre d'un coup après une réinstallation ou sur une autre Shield (même Wi-Fi que la Shield).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Un seul bouton dont seul le texte change : il garde le focus (sinon il part sur les onglets).
                val running = requested && server.url != null
                Button(onClick = {
                    if (running) {
                        requested = false
                        graph.tagServer.stop()
                    } else {
                        requested = true
                        graph.tagServer.start()
                    }
                }) { Text(if (running) "Arrêter la page web" else "Sauvegarder / restaurer depuis le téléphone") }
                val url = server.url?.replace("/?k=", "/backup?k=")
                if (requested && url != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        QrCode(url, size = 180.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Scanne ce QR code avec ton téléphone, ou ouvre :", style = MaterialTheme.typography.bodyMedium)
                            Text(url, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Le fichier donne accès à tes comptes : garde-le pour toi. Après une restauration, l'app redémarre.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (requested) server.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }

        item { Section("Automatismes") }
        item {
            var malSync by remember { mutableStateOf(settings.malAutoSync) }
            var channel by remember { mutableStateOf(settings.homeChannel) }
            var notifications by remember { mutableStateOf(settings.newEpisodeNotifications) }
            var arrivals by remember { mutableStateOf(settings.arrivalAlerts) }
            var hideCompleted by remember { mutableStateOf(settings.hideMalCompleted) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(selected = malSync, onClick = { malSync = !malSync; settings.malAutoSync = malSync }) {
                    Text(if (malSync) "✓ Synchro auto de ma liste MAL" else "Synchro auto de ma liste MAL")
                }
                FilterChip(selected = channel, onClick = {
                    channel = !channel
                    settings.homeChannel = channel
                    // Désactivée : la chaîne est retirée de l'écran d'accueil.
                    if (!channel) scope.launch { graph.homeChannel.publish(emptyList()) }
                }) {
                    Text(if (channel) "✓ Chaîne sur l'accueil Android TV" else "Chaîne sur l'accueil Android TV")
                }
                FilterChip(selected = notifications, onClick = { notifications = !notifications; settings.newEpisodeNotifications = notifications }) {
                    Text(if (notifications) "✓ Notifications nouveaux épisodes" else "Notifications nouveaux épisodes")
                }
                FilterChip(selected = arrivals, onClick = { arrivals = !arrivals; settings.arrivalAlerts = arrivals }) {
                    Text(if (arrivals) "✓ Alertes « À voir » disponibles" else "Alertes « À voir » disponibles")
                }
                FilterChip(selected = hideCompleted, onClick = { hideCompleted = !hideCompleted; settings.hideMalCompleted = hideCompleted }) {
                    Text(if (hideCompleted) "✓ Masquer ce que j'ai terminé sur MAL" else "Masquer ce que j'ai terminé sur MAL")
                }
            }
        }
        item {
            Text(
                "Synchro MAL : à l'ouverture d'une fiche, les épisodes vus sont reportés sur ta liste MAL (jamais en arrière ; " +
                    "les séries « Terminé » ou « Abandonné » ne sont pas modifiées). Chaîne : remplie à chaque ouverture de l'accueil ; " +
                    "si elle n'apparaît pas, ajoute « CrunchyMAL » dans Paramètres Android TV → Écran d'accueil → Chaînes. " +
                    "Notifications : vérification toutes les 4 h des séries de ta watchlist. Alertes : quand un anime « À voir » " +
                    "de ta liste MAL arrive sur Crunchyroll ou ADN (vérifié toutes les 12 h, à partir du deuxième passage). " +
                    "Masquer : les séries « Terminé » sur ta liste MAL disparaissent de l'accueil, Parcourir, En cours, Watchlist, " +
                    "Saison, Plex et des recommandations (une série Crunchyroll n'est cachée que si elle n'a pas plus d'épisodes " +
                    "que sa fiche MAL). Même sans l'option, elles comptent comme « Tout vu » dans les filtres de statut.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Section("Vu seul / vu avec…") }
        item {
            val people by graph.companions.people.collectAsState()
            var names by remember { mutableStateOf(graph.companions.people.value.joinToString(", ")) }
            val server by graph.tagServer.state.collectAsState()
            val job by graph.tagServer.job.collectAsState()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Avec qui tu as vu chaque série, rangé comme tags de ta liste MAL (« vu-seul », « vu-avec-Prénom ») : " +
                        "pastille sur les affiches, filtre « Vu avec » dans Liste MAL, En cours et Watchlist, bouton « Vu avec… » sur les fiches. " +
                        "Pour tout marquer d'un coup, ouvre la page web sur ton téléphone (même Wi-Fi que la Shield).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvTextField(names, { names = it }, "Prénoms séparés par des virgules (ex. Julie, Max)")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton(onClick = {
                        graph.companions.setPeople(names.split(","))
                        names = graph.companions.people.value.joinToString(", ")
                        toast("Personnes enregistrées")
                    }) { Text("Enregistrer les prénoms") }
                    if (server.url == null) {
                        Button(onClick = { graph.tagServer.start() }, enabled = malLoggedIn) { Text("Marquer depuis le téléphone") }
                    } else {
                        OutlinedButton(onClick = { graph.tagServer.stop() }) { Text("Arrêter la page web") }
                    }
                }
                if (!malLoggedIn) {
                    Text("Connecte d'abord ton compte MAL (section « Compte MyAnimeList »).", style = MaterialTheme.typography.bodySmall)
                }
                server.url?.let { url ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        QrCode(url, size = 180.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Scanne ce QR code avec ton téléphone, ou ouvre :", style = MaterialTheme.typography.bodyMedium)
                            Text(url, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Personnes : " + (listOf("Seul") + people).joinToString(", ") +
                                    ". La page s'arrête seule après 1 h sans utilisation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                server.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (job.total > 0) {
                    Text(
                        if (job.running) "Enregistrement sur MAL : ${job.done}/${job.total}…"
                        else "Dernier enregistrement : ${job.total - job.failed.size}/${job.total} ✓" +
                            if (job.failed.isNotEmpty()) " · échecs : " + job.failed.take(3).joinToString(" ; ") else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (job.failed.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item { Section("Plex") }
        item {
            val plexConnected by graph.plex.connected.collectAsState()
            val plexLogin by graph.plex.login.collectAsState()
            var linkFormat by remember { mutableStateOf(settings.plexLinkFormat) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (plexConnected) {
                    Text("Serveur : ${graph.plex.serverName}", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { graph.plex.logout() }) { Text("Se déconnecter de Plex") }
                        CycleButton("Lien vers l'app Plex", "format ${linkFormat + 1}/${PlexApp.formats.size}") {
                            linkFormat = (linkFormat + 1) % PlexApp.formats.size
                            settings.plexLinkFormat = linkFormat
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        OutlinedButton(onClick = { sendDiagnostic("Diagnostic Plex") { graph.plex.diagnostic(context) + graph.plexLibrary.summary() } }) {
                            Text("Envoyer un diagnostic Plex")
                        }
                        OutlinedButton(onClick = {
                            val appContext = context.applicationContext
                            sendDiagnostic("Analyse de l'app Plex") {
                                // Liens déclarés par l'app Plex, puis chaînes de son code évoquant des liens vers un contenu.
                                val pkg = PlexApp.PACKAGE
                                listOf("Paquet : $pkg", "", "Liens acceptés (intent-filter) :") +
                                    ManifestReader.intentFilters(appContext, pkg) +
                                    listOf("", "Chaînes du code :") +
                                    AppAnalyzer.analyze(
                                        appContext, pkg, limit = 1500,
                                        extra = Regex("(?i)plex://|metadataKey|ratingKey|preplay|deep.?link|watch\\.plex\\.tv|app\\.plex\\.tv"),
                                        keep = { s ->
                                            !(s.startsWith("L") && s.endsWith(";")) ||
                                                Regex("(?i)deep.?link|uri|route|intent").containsMatchIn(s)
                                        },
                                    )
                            }
                        }) { Text("Analyser l'app Plex") }
                    }
                    Text(
                        "Les épisodes s'ouvrent dans l'app Plex par un lien (format 1 d'après l'analyse de l'app Plex). " +
                            "Si un épisode n'ouvre que l'accueil de Plex, passe au format suivant et réessaie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(onClick = { graph.plex.startLogin() }) { Text("Se connecter à Plex") }
                }
                plexLogin?.let { login ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(login.message, style = MaterialTheme.typography.bodyMedium)
                            login.code?.let { Text(it, style = MaterialTheme.typography.displaySmall) }
                        }
                        login.qrUrl?.let { QrCode(it, size = 160.dp) }
                    }
                }
            }
        }

        item { Section("Services") }
        item {
            SettingRow("Afficher") {
                listOf(
                    setOf(Provider.CRUNCHYROLL) to "Crunchyroll",
                    setOf(Provider.ADN) to "ADN",
                    setOf(Provider.CRUNCHYROLL, Provider.ADN) to "Les deux",
                ).forEach { (value, label) ->
                    FilterChip(selected = providers == value, onClick = { graph.providers.set(value) }) { Text(label) }
                }
            }
        }

        item { Section("ADN") }
        item {
            Text(
                if (adnLoggedIn) "Connecté à ADN (${graph.adn.username})." else
                    "Le catalogue ADN fonctionne sans connexion ; la connexion servira à ta liste et ta progression.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!adnLoggedIn) {
            item { TvTextField(adnUser, { adnUser = it }, "E-mail ou identifiant ADN") }
            item { TvTextField(adnPassword, { adnPassword = it }, "Mot de passe ADN", password = true) }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (adnLoggedIn) {
                    OutlinedButton(onClick = {
                        graph.adn.logout()
                        graph.adnWatchlist.invalidate()
                        graph.adnProgress.clearCache()
                        scope.launch { graph.progress.removeKeys { it.startsWith("adn:") } }
                    }) { Text("Se déconnecter d'ADN") }
                } else {
                    Button(onClick = {
                        adnStatus = "Connexion…"
                        scope.launch {
                            val error = runCatching { graph.adn.login(adnUser, adnPassword) }.getOrElse { it.message }
                            adnStatus = error?.let { "Échec : $it" } ?: "Connecté"
                            if (error == null) adnPassword = ""
                        }
                    }, enabled = adnUser.isNotBlank() && adnPassword.isNotBlank()) { Text("Se connecter à ADN") }
                }
                adnStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            Text(
                "Lecture dans l'app ADN : les épisodes se lancent directement (anidn://video/…), « Ouvrir dans ADN » ouvre la fiche.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedButton(onClick = {
                val pkg = AdnApp.packageName(context)
                val appContext = context.applicationContext
                if (pkg == null) {
                    adnAnalysis = listOf("Application ADN introuvable sur cet appareil.")
                } else if (graph.diagnostics.configured) {
                    // Analyse complète envoyée sur GitHub (tâche de fond) ; l'écran en affiche le début.
                    adnAnalysis = null
                    sendDiagnostic("Analyse de l'app ADN") { listOf("Paquet : $pkg") + AppAnalyzer.analyze(appContext, pkg, limit = 3000) }
                } else {
                    adnAnalyzing = true
                    scope.launch {
                        adnAnalysis = withContext(Dispatchers.IO) {
                            try {
                                listOf("Paquet : $pkg") + AppAnalyzer.analyze(appContext, pkg)
                            } catch (t: Throwable) {
                                listOf("Analyse interrompue : ${t.javaClass.simpleName} ${t.message}")
                            }
                        }
                        adnAnalyzing = false
                    }
                }
            }) { Text(if (adnAnalyzing) "Analyse en cours…" else "Analyser l'app ADN (liens et liste)") }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    sendDiagnostic("Sondage de l'API ADN") { graph.adn.probe() }
                }, enabled = adnLoggedIn) { Text("Sonder l'API ADN (liste, historique) → GitHub") }
                diagStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
        (adnAnalysis ?: lastDiagnostic?.takeIf { it.first == "Analyse de l'app ADN" }?.second?.take(150))?.let { lines ->
            items(lines.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    pair.forEach { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        item { Section("Notes des épisodes") }
        item {
            Text(
                "IMDb via une clé OMDb (omdbapi.com → API Key → FREE) et TMDB en secours (themoviedb.org → Paramètres → API). " +
                    "Une seule clé suffit, les deux donnent le meilleur résultat. La clé TMDB sert aussi aux badges " +
                    "« où regarder en France » (Netflix, Disney+…) de la liste MAL.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { TvTextField(omdb, { omdb = it }, "Clé OMDb (8 caractères)") }
        item { TvTextField(tmdb, { tmdb = it }, "Clé API TMDB (v3) ou jeton de lecture (v4)") }
        item {
            Button(onClick = {
                val tmdbChanged = settings.tmdbKey != tmdb
                settings.omdbKey = omdb
                settings.tmdbKey = tmdb
                graph.ratings.clear()
                // Les plateformes « où regarder » dépendent aussi de la clé TMDB.
                if (tmdbChanged) scope.launch { graph.watchPlatforms.clear() }
                toast("Clés enregistrées")
            }) { Text("Enregistrer les clés") }
        }

        item { Section("MyAnimeList") }
        item {
            Text(
                "Client ID de l'API officielle : crée-le sur myanimelist.net/apiconfig (type « other »), puis colle-le ici." +
                    if (settings.malClientIdOverride.isBlank() && settings.malClientId.isNotBlank()) " Un Client ID est déjà intégré au build." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { TvTextField(malId, { malId = it }, "Client ID MyAnimeList (32 caractères)") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    settings.malClientIdOverride = malId
                    graph.mal.configChanged()
                    toast("Client ID MAL enregistré")
                }) { Text("Enregistrer le Client ID") }
                OutlinedButton(onClick = { scope.launch { graph.mal.clear(); graph.mal.configChanged(); toast("Cache MAL vidé") } }) {
                    Text("Vider les notes MAL")
                }
            }
        }

        item { Section("Compte MyAnimeList (pour noter)") }
        item {
            Text(
                if (malLoggedIn) "Connecté à MAL (${graph.malAuth.username}) : bouton « Ma liste MAL » sur les fiches."
                else "Pour noter et marquer tes séries terminées. L'URL de redirection de ton app MAL doit être http://localhost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (malLoggedIn) {
            item { OutlinedButton(onClick = { graph.malAuth.logout() }) { Text("Se déconnecter de MAL") } }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        settings.malClientSecret = malSecret
                        malAuthUrl = graph.malAuth.authorizeUrl()
                        malStatus = null
                    }, enabled = settings.malClientId.isNotBlank()) { Text("Se connecter à MAL") }
                    if (settings.malClientId.isBlank()) Text("Enregistre d'abord ton Client ID ci-dessus.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item { TvTextField(malSecret, { malSecret = it }, "Client Secret MAL (seulement si ton app MAL en affiche un)") }
            malAuthUrl?.let { url ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        QrCode(url)
                        Text(
                            "1. Scanne ce QR code avec ton téléphone et autorise CrunchyMAL.\n" +
                                "2. Le navigateur arrive sur une page « localhost » qui ne charge pas : c'est normal.\n" +
                                "3. Copie l'adresse complète de cette page et colle-la ci-dessous (télécommande Google TV).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item { TvTextField(malCode, { malCode = it }, "Adresse http://localhost/?code=… (ou le code seul)") }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            malStatus = "Validation…"
                            scope.launch {
                                val error = graph.malAuth.exchange(malCode)
                                malStatus = error?.let { "Échec : $it" } ?: "Connecté à MAL ✓"
                                if (error == null) {
                                    malAuthUrl = null
                                    malCode = ""
                                }
                            }
                        }, enabled = malCode.isNotBlank()) { Text("Valider") }
                    }
                }
            }
            malStatus?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }

        item { Section("Diagnostics (envoi sur GitHub)") }
        item {
            Text(
                "Avec un jeton GitHub, les analyses et rapports sont envoyés automatiquement sous forme d'issue dans le dépôt " +
                    "(public) : aucune photo nécessaire. Jeton : github.com → Settings → Developer settings → Fine-grained tokens, " +
                    "dépôt Test-crunchyroll uniquement, permission « Issues : Read and write ».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { TvTextField(githubToken, { githubToken = it }, "Jeton GitHub (github_pat_…)", password = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    settings.githubToken = githubToken
                    toast("Jeton GitHub enregistré")
                }) { Text("Enregistrer le jeton") }
                OutlinedButton(onClick = { val appContext = context.applicationContext
                    sendDiagnostic("Diagnostic général") { generalDiagnostic(graph, appContext) } }) {
                    Text("Envoyer un diagnostic général")
                }
            }
        }
        diagStatus?.let { status ->
            item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }

        item { Section("Langues") }
        item {
            SettingRow("Titres") {
                Locales.forEach { (code, label) ->
                    FilterChip(selected = locale == code, onClick = {
                        locale = code
                        settings.locale = code
                        graph.watchlist.invalidate()
                    }) { Text(label) }
                }
            }
        }
        item {
            SettingRow("Audio préféré") {
                Audios.forEach { (code, label) ->
                    FilterChip(selected = audio == code, onClick = {
                        audio = code
                        settings.preferredAudio = code
                    }) { Text(label) }
                }
            }
        }

        item { Section("Compte et avancé") }
        item {
            OutlinedButton(onClick = { scope.launch { graph.progress.clear(); toast("Progression recalculée au prochain affichage") } }) {
                Text("Vider le cache de progression")
            }
        }
        item {
            Text(
                "Identifiants client de l'app TV Crunchyroll (laisser vide pour la valeur du build). " +
                    "S'ils sont refusés, l'app les met à jour automatiquement depuis une source publique en ligne ; " +
                    "le bouton ci-dessous force cette mise à jour.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { TvTextField(basic, { basic = it }, "Basic xxxxxxxx= (identifiant:secret en base64)") }
        item { TvTextField(ua, { ua = it }, "User-Agent, ex. Crunchyroll/ANDROIDTV/3.xx.x_xxxxx (Android 14; en-US; Chromecast)") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    settings.basicAuthOverride = basic
                    settings.userAgentOverride = ua
                    toast("Enregistré")
                }) { Text("Enregistrer") }
                OutlinedButton(onClick = {
                    scope.launch {
                        graph.progress.clear()
                        graph.watchlist.invalidate()
                        graph.auth.logout()
                    }
                }) { Text("Se déconnecter") }
            }
        }
        item {
            var searching by remember { mutableStateOf(false) }
            var report by remember { mutableStateOf<String?>(null) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !searching, onClick = {
                    searching = true
                    report = null
                    scope.launch {
                        val result = graph.auth.credentials.recover(manual = true)
                        searching = false
                        when (result) {
                            is ClientCredentials.Result.Found -> {
                                basic = settings.basicAuthOverride
                                ua = settings.userAgentOverride
                                graph.mal.configChanged()
                                report = "Identifiant mis à jour depuis la source en ligne ✓"
                            }
                            is ClientCredentials.Result.Failed -> report = "Échec : ${result.reason}"
                        }
                    }
                }) { Text(if (searching) "Mise à jour…" else "Mettre à jour l'identifiant en ligne") }
                report?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(180.dp))
        content()
    }
}

/** Résumé de configuration (sans aucun secret) pour le diagnostic général. */
private fun generalDiagnostic(graph: com.dakyub.crunchymal.Graph, context: android.content.Context): List<String> {
    val s = graph.settings
    fun yes(b: Boolean) = if (b) "oui" else "non"
    return listOf(
        "Build : ${com.dakyub.crunchymal.BuildConfig.VERSION_CODE}",
        "Appareil : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
        "Services affichés : ${graph.providers.selected.value.joinToString { it.label }}",
        "Crunchyroll connecté : ${yes(graph.auth.loggedIn.value)} · langue ${s.locale} · audio ${s.preferredAudio}",
        "ADN connecté : ${yes(graph.adn.loggedIn.value)} · app ADN : ${AdnApp.packageName(context) ?: "introuvable"}",
        "MAL Client ID : ${yes(s.malClientId.isNotBlank())} · compte MAL connecté : ${yes(graph.malAuth.loggedIn.value)}",
        "MAL dernière erreur : ${graph.mal.lastError.value ?: "aucune"} · notes en cache : ${graph.mal.records.value.size}",
        "Clé OMDb : ${yes(s.omdbKey.isNotBlank())} · clé TMDB : ${yes(s.tmdbKey.isNotBlank())}",
        "Progression en cache : ${graph.progress.summaries.value.size} séries (dont ADN : ${graph.progress.summaries.value.keys.count { it.startsWith("adn:") }})",
        "Mise à jour : ${graph.updates.state.value}",
        "Dernier plantage :",
    ) + (CrunchyMalApp.lastCrash(context)?.lines() ?: listOf("aucun"))
}
