package ru.stereoo.aiglasses

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Completely independent wire-level probe for Qwen3.8-Omni-Flash-Realtime.
 * Uses Java-WebSocket instead of the OkHttp stack in Stereoo Qwen Live.
 *
 * TEST ONLY. Never embeds, persists or logs the API key. No microphone permission,
 * no audio and no images. It measures only session.created / session.updated.
 */
class MainActivity : ComponentActivity() {
    private enum class Mode { SOCKET_ONLY, SESSION_UPDATE }

    private val ui = Handler(Looper.getMainLooper())
    private val serial = AtomicInteger(0)
    private var client: WebSocketClient? = null
    private var activeId = 0
    private var mode = Mode.SOCKET_ONLY
    private lateinit var workspaceInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var logText: TextView
    private lateinit var stateText: TextView
    private var createdAt = 0L
    private var updateSentAt = 0L
    private var stage = "не начато"
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 32, 30, 24)
        }
        fun label(text: String, size: Float = 17f): TextView = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, 0, 0, 12)
        }
        root.addView(label("Stereoo | Независимая проверка Qwen", 22f))
        root.addView(label(
            "ДРУГОЙ WebSocket-клиент. Не использует OkHttp. Только проверяет подключение " +
                "и session.update, без микрофона, фото и BV200.", 14f))
        workspaceInput = EditText(this).apply {
            hint = "Workspace ID (Singapore)"
            isSingleLine = true
        }
        root.addView(workspaceInput)
        secretInput = EditText(this).apply {
            hint = "API key Singapore (скрыт)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        root.addView(secretInput)
        root.addView(label(
            "ВНИМАНИЕ: квота для Qwen3.8 может отсутствовать. " +
                "Тесты могут быть платными. Ключ никуда не сохраняется.", 14f))
        root.addView(Button(this).apply {
            text = "1. Проверить WebSocket без настроек"
            setOnClickListener { confirmAndRun(Mode.SOCKET_ONLY) }
        })
        root.addView(Button(this).apply {
            text = "2. Отправить session.update без микрофона"
            setOnClickListener { confirmAndRun(Mode.SESSION_UPDATE) }
        })
        root.addView(Button(this).apply {
            text = "Остановить тест"
            setOnClickListener { stop("Остановлено пользователем") }
        })
        stateText = label("Готов к проверке.", 16f)
        root.addView(stateText)
        logText = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            text = "Журнал событий появится здесь. Секретный ключ не выводится."
        }
        root.addView(logText)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun confirmAndRun(requested: Mode) {
        val ws = workspaceInput.text.toString().trim().lowercase()
        val key = secretInput.text.toString().trim()
        if (!Regex("ws-[a-z0-9-]{8,90}").matches(ws) || key.isBlank()) {
            stateText.text = "Проверь Workspace ID (начинается с ws-) и API-ключ Singapore."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Проверка Qwen API")
            .setMessage("У этой модели может не быть бесплатной квоты. " +
                "Подключение может учитываться в платном использовании. Продолжить?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Проверить") { _, _ -> start(requested, ws, key) }
            .show()
    }

    private fun start(selected: Mode, workspace: String, key: String) {
        stop(null)
        activeId = serial.incrementAndGet()
        val ownId = activeId
        mode = selected
        done = false
        stage = "начало подключения"
        createdAt = 0L
        updateSentAt = 0L
        logText.text = ""
        record("Режим: " + mode.name)
        record("Клиент: Java-WebSocket 1.6.0 (не OkHttp)")
        record("Регион: Singapore; модель: qwen3.8-omni-flash-realtime")
        record("API-ключ и Workspace ID не выводятся.")
        stateText.text = "Подключаюсь…"

        val uri = URI("wss://$workspace.ap-southeast-1.maas.aliyuncs.com/" +
            "api-ws/v1/realtime?model=qwen3.8-omni-flash-realtime")
        val headers = mapOf("Authorization" to "Bearer $key")
        // Official minimal sequence: open -> session.created -> session.update -> session.updated.
        val ws = object : WebSocketClient(uri, Draft_6455(), headers, 10000) {
            override fun onOpen(handshake: ServerHandshake) {
                post(ownId) {
                    stage = "WebSocket открыт"
                    record("✓ WebSocket открыт (HTTP ${handshake.httpStatus})")
                    stateText.text = "Открыто. Ждём session.created…"
                }
            }

            override fun onMessage(message: String) {
                try {
                    val json = JSONObject(message)
                    val eventType = json.optString("type", "(без type)")
                    post(ownId) {
                        if (done) return@post
                        stage = eventType.take(72)
                        when (eventType) {
                            "session.created" -> {
                                createdAt = android.os.SystemClock.elapsedRealtime()
                                val model = json.optJSONObject("session")?.optString("model")
                                record("✓ session.created; model=" + (model ?: "?").take(64))
                                if (selected == Mode.SOCKET_ONLY) {
                                    stateText.text = "Сессия создана. Проверяю 8 секунд БЕЗ настроек…"
                                    scheduleTimeout(ownId, 8000, "8 секунд без session.update")
                                } else {
                                    val payload = JSONObject().put("type", "session.update")
                                        .put("session", JSONObject()
                                            .put("modalities", JSONArray().put("text").put("audio"))
                                            .put("voice", "Tina")
                                            .put("turn_detection",
                                                JSONObject().put("type", "server_vad")))
                                    try {
                                        updateSentAt = android.os.SystemClock.elapsedRealtime()
                                        send(payload.toString())
                                        stage = "отправили session.update"
                                        record("→ Отправлено минимальное session.update")
                                        stateText.text = "Ожидаю session.updated…"
                                        scheduleTimeout(ownId, 8000, "8 секунд без session.updated")
                                    } catch (e: Exception) {
                                        finish(ownId, "Ошибка отправки session.update: " +
                                            e.javaClass.simpleName)
                                    }
                                }
                            }
                            "session.updated" -> {
                                val ms = if (updateSentAt > 0L)
                                    android.os.SystemClock.elapsedRealtime() - updateSentAt else -1L
                                record("✓ session.updated, задержка ${ms} мс")
                                finish(ownId, "✅ УСПЕШНО: сервер принял session.update.")
                            }
                            "error" -> {
                                val err = json.optJSONObject("error")
                                // Log code and param only; model error text could contain sensitive data.
                                val code = err?.optString("code", "?").orEmpty()
                                val param = err?.optString("param", "?").orEmpty()
                                record("✗ Сервер прислал error: code=" + code.take(90) +
                                    "; param=" + param.take(80))
                                finish(ownId, "Ошибка API: " + code.take(100))
                            }
                            else -> record("Событие сервера: " + eventType.take(80))
                        }
                    }
                } catch (e: Exception) {
                    post(ownId) { record("Ошибка JSON: " + e.javaClass.simpleName) }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                post(ownId) {
                    if (done) return@post
                    val elapsed = if (createdAt > 0L) {
                        (android.os.SystemClock.elapsedRealtime() - createdAt).toString() + " мс"
                    } else "не создавалась"
                    record("✗ WebSocket закрылся: код=$code, " +
                        "со стороны сервера=$remote, с создания сессии прошло $elapsed")
                    // Do not print raw reason: it may contain details from a proxy or credentials.
                    finish(ownId, "Закрыто: $code; этап: $stage")
                }
            }

            override fun onError(ex: Exception) {
                post(ownId) {
                    if (done) return@post
                    record("✗ Ошибка транспорта: " + ex.javaClass.simpleName)
                    // No exception message: may contain Authorization header or URL.
                    stateText.text = "Сбой транспорта: " + ex.javaClass.simpleName
                }
            }
        }
        client = ws
        ws.connect()
        scheduleTimeout(ownId, 18000, "сессия не завершилась за 18 секунд")
    }

    private fun scheduleTimeout(id: Int, delayMs: Long, label: String) {
        ui.postDelayed({
            if (activeId != id || done) return@postDelayed
            record("⏱ Таймаут: $label")
            if (label == "8 секунд без session.update" && stage == "session.created") {
                finish(id, "✅ WebSocket держался 8 секунд без session.update.")
            } else if (label == "8 секунд без session.updated" &&
                stage == "отправили session.update") {
                finish(id, "Нет ответа session.updated за 8 секунд.")
            } else if (label == "сессия не завершилась за 18 секунд") {
                finish(id, "Достигнут предел времени теста.")
            }
        }, delayMs)
    }

    private fun post(id: Int, task: () -> Unit) {
        ui.post { if (activeId == id) task() }
    }

    private fun record(msg: String) {
        // No secret values, URL, authorization header, full server JSON, or exception messages.
        val prefix = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
            .format(java.util.Date())
        val old = logText.text.toString()
        logText.text = (old + "\n[$prefix] " + msg).trim().takeLast(6000)
    }

    private fun finish(id: Int, result: String) {
        if (activeId != id || done) return
        done = true
        stateText.text = result
        record("РЕЗУЛЬТАТ: $result")
        try { client?.close() } catch (_: Exception) {}
        client = null
    }

    private fun stop(message: String?) {
        done = true
        activeId = serial.incrementAndGet()
        try { client?.close() } catch (_: Exception) {}
        client = null
        if (message != null) {
            stateText.text = message
            if (::logText.isInitialized) record(message)
        }
    }

    override fun onDestroy() {
        stop(null)
        secretInput.text.clear()
        super.onDestroy()
    }
}
