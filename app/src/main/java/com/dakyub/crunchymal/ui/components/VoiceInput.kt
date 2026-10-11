package com.dakyub.crunchymal.ui.components

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Dictée dans l'app (service vocal Google via [SpeechRecognizer]), comme la recherche des apps Android TV :
 * le micro de la télécommande est ouvert pendant l'écoute. Il faut l'autorisation RECORD_AUDIO.
 */
class VoiceInput(private val context: Context, private val onResult: (String) -> Unit) : RecognitionListener {
    var listening by mutableStateOf(false)
        private set
    /** Texte reconnu en direct pendant l'écoute. */
    var partial by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Volume capté (0 à 1) : montre que le micro entend quelque chose. */
    var level by mutableFloatStateOf(0f)
        private set

    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun start() {
        error = null
        partial = null
        level = 0f
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?.also { it.setRecognitionListener(this); recognizer = it }
        if (r == null) {
            error = "Service de reconnaissance vocale introuvable."
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        listening = true
        runCatching { r.startListening(intent) }.onFailure {
            listening = false
            error = "Écoute impossible : ${it.message}"
        }
    }

    /** Termine l'écoute : ce qui a été dit est reconnu. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
    }

    override fun onReadyForSpeech(params: Bundle?) {
        listening = true
    }

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {
        // Environ -2 dB (silence) à 10 dB (voix forte).
        level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        level = 0f
    }

    override fun onError(code: Int) {
        listening = false
        level = 0f
        error = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Rien compris, réessaie en parlant plus près de la télécommande."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Aucune voix entendue. Parle juste après avoir appuyé sur « Dicter »."
            SpeechRecognizer.ERROR_AUDIO -> "Micro indisponible (erreur audio)."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Autorisation du micro refusée (Paramètres Android → Applis → CrunchyMAL)."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Erreur réseau pendant la reconnaissance."
            SpeechRecognizer.ERROR_SERVER -> "Le service vocal Google a renvoyé une erreur."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Le service vocal est occupé, réessaie dans un instant."
            SpeechRecognizer.ERROR_CLIENT -> "Écoute interrompue."
            12, 13 -> "Le français n'est pas disponible pour la reconnaissance vocale."
            else -> "Erreur de reconnaissance vocale ($code)."
        }
    }

    override fun onResults(results: Bundle?) {
        listening = false
        level = 0f
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        if (text.isNullOrEmpty()) {
            error = "Rien compris, réessaie."
        } else {
            partial = null
            onResult(text)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            ?.takeIf { it.isNotBlank() }?.let { partial = it }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}

@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): VoiceInput {
    val context = LocalContext.current
    val callback by rememberUpdatedState(onResult)
    val voice = remember { VoiceInput(context) { callback(it) } }
    DisposableEffect(voice) { onDispose { voice.destroy() } }
    return voice
}
