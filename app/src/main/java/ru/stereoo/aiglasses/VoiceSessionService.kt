package ru.stereoo.aiglasses

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.token.ConfigurableTokenSource
import io.livekit.android.token.TokenRequestOptions
import io.livekit.android.token.TokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class StereooSessionState(
    val status: String = "Не подключено.",
    val active: Boolean = false,
    val connected: Boolean = false,
    val microphone: Boolean = true,
    val audioRoute: String = "Неизвестно",
    val transcript: String = ""
)

/** In-process state: no conversations or credentials persisted on storage. */
object StereooSession {
    val state = MutableStateFlow(StereooSessionState())
}

/**
 * A microphone foreground service is required for live voice on Android 14/15
 * when the phone screen locks. No service is started from BOOT_COMPLETED.
 *
 * The real LiveKit agent runs on the remote cloud project, NOT inside this APK.
 */
@OptIn(io.livekit.android.annotations.Beta::class)
class VoiceSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var room: Room? = null
    private var connectJob: Job? = null
    private var eventsJob: Job? = null
    private var durationJob: Job? = null
    private var closing = false
    private var microphoneOn = true
    private val channelId = "stereoo_livekit_voice"

    companion object {
        const val ACTION_START = "ru.stereoo.livekitpilot.START"
        const val ACTION_STOP = "ru.stereoo.livekitpilot.STOP"
        const val ACTION_MIC = "ru.stereoo.livekitpilot.MIC"
        const val ACTION_BT = "ru.stereoo.livekitpilot.BLUETOOTH"
        const val ACTION_AUTO = "ru.stereoo.livekitpilot.AUTO"
        const val EXTRA_TOKEN_SERVER_ID = "token_server_id"
        const val EXTRA_AGENT_NAME = "agent_name"
        const val NOTIFICATION_ID = 3103
        // Cost-control guard for first pilot.
        private const val MAX_TEST_DURATION_MS = 10 * 60 * 1000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (StereooSession.state.value.active) return START_NOT_STICKY
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    update("Нет разрешения на микрофон.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val tokenServerId = intent.getStringExtra(EXTRA_TOKEN_SERVER_ID)?.trim().orEmpty()
                val agentName = intent.getStringExtra(EXTRA_AGENT_NAME)?.trim().orEmpty()
                if (tokenServerId.isBlank() || agentName.isBlank()) {
                    update("Нужны Token Server ID и имя агента.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    // Must be called while the user has the app foregrounded:
                    // Android 14+ denies starting a microphone FGS from background.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification("Соединение с LiveKit…"),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    } else {
                        startForeground(NOTIFICATION_ID, notification("Соединение с LiveKit…"))
                    }
                } catch (e: Exception) {
                    update("Ошибка фонового режима: ${e.message ?: e.javaClass.simpleName}")
                    stopSelf()
                    return START_NOT_STICKY
                }
                StereooSession.state.value = StereooSessionState(
                    status = "Соединение с LiveKit…",
                    active = true,
                    microphone = true)
                connectJob = scope.launch { establish(tokenServerId, agentName) }
            }
            ACTION_STOP -> stopSession("Разговор завершён.")
            ACTION_MIC -> toggleMicrophone()
            ACTION_BT -> routeToBluetooth()
            ACTION_AUTO -> resetRoute()
        }
        return START_NOT_STICKY
    }

    private suspend fun establish(tokenId: String, agentName: String) {
        try {
            val roomName = "stereoo-" + UUID.randomUUID().toString().take(12)
            val tokenSource = TokenSource.fromDevelopmentTokenServer(tokenId)
                as ConfigurableTokenSource
            update("Получаю временный токен…")
            val token = tokenSource.fetch(
                TokenRequestOptions(
                    roomName = roomName,
                    agentName = agentName,
                    participantName = "Stereoo BV200 test"
                )
            ).getOrThrow()
            update("Токен получен. Подключаю звук…")
            val newRoom = LiveKit.create(applicationContext)
            room = newRoom
            eventsJob = scope.launch {
                newRoom.events.collect { event ->
                    when (event) {
                        is RoomEvent.TranscriptionReceived -> {
                            val finalText = event.transcriptionSegments
                                .filter { it.final }
                                .joinToString(" ") { it.text }
                                .trim()
                            if (finalText.isNotBlank()) {
                                val who = if (event.participant == newRoom.localParticipant) {
                                    "Ты"
                                } else "Stereoo"
                                val current = StereooSession.state.value
                                StereooSession.state.value = current.copy(
                                    transcript = (current.transcript +
                                        "\n" + who + ": " + finalText).takeLast(1800))
                            }
                        }
                        is RoomEvent.Disconnected -> {
                            if (!closing) stopSession("Соединение с LiveKit разорвано.")
                        }
                        is RoomEvent.ParticipantConnected -> update("Агент присоединился. Говори!")
                        else -> { /* High-frequency quality/speaker events ignored. */ }
                    }
                }
            }
            newRoom.connect(token.serverUrl, token.participantToken)
            newRoom.localParticipant.setMicrophoneEnabled(true)
            microphoneOn = true
            val route = describeAudioRoute()
            StereooSession.state.value = StereooSession.state.value.copy(
                active = true, connected = true, microphone = true,
                status = "🟢 Realtime работает. Экран можно погасить.",
                audioRoute = route)
            refreshNotification()
            // Auto-stop the first pilot after 10 minutes to limit cloud usage.
            durationJob = scope.launch {
                delay(MAX_TEST_DURATION_MS)
                stopSession("Тест завершён автоматически через 10 минут.")
            }
        } catch (_: CancellationException) {
            // The user stopped the service during the connection.
        } catch (e: Exception) {
            update("Ошибка LiveKit: " + (e.message ?: e.javaClass.simpleName).take(220))
            stopSession("Подключение не удалось: " +
                (e.message ?: e.javaClass.simpleName).take(150))
        } finally {
            connectJob = null
        }
    }

    private fun toggleMicrophone() {
        val currentRoom = room ?: return
        if (!StereooSession.state.value.connected) return
        scope.launch {
            try {
                microphoneOn = !microphoneOn
                currentRoom.localParticipant.setMicrophoneEnabled(microphoneOn)
                val state = StereooSession.state.value
                StereooSession.state.value = state.copy(
                    microphone = microphoneOn,
                    status = if (microphoneOn) "🎙 Микрофон включён."
                        else "🔇 Микрофон отключён.")
                refreshNotification()
            } catch (e: Exception) {
                update("Ошибка микрофона: " + e.javaClass.simpleName)
            }
        }
    }

    private fun routeToBluetooth() {
        val handler = room?.audioSwitchHandler
        if (handler == null) {
            update("Маршрутизация LiveKit ещё не готова.")
            return
        }
        try {
            // Let LiveKit's AudioSwitch own the audio mode, input and output route.
            // Do not use deprecated startBluetoothSco() or setBluetoothScoOn().
            val bluetooth = handler.availableAudioDevices.firstOrNull {
                it.javaClass.simpleName.contains("Bluetooth", ignoreCase = true)
            }
            if (bluetooth != null) {
                handler.selectDevice(bluetooth)
                StereooSession.state.value = StereooSession.state.value.copy(
                    audioRoute = describeAudioRoute(),
                    status = "Маршрут Bluetooth выбран. Проверь звук и микрофон BV200.")
            } else {
                update("Bluetooth-гарнитура для звонков не найдена. " +
                    "В настройках BV200 включи профиль «Звонки».")
                StereooSession.state.value = StereooSession.state.value.copy(
                    audioRoute = "Bluetooth не найден")
            }
        } catch (e: SecurityException) {
            update("Для Bluetooth требуется разрешение «Устройства поблизости».")
        } catch (e: Exception) {
            update("Ошибка маршрута: " + e.javaClass.simpleName)
        }
    }

    private fun resetRoute() {
        try {
            room?.audioSwitchHandler?.selectDevice(null)
            StereooSession.state.value = StereooSession.state.value.copy(
                audioRoute = describeAudioRoute(), status = "Автоматический выбор звука.")
        } catch (e: Exception) {
            update("Ошибка маршрута: " + e.javaClass.simpleName)
        }
    }

    private fun describeAudioRoute(): String =
        try {
            room?.audioSwitchHandler?.selectedAudioDevice
                ?.javaClass?.simpleName ?: "Определяется системой"
        } catch (_: Exception) { "Неизвестно" }

    private fun stopSession(reason: String) {
        if (closing) return
        closing = true
        durationJob?.cancel()
        connectJob?.cancel()
        eventsJob?.cancel()
        scope.launch {
            val previousRoom = room
            room = null
            try { previousRoom?.disconnect() } catch (_: Exception) {}
            try { previousRoom?.release() } catch (_: Exception) {}
            val oldTranscript = StereooSession.state.value.transcript
            StereooSession.state.value = StereooSessionState(
                status = reason, transcript = oldTranscript)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun update(value: String) {
        StereooSession.state.value =
            StereooSession.state.value.copy(status = value)
        refreshNotification()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Stereoo голосовой разговор",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Активное голосовое соединение с LiveKit"
                setShowBadge(false)
            })
    }

    private fun notification(content: String): Notification {
        val open = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Stereoo AI • голосовая связь")
            .setContentText(content)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "Завершить", stop).build())
            .build()
    }

    private fun refreshNotification() {
        val state = StereooSession.state.value
        if (state.active) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(state.status))
        }
    }

    override fun onDestroy() {
        durationJob?.cancel()
        connectJob?.cancel()
        eventsJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
