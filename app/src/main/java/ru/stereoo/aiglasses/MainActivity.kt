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
    @Volatile private var lastSocketEvent: String = "подключение не начиналось"
    @Volatile private var lastPhase = "Подключение не начато"
    @Volatile private var lastApiError = ""
    @Volatile private var everOpened = false
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
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
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
        lastPhase = "Подключаюсь к серверу"
        lastApiError = ""
        everOpened = false
        showStatus("Подключаюсь к Qwen-Omni-Realtime…")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                everOpened = true
                lastPhase = "WebSocket открыт; ожидаем session.created"
                showStatus(lastPhase)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val evt = JSONObject(text)
                    val type = evt.optString("type")
                    if (type != "response.audio.delta" && type != "response.audio_transcript.delta") {
                        lastSocketEvent = type.take(80)
                    }
                    when (type) {
                        "session.created" -> {
                            lastPhase = "Получено session.created; отправляем настройки"
                            sendSessionSetup(webSocket)
                        }
                        "session.updated" -> {
                            lastPhase = "Получено session.updated; запускаем аудио"
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
                            val param = e?.optString("param").orEmpty()
                            lastApiError = "Qwen API: код ${code.take(60)}; параметр ${param.take(60)}; " +
                                message.take(160)
                            lastPhase = "Сервер прислал error"
                            showStatus(lastApiError)
                        }
                    }
                } catch (_: Exception) {
                    showStatus("Ошибка обработки ответа Qwen.")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // If the server initiates a clean close, the close code is diagnostic.
                lastPhase = "Сервер начал закрывать WebSocket, код $code"
                showStatus(lastPhase)
                webSocket.close(code, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A WebSocket can open successfully but fail later. Keep the last known stage.
                val diagnostic = if (lastApiError.isNotBlank()) {
                    lastApiError
                } else {
                    classifyConnectionFailure(t, response?.code) + " | Этап: $lastPhase"
                }
                runOnUiThread {
                    if (socket === webSocket) disconnect(diagnostic)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    if (socket === webSocket) {
                        val diagnostic = if (lastApiError.isNotBlank()) lastApiError
                            else "WebSocket закрыт, код $code; этап: $lastPhase"
                        disconnect(diagnostic)
                    }
                }
            }
        })
    }

    private fun classifyConnectionFailure(error: Throwable, code: Int?): String {
        if (code != null) {
            return when (code) {
                401 -> "HTTP 401: API-ключ отклонён. Проверь ключ Singapore."
                403 -> "HTTP 403: доступ или квота запрещены. Проверь лимиты и разрешения."
                404 -> "HTTP 404: проверь Workspace ID и адрес регионального API."
                429 -> "HTTP 429: слишком много запросов. Проверь лимиты в кабинете."
                else -> "Ответ сервера HTTP $code. Проверь регион, Workspace ID и статус модели."
            }
        }
        var cause: Throwable = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        // Intentionally omit cause.message: it may contain the request URL or other details.
        return when (cause) {
            is java.io.EOFException ->
                "EOF: сервер или посредник разорвал WebSocket после открытия. " +
                    "Это не доказывает проблему VPN или неверный ключ."
            is java.net.UnknownHostException ->
                "Ошибка DNS: адрес сервера не найден. Проверь Workspace ID и доступность сети через VPN."
            is javax.net.ssl.SSLException ->
                "Ошибка TLS: не удалось установить защищённое соединение. Проверь сеть, дату и VPN."
            is java.net.SocketTimeoutException ->
                "Таймаут: сервер не отвечает. Попробуй Wi-Fi и другой режим VPN."
            is java.net.ConnectException ->
                "Соединение отклонено. Проверь сеть и доступность сервера через VPN."
            is java.net.SocketException ->
                "Соединение оборвалось. Попробуй переключить Wi-Fi/4G или VPN."
            is java.net.ProtocolException ->
                "Ошибка WebSocket-протокола до соединения."
            is java.io.EOFException ->
                if (everOpened) "Сервер/сеть оборвали WebSocket после открытия (EOF)."
                else "Сервер/сеть оборвали соединение до открытия WebSocket (EOF)."
            else ->
                "Сбой соединения: ${cause.javaClass.simpleName}. " +
                    "Проверь настройки и доступность сервера."
        }
    }

    private fun sendSessionSetup(ws: WebSocket) {
        // Minimal official Qwen3.8 session config. PCM defaults: 16 kHz in, 24 kHz out.
        // Complex nested audio overrides are intentionally omitted until the first
        // successful session.updated event, to isolate protocol vs network failures.
        val session = JSONObject()
            .put("modalities", JSONArray().put("text").put("audio"))
            .put("voice", "Tina")
            .put("turn_detection", JSONObject().put("type", "server_vad"))
            .put("instructions", "Ты русскоязычный ассистент умных очков. " +
                "Общайся естественно и кратко. Отвечай голосом. " +
                "Когда пользователь присылает фотографию, обсуждай её содержимое.")
        val event = JSONObject().put("type", "session.update").put("session", session)
        ws.send(event.toString())
        lastSocketEvent = "session.update отправлен"
        showStatus("Сервер подтвердил session.created. Отправил session.update…")
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
