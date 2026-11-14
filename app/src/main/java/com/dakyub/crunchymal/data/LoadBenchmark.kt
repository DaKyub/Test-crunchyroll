package com.dakyub.crunchymal.data

import com.dakyub.crunchymal.BuildConfig
import com.dakyub.crunchymal.Graph
import java.util.Calendar
import java.util.Locale

/**
 * Benchmark de chargement (Réglages → Diagnostics) : quelques requêtes chronométrées, sans cache, vers
 * chaque service, puis le journal [Perf] de l'utilisation depuis le lancement. Le tout part sur GitHub.
 */
suspend fun Graph.loadBenchmark(): List<String> {
    val out = mutableListOf(
        "Build : ${BuildConfig.VERSION_CODE}",
        "Appareil : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}",
        "Services : ${providers.selected.value.joinToString { it.label }} · CR connecté : ${auth.loggedIn.value} · ADN connecté : ${adn.loggedIn.value}",
        "Onglet au premier plan : ${Foreground.owner.value}",
        "",
        "== Files au début du test ==",
    )
    out += Perf.queueStates()
    out += ""
    out += "== Test chronométré (sans cache, l'un après l'autre) =="

    suspend fun step(label: String, block: suspend () -> String) {
        val t0 = Perf.now()
        val result = runCatching { block() }
        val ms = Perf.now() - t0
        val detail = result.fold({ it }, { "échec : ${it.javaClass.simpleName} ${it.message?.take(120)}" })
        Perf.record("Benchmark", label, ms, if (result.isSuccess) "" else "échec")
        out += String.format(Locale.FRANCE, "%-52s %7d ms  %s", label, ms, detail)
    }

    val calendar = Calendar.getInstance()
    val year = calendar.get(Calendar.YEAR)
    val season = listOf("winter", "spring", "summer", "fall")[calendar.get(Calendar.MONTH) / 3]
    val api = mal.rawApi
    step("MAL classement « all », 500 animes (prioritaire)") { "${api.ranking("all", limit = 500, offset = 0).size} animes" }
    step("MAL classement « all », 100 animes (prioritaire)") { "${api.ranking("all", limit = 100, offset = 0).size} animes" }
    step("MAL classement « airing », 500 animes (prioritaire)") { "${api.ranking("airing", limit = 500, offset = 0).size} animes" }
    step("MAL saison $season $year (prioritaire)") { "${api.seasonal(year, season).size} animes" }
    step("MAL recherche « Frieren » (file de fond)") { "${api.search("Frieren").size} résultats" }
    step("MAL fiche détaillée (prioritaire)") { api.details(52991, priority = true).title }
    if (auth.loggedIn.value) {
        step("Crunchyroll catalogue populaire, 100 séries") { "${this.api.browse("popularity", n = 100).size} séries" }
        step("Crunchyroll recherche « Frieren »") { "${this.api.search("Frieren").size} résultats" }
    }
    step("ADN catalogue populaire, 100 séries") { "${adn.catalog(order = "popular", limit = 100).size} séries" }
    if (settings.tmdbKey.isNotBlank()) {
        step("TMDB résumé français « Frieren »") { if (watchPlatforms.frenchOverview(listOf("Frieren: Beyond Journey's End", "Sousou no Frieren")) != null) "trouvé" else "rien" }
    }
    out += ""
    out += Perf.report()
    return out
}
