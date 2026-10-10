package com.dakyub.crunchymal

import android.content.Context
import com.dakyub.crunchymal.data.Provider
import com.dakyub.crunchymal.data.SeriesRef

/** Lance un épisode dans l'app officielle du service de la série (Crunchyroll ou ADN). */
fun playEpisode(context: Context, series: SeriesRef, episodeId: String) {
    when (series.provider) {
        Provider.CRUNCHYROLL -> OfficialApp.openEpisode(context, episodeId, series.id.ifBlank { null })
        Provider.ADN -> AdnApp.open(context, series.id, episodeId)
    }
}
