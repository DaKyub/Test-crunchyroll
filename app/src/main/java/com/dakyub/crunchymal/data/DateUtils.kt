package com.dakyub.crunchymal.data

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Dates ISO 8601 des API (sans java.time, indisponible avant Android 8 sans désucrage). */
object DateUtils {
    private fun utcFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** "2026-10-10T14:30:00Z", "…+02:00" ou "…​.123Z" → Date, ou null. */
    fun parse(iso: String?): Date? {
        if (iso.isNullOrBlank() || iso.length < 19) return null
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(iso)
            } catch (_: ParseException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return try {
            utcFormat().parse(iso.take(19))
        } catch (_: ParseException) {
            null
        }
    }

    /** Date au format ISO UTC sans fuseau (comparable comme du texte avec les dates des API). */
    fun isoUtc(date: Date = Date()): String = utcFormat().format(date)

    fun isoDaysAgo(days: Int): String = isoUtc(Date(System.currentTimeMillis() - days * 86_400_000L))
}
