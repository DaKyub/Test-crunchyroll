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
import com.dakyub.crunchymal.ui.screens.HomeScreen
import com.dakyub.crunchymal.ui.screens.LoginScreen
import com.dakyub.crunchymal.ui.screens.SearchScreen
import com.dakyub.crunchymal.ui.screens.SeriesScreen
import com.dakyub.crunchymal.ui.screens.SettingsScreen
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
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainTabs(onOpenSeries = { ref -> if (ref.id.isNotBlank()) nav.navigate(ref.route) })
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
                Provider.ADN.name -> AdnSeriesScreen(showId = id)
                else -> SeriesScreen(seriesId = id)
            }
        }
    }
}

private val Tabs = listOf("Accueil", "Parcourir", "Watchlist", "Recherche", "Paramètres")

@Composable
private fun MainTabs(onOpenSeries: (SeriesRef) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val graph = LocalGraph.current
    val providers by graph.providers.selected.collectAsState()
    LaunchedEffect(Unit) { graph.updates.check() }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 4.dp),
        ) {
            TabRow(selectedTabIndex = selected, modifier = Modifier.weight(1f, fill = false)) {
                Tabs.forEachIndexed { index, title ->
                    Tab(selected = index == selected, onFocus = { selected = index }) {
                        Text(title, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }
            // Sélecteur de services : Crunchyroll → ADN → les deux.
            OutlinedButton(onClick = { graph.providers.next() }) {
                Text("Service : ${ProviderSelection.label(providers)}", style = MaterialTheme.typography.labelLarge)
            }
            UpdateBanner()
        }
        when (selected) {
            0 -> HomeScreen(onOpenSeries)
            1 -> BrowseScreen(onOpenSeries)
            2 -> WatchlistScreen(onOpenSeries)
            3 -> SearchScreen(onOpenSeries)
            else -> SettingsScreen()
        }
    }
}
