package ru.stereoo.aiglasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Visible control panel for the microphone Foreground Service.
 * Audio and WebRTC are owned by VoiceSessionService and continue after lock.
 */
class MainActivity : ComponentActivity() {
    private lateinit var tokenIdField: EditText
    private lateinit var agentField: EditText
    private lateinit var statusView: TextView
    private lateinit var routeView: TextView
    private lateinit var transcriptView: TextView

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            launchVoiceService()
        } else {
            statusView.text = "Разреши микрофон в настройках приложения."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }
        fun label(value: String, font: Float = 16f): TextView =
            TextView(this).apply {
                text = value
                textSize = font
                setPadding(0, 6, 0, 10)
            }
        fun button(value: String, action: () -> Unit): Button =
            Button(this).apply { text = value; setOnClickListener { action() } }

        root.addView(label("Stereoo • LiveKit v2 👓", 24f))
        root.addView(label("Gemini Live через LiveKit. " +
            "Фоновый разговор при выключенном экране и Bluetooth-маршрут для BV200."))
        root.addView(label("Development Token Server ID"))
        tokenIdField = EditText(this).apply {
            hint = "ID тестового сервера токенов"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        root.addView(tokenIdField)
        root.addView(label("Agent Name"))
        agentField = EditText(this).apply {
            setText("assistant-1c7e")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(agentField)
        root.addView(button("🎙 Начать разговор") { checkAndStart() })
        root.addView(button("🎧 Вывести голос в BV200 / Bluetooth") {
            serviceAction(VoiceSessionService.ACTION_BT)
        })
        root.addView(button("🔊 Автоматический выбор звука") {
            serviceAction(VoiceSessionService.ACTION_AUTO)
        })
        root.addView(button("🔇 Включить / выключить микрофон") {
            serviceAction(VoiceSessionService.ACTION_MIC)
        })
        root.addView(button("⏹ Завершить разговор") {
            serviceAction(VoiceSessionService.ACTION_STOP)
        })
        statusView = label("Готово.", 17f)
        root.addView(statusView)
        routeView = label("Звук: не подключено", 14f)
        root.addView(routeView)
        root.addView(label("Последние фразы (только память приложения)", 13f))
        transcriptView = label("", 15f).apply { setTextIsSelectable(true) }
        root.addView(transcriptView)
        root.addView(label(
            "Тестовое ограничение: разговор выключится через 10 минут. " +
            "В процессе должна быть постоянная системная нотификация.\n" +
            "Начни разговор при открытом приложении, затем заблокируй экран. " +
            "Смартфон может попросить разрешить работу в фоне.\n\n" +
            "⚠ Ключи API/Secret НЕ вводить. Development Token Server небезопасен " +
            "для открытого доступа, после тестов отключи его в LiveKit.\n" +
            "Фото, кнопки BV200 и долговременная память будут добавлены отдельно.", 13f
        ))
        setContentView(ScrollView(this).apply { addView(root) })

        lifecycleScope.launch {
            StereooSession.state.collect { s ->
                statusView.text = s.status
                routeView.text = "Маршрут: ${s.audioRoute} • " +
                    if (s.microphone) "🎙 Микрофон включён" else "🔇 Выключен"
                transcriptView.text = s.transcript
            }
        }
    }

    private fun checkAndStart() {
        if (StereooSession.state.value.active) {
            statusView.text = "Сессия уже запущена."
            return
        }
        if (tokenIdField.text.toString().trim().isBlank()) {
            statusView.text = "Введи Development Token Server ID, не Agent ID."
            return
        }
        if (agentField.text.toString().trim().isBlank()) {
            statusView.text = "Введи Agent Name."
            return
        }
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        // POST_NOTIFICATIONS is not mandatory for running a microphone FGS.
        // The OS task manager still shows ongoing services if notifications are denied.
        if (missing.isNotEmpty()) {
            permissions.launch(missing.toTypedArray())
        } else launchVoiceService()
    }

    private fun launchVoiceService() {
        // Starting microphone FGS from an unlocked, visible Activity is required.
        val start = Intent(this, VoiceSessionService::class.java)
            .setAction(VoiceSessionService.ACTION_START)
            .putExtra(VoiceSessionService.EXTRA_TOKEN_SERVER_ID,
                tokenIdField.text.toString().trim())
            .putExtra(VoiceSessionService.EXTRA_AGENT_NAME,
                agentField.text.toString().trim())
        try {
            ContextCompat.startForegroundService(this, start)
            statusView.text = "Запускаю фоновый голосовой режим…"
        } catch (e: Exception) {
            statusView.text = "Android запретил запуск: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun serviceAction(action: String) {
        if (!StereooSession.state.value.active) {
            statusView.text = "Сначала начни разговор."
            return
        }
        // Service already runs in foreground; startService is enough for control.
        try {
            startService(Intent(this, VoiceSessionService::class.java).setAction(action))
        } catch (e: Exception) {
            statusView.text = "Не удалось выполнить команду: ${e.javaClass.simpleName}"
        }
    }

    override fun onDestroy() {
        // Service continues to run; the user ends it using UI or notification.
        tokenIdField.text.clear()
        super.onDestroy()
    }
}
