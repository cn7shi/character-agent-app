package com.character.app.characters

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Deep Module: CharacterVoicePlayer
 *
 * Encapsulates:
 * - Local disk caching for synthesized MP3 speech
 * - Remote high-fidelity neural TTS synthesis from backend (/v1/tts/synthesize)
 * - MediaPlayer streaming and playback state synchronization (playing/pause/wave progress)
 * - Seamless fallback to Android System TextToSpeech if network is unavailable
 */
object VoicePlayer {
    private const val TAG = "VoicePlayer"
    var activeBackendUrl: String = "http://10.0.2.2:8000/"
    private const val DEFAULT_DEV_TOKEN = "VXyaOWUsTIYIJftfvo0qYe26FUizGyPXtGYarIs4-nQ"

    private var mediaPlayer: MediaPlayer? = null
    private var fallbackTts: TextToSpeech? = null
    private var isFallbackTtsReady = false

    private val _isPlaying = mutableStateOf(false)
    val isPlaying: State<Boolean> = _isPlaying

    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    private val _currentCountdown = mutableIntStateOf(0)
    val currentCountdown: State<Int> = _currentCountdown

    private val _totalDuration = mutableIntStateOf(5)
    val totalDuration: State<Int> = _totalDuration

    private val _activeSpokenText = mutableStateOf<String?>(null)
    val activeSpokenText: State<String?> = _activeSpokenText

    private val mainHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun initFallbackTts(context: Context) {
        if (fallbackTts == null) {
            fallbackTts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = fallbackTts?.setLanguage(Locale.CHINESE)
                    isFallbackTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                }
            }
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Toggles playback for the specified character line.
     * If currently playing this line -> pause.
     * If paused on this line -> resume.
     * If new line -> synthesize (or read from cache) and play.
     */
    fun togglePlay(
        context: Context,
        characterName: String,
        rawText: String,
        backendBaseUrl: String = activeBackendUrl
    ) {
        val cleanKey = "${characterName}_$rawText"
        if (_activeSpokenText.value == cleanKey) {
            val player = mediaPlayer
            if (player != null) {
                if (player.isPlaying) {
                    player.pause()
                    _isPlaying.value = false
                    stopCountdown()
                    return
                } else {
                    player.start()
                    _isPlaying.value = true
                    startCountdown()
                    return
                }
            }
        }

        // New line or fresh playback request
        stop()
        _activeSpokenText.value = cleanKey
        _isLoading.value = true

        scope.launch {
            val cacheKey = md5("$characterName:$rawText")
            val cacheDir = File(context.cacheDir, "tts_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val cacheFile = File(cacheDir, "$cacheKey.mp3")

            if (cacheFile.exists() && cacheFile.length() > 0) {
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    playAudioFile(cacheFile)
                }
                return@launch
            }

            // Fetch from backend
            val downloaded = downloadAudio(backendBaseUrl, characterName, rawText, cacheFile)
            withContext(Dispatchers.Main) {
                _isLoading.value = false
                if (downloaded && cacheFile.exists() && cacheFile.length() > 0) {
                    playAudioFile(cacheFile)
                } else {
                    Log.w(TAG, "Backend TTS unavailable or failed, falling back to Android TextToSpeech.")
                    playSystemFallback(context, characterName, rawText)
                }
            }
        }
    }

    private fun downloadAudio(
        backendBaseUrl: String,
        characterName: String,
        text: String,
        targetFile: File
    ): Boolean {
        return try {
            val normalizedBase = if (backendBaseUrl.endsWith("/")) backendBaseUrl else "$backendBaseUrl/"
            val endpoint = URL("${normalizedBase}v1/tts/synthesize")
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $DEFAULT_DEV_TOKEN")

            val jsonBody = """
                {"text": ${quoteJson(text)}, "character_name": ${quoteJson(characterName)}}
            """.trimIndent()

            connection.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            if (connection.responseCode == 200) {
                connection.inputStream.use { input: InputStream ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } else {
                Log.e(TAG, "HTTP synthesis error: ${connection.responseCode} ${connection.responseMessage}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading speech audio", e)
            false
        }
    }

    private fun quoteJson(string: String): String {
        return buildString {
            append('"')
            for (c in string) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }
    }

    private fun playAudioFile(file: File) {
        try {
            stop()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
            }

            mediaPlayer = player
            val durMs = player.duration
            val durSec = if (durMs > 0) (durMs + 999) / 1000 else 5
            _totalDuration.intValue = durSec
            _currentCountdown.intValue = durSec

            player.setOnCompletionListener {
                _isPlaying.value = false
                _currentCountdown.intValue = _totalDuration.intValue
                stopCountdown()
            }

            player.start()
            _isPlaying.value = true
            startCountdown()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio file", e)
            _isPlaying.value = false
            stopCountdown()
        }
    }

    private fun playSystemFallback(context: Context, characterName: String, text: String) {
        initFallbackTts(context)
        val tts = fallbackTts
        if (tts != null) {
            // Apply persona-based pitch and speech rate on system TTS
            when {
                characterName.contains("霍司爵") || characterName.contains("严烈") -> {
                    tts.setPitch(0.82f)
                    tts.setSpeechRate(0.92f)
                }
                characterName.contains("萧寒") -> {
                    tts.setPitch(1.0f)
                    tts.setSpeechRate(0.88f)
                }
                characterName.contains("江野") -> {
                    tts.setPitch(1.1f)
                    tts.setSpeechRate(1.15f)
                }
                characterName.contains("希尔达") -> {
                    tts.setPitch(1.05f)
                    tts.setSpeechRate(1.0f)
                }
                else -> {
                    tts.setPitch(1.0f)
                    tts.setSpeechRate(1.0f)
                }
            }

            // Clean stage directions from speech
            val cleaned = text.replace(Regex("""[\(（【\[\*][^\)）】\]\*]+[\)）】\]\*]"""), "").trim()
            val spoken = if (cleaned.isNotEmpty()) cleaned else text

            val durSec = maxOf(2, (spoken.length / 3.5).toInt())
            _totalDuration.intValue = durSec
            _currentCountdown.intValue = durSec
            _isPlaying.value = true
            startCountdown()

            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "fallback_utterance")
        }
    }

    private fun startCountdown() {
        stopCountdown()
        val runnable = object : Runnable {
            override fun run() {
                if (_isPlaying.value && _currentCountdown.intValue > 0) {
                    _currentCountdown.intValue -= 1
                    mainHandler.postDelayed(this, 1000L)
                } else if (_currentCountdown.intValue <= 0) {
                    _isPlaying.value = false
                    _currentCountdown.intValue = _totalDuration.intValue
                }
            }
        }
        countdownRunnable = runnable
        mainHandler.postDelayed(runnable, 1000L)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        stopCountdown()
        _isPlaying.value = false
        _isLoading.value = false
    }
}
