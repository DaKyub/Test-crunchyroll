package com.dakyub.crunchymal.data.mal

import java.text.Normalizer

object TitleMatcher {
    const val THRESHOLD = 0.6

    private val parentheses = Regex("""\(.*?\)|\[.*?]""")
    private val nonAlnum = Regex("""[^\p{L}\p{N}]+""")
    private val diacritics = Regex("""\p{Mn}+""")

    // Compilées une fois : ces fonctions servent des milliers de fois pendant les correspondances.
    private val baseSeason = Regex("""(?i)\s+(season|saison|part|cour)\s*\d+.*$""")
    private val baseOrdinalSeason = Regex("""(?i)\s+\d+(st|nd|rd|th)\s+season.*$""")
    private val baseTrailingNumber = Regex("""\s+(\d+|II|III|IV|V)$""")
    private val seasonSuffix = Regex("""(?i)\s+(season|saison|cour)\s*\d+$""")
    private val ordinalSeasonSuffix = Regex("""(?i)\s+\d+(st|nd|rd|th)\s+season$""")
    private val trailingSeasonNumber = Regex("""\s+([2-9]|II|III|IV|V)$""")

    /**
     * Titre sans sous-titre ni numéro de saison ("Bleach: Thousand-Year…" → "Bleach",
     * "March Comes in Like a Lion 2nd Season" → "March Comes in Like a Lion").
     */
    fun baseTitle(title: String): String = title
        .substringBefore(':')
        .replace(baseSeason, "")
        .replace(baseOrdinalSeason, "")
        .replace(baseTrailingNumber, "")
        .trim()

    /**
     * Titre sans numéro de saison seulement ("… 2nd Season", "… Season 2", "… II"). Contrairement à
     * [baseTitle], le sous-titre et « Part N » sont gardés : « JoJo… Part 7: Steel Ball Run » n'est pas
     * la même fiche Crunchyroll que JoJo.
     */
    fun withoutSeason(title: String): String = title
        .replace(seasonSuffix, "")
        .replace(ordinalSeasonSuffix, "")
        .replace(trailingSeasonNumber, "")
        .trim()

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(diacritics, "")
            .replace(parentheses, " ")
            .replace(nonAlnum, " ")
            .trim()

    /**
     * Titre préparé une fois pour de nombreuses comparaisons ([similarity] de deux profils) : forme normalisée
     * et bigrammes. Comparer deux listes de centaines de titres avec [similarity] sur des chaînes renormalise
     * chaque titre des milliers de fois (fusion Crunchyroll + ADN de Parcourir : ~15 s sur la Shield).
     */
    class Profile internal constructor(val key: String, internal val bigrams: Map<Int, Int>) {
        internal val count = (key.length - 1).coerceAtLeast(0)
    }

    fun profile(title: String): Profile {
        val key = normalize(title).replace(" ", "")
        val bigrams = HashMap<Int, Int>()
        for (i in 0 until key.length - 1) {
            val code = (key[i].code shl 16) or key[i + 1].code
            bigrams[code] = (bigrams[code] ?: 0) + 1
        }
        return Profile(key, bigrams)
    }

    /** Même calcul que [similarity] sur des chaînes, sans renormaliser les titres. */
    fun similarity(a: Profile, b: Profile): Double {
        if (a.key.isEmpty() || b.key.isEmpty()) return 0.0
        if (a.key == b.key) return 1.0
        if (a.count < 1 || b.count < 1) return 0.0
        val (small, large) = if (a.bigrams.size <= b.bigrams.size) a to b else b to a
        var common = 0
        for ((k, v) in small.bigrams) common += minOf(v, large.bigrams[k] ?: 0)
        return 2.0 * common / (a.count + b.count)
    }

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
