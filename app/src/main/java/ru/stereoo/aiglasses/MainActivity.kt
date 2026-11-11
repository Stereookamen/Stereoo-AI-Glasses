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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Stereoo Qwen-Omni-Realtime proof of concept.
 *
 * Uses Qwen3.8-Omni-Flash-Realtime streaming audio (NOT Android TTS).
 * User's private key and workspace ID are entered at runtime, never persisted.
 * NOT YET the BV200 integration. Keep the activity open for the pilot.
 */
class MainActivity : ComponentActivity() {
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private lateinit var apiKeyField: EditText
    private lateinit var workspaceField: EditText
    private lateinit var status: TextView
    private lateinit var transcript: TextView

    @Volatile private var socket: WebSocket? = null
    @Volatile private var sessionReady = false
    @Volatile private var recording = false
    @Volatile private var playing = false
    @Volatile private var responding = false
    private var recorder: AudioRecord? = null
    private var speaker: AudioTrack? = null
    private var micThread: Thread? = null
    private var speakerThread: Thread? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>(96)

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) connect() else showStatus("Разреши использование микрофона.")
        }

    private val photoPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) sendPhoto(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 28, 30, 28)
        }
        root.addView(TextView(this).apply {
            text = "Stereoo Qwen Live • ТЕСТ"
            textSize = 23f
        })
        root.addView(TextView(this).apply {
            text = "Живой голос Qwen в реальном времени и обсуждение фото. " +
                "Это отдельное тестовое APK: кнопки BV200 пока не подключены."
            textSize = 15f
        })
        workspaceField = EditText(this).apply {
            hint = "Workspace ID (регион Singapore)"
            isSingleLine = true
        }
        root.addView(workspaceField)
        apiKeyField = EditText(this).apply {
            hint = "Alibaba Model Studio API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        root.addView(apiKeyField)
        root.addView(Button(this).apply {
            text = "🎙 Начать настоящий Realtime"
            setOnClickListener { requestPermissionAndConnect() }
        })
        root.addView(Button(this).apply {
            text = "📸 Фото в текущий разговор"
            setOnClickListener {
                if (sessionReady) photoPicker.launch("image/*")
                else showStatus("Сначала подключи голосовую сессию.")
            }
        })
        root.addView(Button(this).apply {
            text = "⏹ Завершить разговор"
            setOnClickListener { disconnect("Разговор завершён.") }
        })
        status = TextView(this).apply {
            textSize = 16f
            text = "Укажи Singapore Workspace ID и API key. Ключ не сохраняется."
        }
        root.addView(status)
        transcript = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(transcript)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun requestPermissionAndConnect() {
        if (sessionReady || socket != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) connect()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun connect() {
        val workspace = workspaceField.text.toString().trim().lowercase()
        val key = apiKeyField.text.toString().trim()
        // ID is placed in hostname; strict validation avoids URLs with arbitrary hosts.
        if (!Regex("[a-z0-9-]{3,100}").matches(workspace)) {
            showStatus("Проверь Workspace ID. Его можно скопировать из Model Studio.")
            return
        }
        if (key.isBlank()) {
            showStatus("Введи API key для Singapore в Alibaba Cloud.")
            return
        }
        val endpoint = "wss://$workspace.ap-southeast-1.maas.aliyuncs.com" +
            "/api-ws/v1/realtime?model=qwen3.8-omni-flash-realtime"
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .build()
        showStatus("Подключаюсь к Qwen-Omni-Realtime…")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                showStatus("Сокет открыт, ожидаю session.created…")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val evt = JSONObject(text)
                    when (evt.optString("type")) {
                        "session.created" -> sendSessionSetup(webSocket)
                        "session.updated" -> {
                            sessionReady = true
                            startAudio()
                            showStatus("🟢 Qwen Live: говори естественно, можно перебивать. " +
                                "Для фото нажми кнопку, затем задай вопрос.")
                        }
                        "input_audio_buffer.speech_started" -> {
                            clearSpeaker()
                            if (responding) {
                                webSocket.send(JSONObject()
                                    .put("type", "response.cancel").toString())
                                responding = false
                            }
                        }
                        "response.created" -> { responding = true }
                        "response.done" -> { responding = false }
                        "response.audio.delta" -> {
                            val pcm = Base64.decode(evt.optString("delta"), Base64.DEFAULT)
                            if (pcm.isNotEmpty() && !audioQueue.offer(pcm)) {
                                audioQueue.poll()
                                audioQueue.offer(pcm)
                            }
                        }
                        "response.audio_transcript.done" ->
                            evt.optString("transcript").takeIf { it.isNotBlank() }
                                ?.let { appendTranscript("Qwen: $it") }
                        "conversation.item.input_audio_transcription.completed" ->
                            evt.optString("transcript").takeIf { it.isNotBlank() }
                                ?.let { appendTranscript("Ты: $it") }
                        "error" -> {
                            val e = evt.optJSONObject("error")
                            val code = e?.optString("code").orEmpty()
                            val message = e?.optString("message").orEmpty()
                            showStatus("Ошибка Qwen: ${code.take(70)} ${message.take(180)}")
                        }
                    }
                } catch (_: Exception) {
                    showStatus("Ошибка обработки ответа Qwen.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Don't echo exception strings: on some clients they may contain auth data.
                runOnUiThread {
                    if (socket === webSocket) {
                        disconnect("Не подключилось (HTTP ${response?.code ?: "нет ответа"}). " +
                            "Проверь регион Singapore, Workspace ID, API key и доступ.")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    if (socket === webSocket)
                        disconnect("Сессия закрыта (код $code): ${reason.take(100)}")
                }
            }
        })
    }

    private fun sendSessionSetup(ws: WebSocket) {
        // Qwen3.8 WebSocket uses nested input/output PCM format.
        val inputFormat = JSONObject().put("type", "pcm")
            .put("sample_rate", 16000).put("sample_format", "s16le")
            .put("channels", 1).put("packing", "interleaved")
            .put("channel_layout", "mono")
        val audio = JSONObject()
            .put("input", JSONObject().put("format", inputFormat))
            .put("output", JSONObject().put("voice", "Tina")
                .put("format", JSONObject().put("type", "pcm").put("sample_rate", 24000)))
        val session = JSONObject()
            .put("modalities", JSONArray().put("text").put("audio"))
            .put("instructions", "Ты голосовой помощник в умных очках. " +
                "Общайся естественно и дружелюбно на русском языке, не читай текст роботом. " +
                "Отвечай кратко. Когда пользователь присылает фотографию, " +
                "обсуждай именно изображение и помни контекст разговора.")
            .put("turn_detection", JSONObject()
                .put("type", "semantic_vad_ec")
                .put("threshold", 0.5)
                .put("prefix_padding_ms", 500)
                .put("silence_duration_ms", 800))
            .put("audio", audio)
        val command = JSONObject().put("type", "session.update").put("session", session)
        ws.send(command.toString())
    }

    private fun startAudio() {
        startSpeaker()
        recording = true
        micThread = Thread({
            val min = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) {
                showStatus("Телефон не поддерживает PCM 16 кГц.")
                return@Thread
            }
            var input: AudioRecord? = null
            try {
                input = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
                recorder = input
                if (input.state != AudioRecord.STATE_INITIALIZED) {
                    showStatus("Микрофон не открылся.")
                    return@Thread
                }
                input.startRecording()
                val chunk = ByteArray(3200) // 100 ms, PCM16, mono, 16 kHz.
                while (recording) {
                    val count = input.read(chunk, 0, chunk.size)
                    if (count > 0) {
                        val bytes = if (count == chunk.size) chunk else chunk.copyOf(count)
                        val event = JSONObject()
                            .put("type", "input_audio_buffer.append")
                            .put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        if (socket?.send(event.toString()) != true) break
                    }
                }
            } catch (_: SecurityException) {
                showStatus("Микрофон запрещён в настройках Android.")
            } catch (_: Exception) {
                if (recording) showStatus("Ошибка микрофона.")
            } finally {
                try { input?.stop() } catch (_: Exception) {}
                input?.release()
                if (recorder === input) recorder = null
            }
        }, "QwenLiveMic").apply { start() }
    }

    private fun startSpeaker() {
        val min = AudioTrack.getMinBufferSize(
            24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            showStatus("Нет PCM 24 кГц для воспроизведения.")
            return
        }
        val output = AudioTrack(
            AudioManager.STREAM_VOICE_CALL, 24000,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, 9600), AudioTrack.MODE_STREAM)
        speaker = output
        playing = true
        speakerThread = Thread({
            try {
                output.play()
                while (playing) {
                    val chunk = audioQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    if (!playing) break
                    output.write(chunk, 0, chunk.size)
                }
            } catch (_: Exception) {
                // Closing a stream during playback is expected.
            } finally {
                try { output.stop() } catch (_: Exception) {}
                output.release()
                if (speaker === output) speaker = null
            }
        }, "QwenLiveSpeaker").apply { start() }
    }

    private fun clearSpeaker() {
        audioQueue.clear()
        try {
            speaker?.pause()
            speaker?.flush()
            speaker?.play()
        } catch (_: Exception) {}
    }

    private fun sendPhoto(uri: Uri) {
        if (!sessionReady) return
        Thread({
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot open image")
                val original = input.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalArgumentException("Cannot decode image")
                var side = 900
                var jpeg: ByteArray? = null
                // Qwen image limit: base64 <=256KB; keep JPEG <=185KB with margin.
                for (tryIndex in 0..7) {
                    val scale = minOf(1.0, side.toDouble() /
                        maxOf(original.width, original.height))
                    val copy = Bitmap.createScaledBitmap(original,
                        maxOf(1, (original.width * scale).toInt()),
                        maxOf(1, (original.height * scale).toInt()), true)
                    val out = ByteArrayOutputStream()
                    copy.compress(Bitmap.CompressFormat.JPEG, 64, out)
                    jpeg = out.toByteArray()
                    if (copy !== original) copy.recycle()
                    if (jpeg.size <= 185000) break
                    side = (side * 0.78).toInt()
                }
                original.recycle()
                val bytes = jpeg ?: return@Thread
                if (bytes.size > 185000) {
                    showStatus("Слишком большое фото, выбери другое.")
                    return@Thread
                }
                val msg = JSONObject()
                    .put("type", "input_image_buffer.append")
                    .put("image", Base64.encodeToString(bytes, Base64.NO_WRAP))
                if (socket?.send(msg.toString()) == true) {
                    appendTranscript("📷 Фото отправлено в этот же разговор.")
                    showStatus("📷 Снимок отправлен. Теперь скажи: «Что это на фото?»")
                } else showStatus("Не удалось отправить фото.")
            } catch (_: Exception) {
                showStatus("Не удалось обработать фотографию.")
            }
        }, "QwenPhoto").start()
    }

    private fun disconnect(message: String) {
        recording = false
        sessionReady = false
        responding = false
        try { recorder?.stop() } catch (_: Exception) {}
        playing = false
        audioQueue.clear()
        speakerThread?.interrupt()
        try { speaker?.pause() } catch (_: Exception) {}
        val ws = socket
        socket = null
        try { ws?.close(1000, "user stop") } catch (_: Exception) {}
        showStatus(message)
    }

    private fun showStatus(value: String) {
        runOnUiThread { status.text = value }
    }

    private fun appendTranscript(value: String) {
        runOnUiThread {
            transcript.text = transcript.text.toString().takeLast(6000) + "\n" + value
        }
    }

    override fun onDestroy() {
        disconnect("Остановлено.")
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
