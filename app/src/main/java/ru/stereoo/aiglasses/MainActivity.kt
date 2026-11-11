package ru.stereoo.aiglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import io.livekit.android.events.collect
import io.livekit.android.token.TokenSource
import io.livekit.android.token.ConfigurableTokenSource
import io.livekit.android.token.TokenRequestOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Stereoo • LiveKit test for Motorola moto g15.
 * Standalone prototype: audio WebRTC + LiveKit Cloud agent dispatch.
 * Agent: assistant-1c7e, deployed in Frankfurt / Google Gemini Live.
 *
 * No API key or secret is embedded in the application.
 * Development token server IDs must never be used in a production release.
 * No camera/BV200 button handling, lockscreen service or long-term memory yet.
 */
class MainActivity : ComponentActivity() {
    private lateinit var tokenField: EditText
    private lateinit var agentField: EditText
    private lateinit var status: TextView
    private lateinit var events: TextView
    private var room: Room? = null
    private var connecting: Job? = null
    private var eventsJob: Job? = null
    private var isConnected = false
    private var micEnabled = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) startCall() else setStatus("Без разрешения микрофона разговор невозможен.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 26, 34, 26)
        }
        fun label(content: String, size: Float = 16f): TextView =
            TextView(this).apply {
                text = content
                textSize = size
                setPadding(0, 8, 0, 10)
            }
        container.addView(label("Stereoo • LiveKit / Gemini Live", 23f))
        container.addView(label(
            "Тест настоящего Realtime через WebRTC.\n" +
            "Для подключения нужен только Token server ID из LiveKit Cloud. " +
            "API Key и API Secret не вводить!"
        ))
        container.addView(label("Token server ID (Settings → Project Settings):"))
        tokenField = EditText(this).apply {
            hint = "Скопируй Token server ID"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        container.addView(tokenField)
        container.addView(label("Имя развёрнутого агента:"))
        agentField = EditText(this).apply {
            setText("assistant-1c7e")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(agentField)
        container.addView(Button(this).apply {
            text = "🎙 Начать разговор"
            setOnClickListener { checkMicrophoneAndStart() }
        })
        container.addView(Button(this).apply {
            text = "🔇 Вкл. / выкл. микрофон"
            setOnClickListener { toggleMicrophone() }
        })
        container.addView(Button(this).apply {
            text = "⏹ Завершить"
            setOnClickListener { endCall() }
        })
        status = label("Готово. Введи Token server ID и нажми «Начать разговор».")
        container.addView(status)
        events = label("", 13f).apply { setTextIsSelectable(true) }
        container.addView(events)
        container.addView(label(
            "⚠ Только для тестов. Development token server позволяет получать " +
            "неограниченные по разрешениям тестовые токены. После тестирования " +
            "выключи его в LiveKit Cloud. Для рабочей версии нужен сервер " +
            "с авторизацией пользователей и ограничением доступа."
        , 13f))
        setContentView(ScrollView(this).apply { addView(container) })
    }

    private fun checkMicrophoneAndStart() {
        if (connecting != null || isConnected) {
            setStatus("Сессия уже запущена.")
            return
        }
        if (tokenField.text.toString().trim().isEmpty()) {
            setStatus("Введи Token server ID, не Agent ID и не API Secret.")
            return
        }
        if (agentField.text.toString().trim().isEmpty()) {
            setStatus("Нужно имя агента из LiveKit Agent Overview.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else startCall()
    }

    private fun startCall() {
        if (connecting != null || isConnected) return
        val tokenServerId = tokenField.text.toString().trim()
        val agentName = agentField.text.toString().trim()
        val sessionRoom = "stereoo-" + UUID.randomUUID().toString().substring(0, 12)
        events.text = ""
        setStatus("Запрашиваю временный токен…")
        connecting = lifecycleScope.launch {
            try {
                val source = TokenSource.fromDevelopmentTokenServer(tokenServerId)
                    as ConfigurableTokenSource
                val response = source.fetch(
                    TokenRequestOptions(
                        roomName = sessionRoom,
                        agentName = agentName,
                        participantName = "Stereoo test"
                    )
                ).getOrThrow()
                setStatus("Токен получен. Подключаю WebRTC…")
                val newRoom = LiveKit.create(applicationContext)
                room = newRoom
                eventsJob = lifecycleScope.launch {
                    newRoom.events.collect {
                        showEvent(it.javaClass.simpleName)
                    }
                }
                newRoom.connect(response.serverUrl, response.participantToken)
                newRoom.localParticipant.setMicrophoneEnabled(true)
                micEnabled = true
                isConnected = true
                setStatus("🟢 Подключено. Разговаривай с агентом. Если он не ответил, проверь Agent Overview.")
                showEvent("Комната: $sessionRoom")
                showEvent("Агент: $agentName")
            } catch (_: CancellationException) {
                // user stopped session
            } catch (e: Exception) {
                setStatus("Ошибка подключения: ${e.message?.take(200) ?: e.javaClass.simpleName}")
                disconnectRoom()
            } finally {
                connecting = null
            }
        }
    }

    private fun toggleMicrophone() {
        val r = room
        if (r == null || !isConnected) {
            setStatus("Сначала подключись к агенту.")
            return
        }
        lifecycleScope.launch {
            try {
                val newValue = !micEnabled
                r.localParticipant.setMicrophoneEnabled(newValue)
                micEnabled = newValue
                setStatus(if (micEnabled) "🎙 Микрофон включён." else "🔇 Микрофон выключен.")
            } catch (e: Exception) {
                setStatus("Ошибка микрофона: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun endCall() {
        connecting?.cancel()
        connecting = null
        disconnectRoom()
        setStatus("Разговор завершён.")
    }

    private fun disconnectRoom() {
        eventsJob?.cancel()
        eventsJob = null
        val current = room
        room = null
        isConnected = false
        micEnabled = true
        lifecycleScope.launch {
            try { current?.disconnect() } catch (_: Exception) {}
            try { current?.release() } catch (_: Exception) {}
        }
    }

    private fun setStatus(value: String) {
        runOnUiThread { status.text = value }
    }

    private fun showEvent(message: String) {
        runOnUiThread {
            events.text = (events.text.toString() + "\n" + message).takeLast(2500)
        }
    }

    override fun onDestroy() {
        endCall()
        tokenField.text.clear()
        super.onDestroy()
    }
}
