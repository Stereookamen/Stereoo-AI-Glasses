package ru.stereoo.aiglasses

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Isolated Gemini Live prototype for validating speech-to-speech and photo conversation.
 *
 * This is NOT the BV200 integration yet. The activity must remain open during testing.
 * The API key is not persisted or logged. Production should use ephemeral tokens.
 */
class MainActivity : ComponentActivity() {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private lateinit var keyInput: EditText
    private lateinit var status: TextView
    private lateinit var history: TextView
    private var socket: WebSocket? = null
    @Volatile private var live = false
    @Volatile private var recording = false
    @Volatile private var playing = false
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(80)

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) connect() else setStatus("Нужен доступ к микрофону.")
        }

    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) sendPhoto(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 28, 30, 28)
        }
        panel.addView(TextView(this).apply {
            text = "Stereoo Gemini Live • ТЕСТ"
            textSize = 23f
        })
        panel.addView(TextView(this).apply {
            text = "Настоящий потоковый голос и обсуждение фото. Пока только тест на телефоне, без кнопок BV200 и без фонового режима."
            textSize = 15f
        })
        keyInput = EditText(this).apply {
            hint = "API key из Google AI Studio"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        panel.addView(keyInput)
        panel.addView(Button(this).apply {
            text = "🎙 Начать Live"
            setOnClickListener { requestMicAndConnect() }
        })
        panel.addView(Button(this).apply {
            text = "📷 Отправить фото в текущий разговор"
            setOnClickListener {
                if (live) photoPicker.launch("image/*")
                else setStatus("Сначала запусти Live-разговор.")
            }
        })
        panel.addView(Button(this).apply {
            text = "⏹ Завершить"
            setOnClickListener { disconnect("Остановлено пользователем.") }
        })
        status = TextView(this).apply {
            textSize = 16f
            text = "Введи ключ и нажми «Начать Live»."
        }
        panel.addView(status)
        history = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        panel.addView(history)
        setContentView(ScrollView(this).apply { addView(panel) })
    }

    private fun requestMicAndConnect() {
        if (live || socket != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            connect()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun connect() {
        val key = keyInput.text.toString().trim()
        if (key.isBlank()) {
            setStatus("Нужен API-ключ, доступный для Gemini Live.")
            return
        }
        setStatus("Подключаю Gemini Live…")
        // Key kept only in RAM for this private proof-of-concept.
        // Never use a permanent key embedded in a publicly distributed APK.
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" +
            URLEncoder.encode(key, "UTF-8")
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setup = JSONObject().put("setup", JSONObject()
                    .put("model", "models/gemini-3.8-live")
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put("systemInstruction", JSONObject().put("parts", JSONArray()
                        .put(JSONObject().put("text",
                            "Говори естественно по-русски, как живой собеседник. " +
                            "Отвечай кратко и по существу. Учитывай изображения из беседы.")))))
                webSocket.send(setup.toString())
                setStatus("Соединение открыто, ожидаю настройку сессии…")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    if (message.has("setupComplete")) {
                        live = true
                        startAudio()
                        setStatus("🟢 Live активен. Говори, перебивай, отправляй фото.")
                    }
                    val content = message.optJSONObject("serverContent")
                    if (content != null) {
                        if (content.optBoolean("interrupted", false)) clearPlayback()
                        val turn = content.optJSONObject("modelTurn")
                        val parts = turn?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData")
                                    ?: continue
                                if (inline.optString("mimeType").startsWith("audio/pcm")) {
                                    val pcm = Base64.decode(inline.getString("data"), Base64.DEFAULT)
                                    if (!playbackQueue.offer(pcm)) {
                                        playbackQueue.poll()
                                        playbackQueue.offer(pcm)
                                    }
                                }
                            }
                        }
                        content.optJSONObject("inputTranscription")
                            ?.optString("text")?.takeIf { it.isNotBlank() }
                            ?.let { appendHistory("Ты: $it") }
                        content.optJSONObject("outputTranscription")
                            ?.optString("text")?.takeIf { it.isNotBlank() }
                            ?.let { appendHistory("Gemini: $it") }
                    }
                    if (message.has("goAway")) setStatus("Сессия скоро завершится, переподключение пока не реализовано.")
                } catch (e: Exception) {
                    setStatus("Не удалось разобрать ответ: ${e.javaClass.simpleName}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Never print t.message: some URL errors may accidentally contain an API key.
                val httpCode = response?.code?.toString() ?: "нет ответа HTTP"
                runOnUiThread { disconnect("Ошибка соединения (HTTP $httpCode). " +
                    "Проверь доступность API, ключ и регион.") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    if (socket === webSocket) {
                        disconnect("Соединение закрыто: код $code, ${reason.take(160)}")
                    }
                }
            }
        })
    }

    private fun startAudio() {
        startPlayback()
        recording = true
        captureThread = Thread({
            val minimum = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) {
                setStatus("Микрофон не поддерживает формат PCM 16 кГц.")
                return@Thread
            }
            try {
                val input = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, 16000))
                recorder = input
                if (input.state != AudioRecord.STATE_INITIALIZED) {
                    setStatus("Не удалось открыть микрофон.")
                    input.release()
                    return@Thread
                }
                input.startRecording()
                val chunk = ByteArray(3200) // 100 ms / mono / 16-bit / 16kHz
                while (recording) {
                    val count = input.read(chunk, 0, chunk.size)
                    if (count > 0) {
                        val pcm = if (count == chunk.size) chunk else chunk.copyOf(count)
                        val json = JSONObject().put("realtimeInput", JSONObject().put(
                            "audio", JSONObject()
                                .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                                .put("mimeType", "audio/pcm;rate=16000")))
                        if (socket?.send(json.toString()) != true) break
                    }
                }
                try { input.stop() } catch (_: Exception) {}
                input.release()
                recorder = null
            } catch (e: SecurityException) {
                setStatus("Нет разрешения для микрофона.")
            } catch (e: Exception) {
                if (recording) setStatus("Ошибка аудиоввода: ${e.javaClass.simpleName}")
            }
        }, "GeminiLiveCapture").apply { start() }
    }

    private fun startPlayback() {
        val minSize = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) {
            setStatus("Вывод PCM 24 кГц не поддерживается.")
            return
        }
        val output = AudioTrack(
            AudioManager.STREAM_VOICE_CALL, 24000,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minSize, 9600), AudioTrack.MODE_STREAM)
        player = output
        playing = true
        playThread = Thread({
            try {
                output.play()
                while (playing) {
                    val pcm = playbackQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    if (!playing) break
                    output.write(pcm, 0, pcm.size)
                }
            } catch (_: Exception) {
                // Expected if playback stops while the stream is being written.
            } finally {
                try { output.stop() } catch (_: Exception) {}
                output.release()
                if (player === output) player = null
            }
        }, "GeminiLivePlayback").apply { start() }
    }

    private fun clearPlayback() {
        playbackQueue.clear()
        try {
            player?.pause()
            player?.flush()
            player?.play()
        } catch (_: Exception) {}
    }

    private fun sendPhoto(uri: Uri) {
        val ws = socket
        if (!live || ws == null) return
        Thread({
            try {
                val bitmap = contentResolver.openInputStream(uri).use { input ->
                    BitmapFactory.decodeStream(input)
                } ?: throw IllegalArgumentException("Не удалось открыть фото")
                val maxSide = 1280
                val scale = minOf(1.0, maxSide.toDouble() / maxOf(bitmap.width, bitmap.height))
                val scaled = if (scale < 1.0) {
                    Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                } else bitmap
                val jpeg = ByteArrayOutputStream().use { stream ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 78, stream)
                    stream.toByteArray()
                }
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                val blob = JSONObject()
                    .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                    .put("mimeType", "image/jpeg")
                val frame = JSONObject().put("realtimeInput",
                    JSONObject().put("video", blob))
                if (ws.send(frame.toString())) {
                    ws.send(JSONObject().put("realtimeInput",
                        JSONObject().put("text", "Опиши полученное изображение и обсуди его со мной."))
                        .toString())
                    appendHistory("📷 Фото отправлено в текущую Live-сессию.")
                } else setStatus("Не удалось отправить фотографию.")
            } catch (e: Exception) {
                setStatus("Ошибка фото: ${e.javaClass.simpleName}")
            }
        }, "GeminiPhoto").start()
    }

    private fun disconnect(message: String) {
        recording = false
        live = false
        try { recorder?.stop() } catch (_: Exception) {}
        playing = false
        playbackQueue.clear()
        playThread?.interrupt()
        try { player?.pause() } catch (_: Exception) {}
        try { socket?.close(1000, "done") } catch (_: Exception) {}
        socket = null
        setStatus(message)
    }

    private fun setStatus(text: String) {
        runOnUiThread { status.text = text }
    }

    private fun appendHistory(text: String) {
        runOnUiThread {
            val recent = history.text.toString().takeLast(6000)
            history.text = "$recent\n$text"
        }
    }

    override fun onDestroy() {
        disconnect("Закрыто.")
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
