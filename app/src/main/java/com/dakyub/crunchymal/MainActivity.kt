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
    val loggedIn by LocalGraph.current.auth.loggedIn.collectAsState()
    if (!loggedIn) {
        LoginScreen()
        return
    }
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainTabs(onOpenSeries = { id -> nav.navigate("series/$id") })
        }
        composable("series/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            SeriesScreen(seriesId = entry.arguments?.getString("id").orEmpty())
        }
    }
}

private val Tabs = listOf("Accueil", "Watchlist", "Recherche", "Paramètres")

@Composable
private fun MainTabs(onOpenSeries: (String) -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val updates = LocalGraph.current.updates
    LaunchedEffect(Unit) { updates.check() }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 16.dp, bottom = 4.dp),
        ) {
            TabRow(selectedTabIndex = selected, modifier = Modifier.weight(1f, fill = false)) {
                Tabs.forEachIndexed { index, title ->
                    Tab(selected = index == selected, onFocus = { selected = index }) {
                        Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    }
                }
            }
            UpdateBanner()
        }
        when (selected) {
            0 -> HomeScreen(onOpenSeries)
            1 -> WatchlistScreen(onOpenSeries)
            2 -> SearchScreen(onOpenSeries)
            else -> SettingsScreen()
        }
    }
}
