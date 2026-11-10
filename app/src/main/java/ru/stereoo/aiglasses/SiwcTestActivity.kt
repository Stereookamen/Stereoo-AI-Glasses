package ru.stereoo.aiglasses

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread
import java.util.Locale

/** Independent test screen: it does not change BV200 Bluetooth button handling. */
class SiwcTestActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var siwc: SiwcClient
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var input: EditText
    private lateinit var models: Spinner
    private lateinit var speech: TextToSpeech
    private var selectedImage: Uri? = null
    private val modelChoices = mutableListOf<Pair<String, String>>()
    private val history = mutableListOf<SiwcClient.Turn>()

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedImage = uri
        if (uri != null) status.text = "Фото выбрано. Напиши вопрос или нажми «Отправить»."
    }
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        if (response.resultCode == Activity.RESULT_OK) {
            val recognized = response.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!recognized.isNullOrBlank()) {
                input.setText(recognized)
                send()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        siwc = SiwcClient(this)
        speech = TextToSpeech(this, this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 22, 30, 22)
        }
        layout.addView(TextView(this).apply { text = "Stereoo AI • Sign in with ChatGPT (ТЕСТ)"; textSize = 23f })
        layout.addView(TextView(this).apply {
            text = "Отдельный тест входа через подписку ChatGPT. Без API-ключа и без нашего сервера. " +
                "Это не рабочая версия для очков BV200."
        })
        val login = Button(this).apply {
            text = "Continue with ChatGPT"
            setOnClickListener {
                siwc.signIn(::showStatus) { loadModels() }
            }
        }
        layout.addView(login)
        layout.addView(Button(this).apply {
            text = "Выйти из тестовой сессии"
            setOnClickListener {
                siwc.signOut()
                modelChoices.clear()
                history.clear()
                status.text = "Сессия завершена в приложении. Можно войти снова."
            }
        })
        status = TextView(this).apply { text = "Для начала нажми Continue with ChatGPT."; textSize = 16f }
        layout.addView(status)
        layout.addView(TextView(this).apply { text = "Модель, доступная аккаунту:" })
        models = Spinner(this)
        layout.addView(models)
        input = EditText(this).apply { hint = "Спроси что-нибудь..."; minLines = 2 }
        layout.addView(input)
        layout.addView(Button(this).apply {
            text = "📷 Выбрать фото"
            setOnClickListener { imagePicker.launch("image/*") }
        })
        layout.addView(Button(this).apply {
            text = "🎙 Спросить голосом"
            setOnClickListener {
                try {
                    voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    })
                } catch (_: Exception) {
                    status.text = "Распознавание речи на устройстве недоступно."
                }
            }
        })
        layout.addView(Button(this).apply { text = "Отправить вопрос"; setOnClickListener { send() } })
        layout.addView(Button(this).apply {
            text = "Новый диалог"
            setOnClickListener {
                history.clear()
                selectedImage = null
                result.text = ""
                status.text = "История тестового диалога очищена."
            }
        })
        result = TextView(this).apply { textSize = 18f; text = "Ответ появится здесь." }
        layout.addView(result)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun loadModels() {
        showStatus("Запрашиваем доступные модели...")
        thread {
            try {
                val fetched = siwc.models()
                runOnUiThread {
                    modelChoices.clear()
                    modelChoices.addAll(fetched)
                    if (modelChoices.isEmpty()) {
                        status.text = "Модели не получены. Возможно, план не поддерживает эту интеграцию."
                    } else {
                        models.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                            modelChoices.map { it.second + " (" + it.first + ")" })
                        status.text = "✅ Моделей: " + modelChoices.size + ". Можно отправить вопрос."
                    }
                }
            } catch (e: Exception) {
                showStatus("Модели недоступны: " + (e.message ?: "ошибка"))
            }
        }
    }

    private fun send() {
        val q = input.text.toString().trim()
        if (q.isBlank() && selectedImage == null) {
            status.text = "Введите текст или выберите фото."
            return
        }
        if (!siwc.signedIn) { status.text = "Сначала войди через ChatGPT."; return }
        val chosen = modelChoices.getOrNull(models.selectedItemPosition)
        if (chosen == null) { status.text = "Список моделей ещё не загружен."; return }
        val image = selectedImage
        val snapshot = history.toList()
        selectedImage = null
        status.text = "Отправляем напрямую в ChatGPT..."
        thread {
            try {
                val (reply, turn) = siwc.ask(chosen.first, q, image, snapshot)
                runOnUiThread {
                    history.add(turn)
                    result.text = reply.ifBlank { "Модель вернула пустой текст." }
                    status.text = "✅ Ответ получен. Контекст разговора сохранён внутри приложения."
                    speech.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "siwc-answer")
                }
            } catch (e: Exception) {
                showStatus("Ошибка запроса: " + (e.message ?: "неизвестно"))
            }
        }
    }

    private fun showStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) speech.language = Locale("ru", "RU")
    }

    override fun onDestroy() {
        siwc.close()
        speech.stop()
        speech.shutdown()
        super.onDestroy()
    }
}
