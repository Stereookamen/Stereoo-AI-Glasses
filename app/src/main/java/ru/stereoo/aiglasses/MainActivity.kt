package ru.stereoo.aiglasses

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Isolated Yandex AI Studio Speech Realtime Max TTS Live phone-only pilot.
 *
 * WebSocket wire format, URL, authentication, and audio format are taken from the
 * official Yandex Realtime API sample (Nov 2026). Audio is streamed over WS.
 * There is NO Android TextToSpeech or speech recognizer in this application.
 *
 * Deliberately no credential persistence; only entered in this activity RAM.
 * Before production: BYOK key security, scoped service account, BV200 routing,
 * foreground service for lockscreen, image analysis tool, and durable memory.
 */
class MainActivity : ComponentActivity() {
    private val sampleRate = 44100
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private lateinit var folderField: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var voiceSpinner: Spinner
    private lateinit var state: TextView
    private lateinit var log: TextView

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var capturing = false
    @Volatile private var outputActive = false
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var inputThread: Thread? = null
    private var outputThread: Thread? = null
    private val outputQueue = LinkedBlockingQueue<ByteArray>(64)
    private var liveResponse = false

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) showBillingConfirmation() else setState("Доступ к микрофону запрещён.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 26)
        }
        fun text(s: String, size: Float): TextView = TextView(this).apply {
            this.text = s
            textSize = size
            setPadding(0, 3, 0, 10)
        }
        container.addView(text("Stereoo • Yandex Realtime", 23f))
        container.addView(text(
            "Отдельный тест на телефоне. Живая потоковая речь Yandex LiveTTS, " +
                "не Android TTS. Пока без BV200, фото и памяти.", 15f))
        folderField = EditText(this).apply {
            hint = "Folder ID каталога Yandex Cloud"
            isSingleLine = true
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        container.addView(folderField)

        apiKeyField = EditText(this).apply {
            hint = "API-ключ Yandex AI Studio (не сохраняется)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        container.addView(apiKeyField)
        container.addView(text("Голос LiveTTS:", 16f))
        voiceSpinner = Spinner(this)
        voiceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Денис • разговорный", "Софья • разговорный", "Вера • разговорный")
        )
        container.addView(voiceSpinner)
        container.addView(Button(this).apply {
            text = "🎙 Начать Realtime"
            setOnClickListener { requestAndStart() }
        })
        container.addView(Button(this).apply {
            text = "⏹ Завершить"
            setOnClickListener { stopSession("Разговор остановлен.") }
        })
        state = text("Готово. Для теста нужен активный каталог Yandex Cloud и API-ключ.", 16f)
        container.addView(state)
        log = text("", 14f).apply { setTextIsSelectable(true) }
        container.addView(log)
        setContentView(ScrollView(this).apply { addView(container) })
    }

    private fun requestAndStart() {
        if (socket != null) {
            setState("Сессия уже запущена; сначала нажми «Завершить».")
            return
        }
        val folder = folderField.text.toString().trim()
        if (!Regex("[a-zA-Z0-9_-]{8,100}").matches(folder)) {
            setState("Нужен действительный Folder ID каталога.")
            return
        }
        if (apiKeyField.text.toString().trim().isEmpty()) {
            setState("Введи API-ключ сервисного аккаунта с правами ai.models.user.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else showBillingConfirmation()
    }

    private fun showBillingConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Платный тест Yandex AI Studio")
            .setMessage("Этот Realtime API оплачивается по фактическому использованию. " +
                "Даже короткая сессия может расходовать деньги. " +
                "Подтверди, что биллинг и ограничения настроены в Yandex Cloud.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Подключиться") { _, _ -> connect() }
            .show()
    }

    private fun connect() {
        if (socket != null) return
        val folder = folderField.text.toString().trim()
        val key = apiKeyField.text.toString().trim()
        val model = "gpt://$folder/speech-realtime-max-tts-live/latest"
        // Model URI and Authorization header from official Yandex AI Studio example.
        val url = "wss://ai.api.cloud.yandex.net/v1/realtime/openai?model=$model"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Api-Key $key")
            .build()
        val voice = when (voiceSpinner.selectedItemPosition) {
            1 -> "sofia"
            2 -> "vera"
            else -> "denis"
        }
        log.text = ""
        setState("Соединение с Яндексом…")
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                note("WebSocket открыт (HTTP 101).")
                val session = JSONObject()
                    .put("type", "realtime")
                    .put("instructions",
                        "Ты Stereoo, доброжелательный русскоязычный голосовой помощник. " +
                        "Общайся естественно, отвечай точно, по делу и кратко. " +
                        "Не утверждай, что видишь изображения, пока они не переданы.")
                    .put("output_modalities", JSONArray().put("audio"))
                    .put("audio", JSONObject()
                        .put("input", JSONObject()
                            .put("format", JSONObject()
                                .put("type", "audio/pcm")
                                .put("rate", sampleRate))
                            .put("turn_detection", JSONObject()
                                .put("type", "server_vad")
                                .put("threshold", 0.5)
                                .put("silence_duration_ms", 450)))
                        .put("output", JSONObject()
                            .put("format", JSONObject()
                                .put("type", "audio/pcm")
                                .put("rate", sampleRate))
                            .put("voice", voice)
                            .put("role", if (voice == "denis") "casual" else "casual")))
                val payload = JSONObject().put("type", "session.update").put("session", session)
                if (ws.send(payload.toString())) {
                    setState("Отправил session.update. Жду подтверждения…")
                } else setState("Не удалось отправить настройки сессии.")
            }

            override fun onMessage(ws: WebSocket, message: String) {
                try {
                    val event = JSONObject(message)
                    when (val type = event.optString("type")) {
                        "session.created" -> note("Сессия создана сервером.")
                        "session.updated" -> {
                            note("Настройки подтверждены.")
                            if (!ready) {
                                ready = true
                                startAudio()
                                setState("🟢 Realtime активен. Говори, можно перебивать.")
                            }
                        }
                        "input_audio_buffer.speech_started" -> {
                            liveResponse = false
                            interruptPlayback()
                        }
                        "response.created" -> { liveResponse = true }
                        "response.output_audio.delta" -> {
                            // Stream PCM bytes generated by the server, never Android TTS.
                            if (liveResponse) {
                                val audio = Base64.decode(event.optString("delta"), Base64.DEFAULT)
                                if (audio.isNotEmpty() && !outputQueue.offer(audio)) {
                                    outputQueue.poll()
                                    outputQueue.offer(audio)
                                }
                            }
                        }
                        "conversation.item.input_audio_transcription.completed" -> {
                            event.optString("transcript").takeIf { it.isNotEmpty() }
                                ?.let { note("Ты: " + it.take(150)) }
                        }
                        "response.output_audio_transcript.done" -> {
                            event.optString("transcript").takeIf { it.isNotEmpty() }
                                ?.let { note("Ассистент: " + it.take(180)) }
                        }
                        "response.done" -> { liveResponse = false }
                        "error" -> {
                            val e = event.optJSONObject("error")
                            note("Ошибка API: " + e?.optString("type", "unknown").orEmpty().take(80))
                            setState(e?.optString("message", "Сервер отклонил запрос.")?.take(250)
                                ?: "Сервер вернул ошибку.")
                        }
                        else -> {
                            if (type != "response.output_text.delta") {
                                note("Событие: " + type.take(75))
                            }
                        }
                    }
                } catch (e: Exception) {
                    note("Ошибка разбора события: " + e.javaClass.simpleName)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val result = when (response?.code) {
                    401 -> "HTTP 401: неверный API-ключ или способ авторизации."
                    403 -> "HTTP 403: нет доступа к модели. Проверь роль/каталог/биллинг."
                    404 -> "HTTP 404: модель или Folder ID недоступны."
                    null -> "Сбой сети/WebSocket: " + t.javaClass.simpleName
                    else -> "Сервер вернул HTTP " + response.code
                }
                runOnUiThread { if (socket === ws) stopSession(result) }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    if (socket === ws) stopSession("Соединение закрыто (код $code).")
                }
            }
        })
    }

    private fun startAudio() {
        startOutput()
        capturing = true
        inputThread = Thread({
            val min = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) {
                setState("Микрофон не поддерживает PCM 44.1 кГц.")
                return@Thread
            }
            var microphone: AudioRecord? = null
            try {
                microphone = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 22050))
                recorder = microphone
                if (microphone.state != AudioRecord.STATE_INITIALIZED) {
                    setState("Не удалось запустить микрофон.")
                    return@Thread
                }
                microphone.startRecording()
                val buffer = ByteArray(8820) // PCM16 mono 44.1kHz, ~100ms.
                while (capturing) {
                    val n = microphone.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        val bytes = if (n == buffer.size) buffer else buffer.copyOf(n)
                        val payload = JSONObject()
                            .put("type", "input_audio_buffer.append")
                            .put("audio", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        if (socket?.send(payload.toString()) != true) break
                    }
                }
            } catch (e: Exception) {
                if (capturing) setState("Ошибка микрофона: " + e.javaClass.simpleName)
            } finally {
                try { microphone?.stop() } catch (_: Exception) {}
                microphone?.release()
                if (recorder === microphone) recorder = null
            }
        }, "YandexMic").apply { start() }
    }

    private fun startOutput() {
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) {
            setState("Динамик не поддерживает PCM 44.1 кГц.")
            return
        }
        val speaker = AudioTrack(
            AudioManager.STREAM_VOICE_CALL, sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 17640),
            AudioTrack.MODE_STREAM)
        track = speaker
        outputActive = true
        outputThread = Thread({
            try {
                speaker.play()
                while (outputActive) {
                    val chunk = outputQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    if (outputActive) speaker.write(chunk, 0, chunk.size)
                }
            } catch (_: Exception) {
                // Stream shutdown, or audio route changing.
            } finally {
                try { speaker.stop() } catch (_: Exception) {}
                speaker.release()
                if (track === speaker) track = null
            }
        }, "YandexSpeaker").apply { start() }
    }

    private fun interruptPlayback() {
        outputQueue.clear()
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (_: Exception) {}
    }

    private fun stopSession(message: String) {
        capturing = false
        ready = false
        liveResponse = false
        try { recorder?.stop() } catch (_: Exception) {}
        outputActive = false
        outputQueue.clear()
        outputThread?.interrupt()
        try { track?.pause() } catch (_: Exception) {}
        val ws = socket
        socket = null
        try { ws?.close(1000, "Stopped") } catch (_: Exception) {}
        setState(message)
    }

    private fun setState(message: String) {
        runOnUiThread { state.text = message }
    }

    private fun note(message: String) {
        runOnUiThread {
            log.text = (log.text.toString() + "\n" + message).takeLast(4500)
        }
    }

    override fun onDestroy() {
        stopSession("Остановлено.")
        apiKeyField.text.clear()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
