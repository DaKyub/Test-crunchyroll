package com.dakyub.crunchymal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.dakyub.crunchymal.ui.components.UpdateBanner
import androidx.tv.material3.OutlinedButton
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.ProviderSelection
import com.dakyub.crunchymal.data.SeriesRef
import com.dakyub.crunchymal.ui.screens.AdnSeriesScreen
import com.dakyub.crunchymal.ui.screens.BrowseScreen
import com.dakyub.crunchymal.ui.screens.CalendarScreen
import com.dakyub.crunchymal.ui.screens.MalListScreen
import com.dakyub.crunchymal.ui.screens.MalSeriesScreen
import com.dakyub.crunchymal.ui.screens.HomeScreen
import com.dakyub.crunchymal.ui.screens.LoginScreen
import com.dakyub.crunchymal.ui.screens.SearchScreen
import com.dakyub.crunchymal.ui.screens.SeasonScreen
import com.dakyub.crunchymal.ui.screens.SeriesScreen
import com.dakyub.crunchymal.ui.screens.SettingsScreen
import com.dakyub.crunchymal.ui.screens.SeriesListSource
import com.dakyub.crunchymal.ui.screens.WatchlistScreen
import com.dakyub.crunchymal.ui.theme.CrunchyMalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as CrunchyMalApp).graph
        setContent {
            CompositionLocalProvider(LocalGraph provides graph) {
                CrunchyMalTheme {
                    // Sans Surface, la couleur de texte par défaut est le noir : on la fixe explicitement.
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {
                            AppRoot()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val graph = LocalGraph.current
    val loggedIn by graph.auth.loggedIn.collectAsState()
    val providers by graph.providers.selected.collectAsState()
    // La connexion Crunchyroll n'est exigée que si Crunchyroll est affiché.
    if (!loggedIn && Provider.CRUNCHYROLL in providers) {
        LoginScreen()
        return
    }
    val nav = rememberNavController()
    val openSeries: (SeriesRef) -> Unit = { ref -> if (ref.id.isNotBlank()) nav.navigate(ref.route) }
    val openMal: (Int) -> Unit = { malId -> nav.navigate("mal/$malId") }
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainTabs(onOpenSeries = openSeries, onOpenMal = openMal)
        }
        composable("mal/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            MalSeriesScreen(malId = entry.arguments?.getInt("id") ?: 0, onOpenSeries = openSeries, onOpenMal = openMal)
        }
        composable(
            "series/{provider}/{id}",
            arguments = listOf(
                navArgument("provider") { type = NavType.StringType },
                navArgument("id") { type = NavType.StringType },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            when (entry.arguments?.getString("provider")) {
                Provider.ADN.name -> AdnSeriesScreen(showId = id, onOpenSeries = openSeries, onOpenMal = openMal)
                else -> SeriesScreen(seriesId = id, onOpenSeries = openSeries, onOpenMal = openMal)
            }
        }
    }
}

private val Tabs = listOf("Accueil", "En cours", "Parcourir", "Calendrier", "Saison", "Watchlist", "Liste MAL", "Recherche", "Réglages")

@Composable
private fun MainTabs(onOpenSeries: (SeriesRef) -> Unit, onOpenMal: (Int) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    // Au retour d'une fiche, Android redonne le focus au premier onglet (Accueil), qui serait alors
    // sélectionné : pendant ce retour, le focus est redirigé vers l'onglet où l'on était.
    val tabRequesters = remember { List(Tabs.size) { FocusRequester() } }
    var restoring by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        runCatching { tabRequesters[selected].requestFocus() }
        delay(1500)
        restoring = false
    }
    val graph = LocalGraph.current
    val providers by graph.providers.selected.collectAsState()
    LaunchedEffect(Unit) { graph.updates.check() }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 4.dp),
        ) {
            TabRow(selectedTabIndex = selected, modifier = Modifier.weight(1f, fill = false)) {
                Tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = index == selected,
                        onFocus = {
                            if (restoring && index != selected) {
                                runCatching { tabRequesters[selected].requestFocus() }
                            } else {
                                selected = index
                                restoring = false
                            }
                        },
                        modifier = Modifier.focusRequester(tabRequesters[index]),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
            // Sélecteur de services : Crunchyroll → ADN → les deux.
            OutlinedButton(onClick = { graph.providers.next() }) {
                Text(ProviderSelection.label(providers), style = MaterialTheme.typography.labelLarge)
            }
            UpdateBanner()
        }
        when (selected) {
            0 -> HomeScreen(onOpenSeries)
            1 -> WatchlistScreen(onOpenSeries, SeriesListSource.STARTED)
            2 -> BrowseScreen(onOpenSeries)
            3 -> CalendarScreen(onOpenSeries)
            4 -> SeasonScreen(onOpenSeries, onOpenMal)
            5 -> WatchlistScreen(onOpenSeries)
            6 -> MalListScreen(onOpenSeries, onOpenMal)
            7 -> SearchScreen(onOpenSeries)
            else -> SettingsScreen()
        }
    }
}
