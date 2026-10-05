package com.dakyub.crunchymal.data.mal

import java.text.Normalizer

object TitleMatcher {
    const val THRESHOLD = 0.6

    private val parentheses = Regex("""\(.*?\)|\[.*?]""")
    private val nonAlnum = Regex("""[^\p{L}\p{N}]+""")
    private val diacritics = Regex("""\p{Mn}+""")

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

    /** Meilleur candidat Jikan pour les titres Crunchyroll donnés, ou null sous le seuil. */
    fun pick(candidates: List<JikanAnime>, titles: List<String>): JikanAnime? {
        val scored = candidates.mapIndexed { index, anime ->
            val sim = anime.allTitles.maxOfOrNull { t -> titles.maxOf { similarity(it, t) } } ?: 0.0
            val typeBonus = when (anime.type) {
                "TV" -> 0.05
                "Movie", "ONA" -> 0.02
                else -> 0.0
            }
            // À similarité égale, on garde l'ordre de pertinence Jikan.
            Triple(anime, sim + typeBonus - index * 0.001, sim)
        }
        return scored.filter { it.third >= THRESHOLD }.maxByOrNull { it.second }?.first
    }
}
