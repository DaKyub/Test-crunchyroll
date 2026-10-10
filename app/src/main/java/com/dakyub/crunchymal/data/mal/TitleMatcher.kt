package com.dakyub.crunchymal.data.mal

import java.text.Normalizer

object TitleMatcher {
    const val THRESHOLD = 0.6

    private val parentheses = Regex("""\(.*?\)|\[.*?]""")
    private val nonAlnum = Regex("""[^\p{L}\p{N}]+""")
    private val diacritics = Regex("""\p{Mn}+""")

    /**
     * Titre sans sous-titre ni numéro de saison ("Bleach: Thousand-Year…" → "Bleach",
     * "March Comes in Like a Lion 2nd Season" → "March Comes in Like a Lion").
     */
    fun baseTitle(title: String): String = title
        .substringBefore(':')
        .replace(Regex("""(?i)\s+(season|saison|part|cour)\s*\d+.*$"""), "")
        .replace(Regex("""(?i)\s+\d+(st|nd|rd|th)\s+season.*$"""), "")
        .replace(Regex("""\s+(\d+|II|III|IV|V)$"""), "")
        .trim()

    /**
     * Titre sans numéro de saison seulement ("… 2nd Season", "… Season 2", "… II"). Contrairement à
     * [baseTitle], le sous-titre et « Part N » sont gardés : « JoJo… Part 7: Steel Ball Run » n'est pas
     * la même fiche Crunchyroll que JoJo.
     */
    fun withoutSeason(title: String): String = title
        .replace(Regex("""(?i)\s+(season|saison|cour)\s*\d+$"""), "")
        .replace(Regex("""(?i)\s+\d+(st|nd|rd|th)\s+season$"""), "")
        .replace(Regex("""\s+([2-9]|II|III|IV|V)$"""), "")
        .trim()

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .replace(parentheses, " ")
            .replace(nonAlnum, " ")
            .trim()

    /** Coefficient de Dice sur les bigrammes de caractères (0..1). */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a).replace(" ", "")
        val y = normalize(b).replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.length < 2 || y.length < 2) return 0.0
        val bx = x.windowed(2).groupingBy { it }.eachCount()
        val by = y.windowed(2).groupingBy { it }.eachCount()
        val common = bx.entries.sumOf { (k, v) -> minOf(v, by[k] ?: 0) }
        return 2.0 * common / (x.length - 1 + y.length - 1)
    }

    /** Meilleur candidat MAL pour les titres Crunchyroll donnés, ou null sous le seuil. */
    fun pick(candidates: List<MalAnime>, titles: List<String>): MalAnime? {
        val scored = candidates.mapIndexed { index, anime ->
            val sim = anime.allTitles.maxOfOrNull { t -> titles.maxOf { similarity(it, t) } } ?: 0.0
            val typeBonus = when (anime.type?.lowercase()) {
                "tv" -> 0.05
                "movie", "ona" -> 0.02
                else -> 0.0
            }
            // À similarité égale, on garde l'ordre de pertinence de MAL.
            Triple(anime, sim + typeBonus - index * 0.001, sim)
        }
        return scored.filter { it.third >= THRESHOLD }.maxByOrNull { it.second }?.first
    }
}
