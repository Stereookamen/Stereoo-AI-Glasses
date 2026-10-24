package ru.stereoo.aiglasses

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.RecognizerIntent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.*
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray
import android.util.Base64
import kotlin.concurrent.thread

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var question: EditText
    private lateinit var answer: TextView
    private lateinit var speech: TextToSpeech
    private var imageUri: Uri? = null
    private var apiKey = ""

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            imageUri = uri
            if (uri != null) answer.text = "Фотография выбрана."
        }

    private val voiceInput =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val text = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    question.setText(text)
                    sendQuestion()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        speech = TextToSpeech(this, this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
        }

        layout.addView(TextView(this).apply {
            text = "Stereoo AI Glasses"
            textSize = 26f
        })

        question = EditText(this).apply {
            hint = "Напиши вопрос..."
            minLines = 2
        }
        layout.addView(question)

        val askButton = Button(this).apply {
            text = "Отправить вопрос"
            setOnClickListener { sendQuestion() }
        }
        layout.addView(askButton)

        val voiceButton = Button(this).apply {
            text = "🎙 Говорить"
            setOnClickListener { startVoiceInput() }
        }
        layout.addView(voiceButton)

        val photoButton = Button(this).apply {
            text = "📷 Выбрать фотографию"
            setOnClickListener { imagePicker.launch("image/*") }
        }
        layout.addView(photoButton)

        answer = TextView(this).apply {
            text = "Готов к работе. Для запросов нужен API-ключ OpenAI."
            textSize = 17f
        }
        layout.addView(answer)

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            speech.language = Locale("ru", "RU")
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говори")
        }

        try {
            voiceInput.launch(intent)
        } catch (e: Exception) {
            answer.text = "Голосовой ввод недоступен на устройстве."
        }
    }

    private fun sendQuestion() {
        val text = question.text.toString().trim()
        if (text.isEmpty() && imageUri == null) {
            answer.text = "Сначала введи вопрос или выбери фотографию."
            return
        }

        if (apiKey.isBlank()) {
            answer.text = "Пока не подключён API-ключ OpenAI. Добавим безопасную настройку позже."
            return
        }

        answer.text = "Отправляю запрос..."

        thread {
            try {
                val connection = URL("https://api.openai.com/v1/responses")
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 20000
                connection.readTimeout = 60000
                connection.doOutput = true

                val input = JSONObject()
                input.put("model", "gpt-4.1-mini")
                input.put("input", text)

                connection.outputStream.use {
                    it.write(input.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val response = stream.bufferedReader().use { it.readText() }

                if (responseCode !in 200..299) {
                    throw Exception("Ошибка API: HTTP $responseCode")
                }

                val json = JSONObject(response)
                val output = json.optJSONArray("output") ?: JSONArray()
                val result = StringBuilder()

                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    val content = item.optJSONArray("content") ?: continue

                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") {
                            result.append(part.optString("text"))
                        }
                    }
                }

                val message = result.toString().ifBlank {
                    "Не удалось получить текстовый ответ."
                }

                runOnUiThread {
                    answer.text = message
                    speech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "answer")
                }

                connection.disconnect()
            } catch (e: Exception) {
                runOnUiThread {
                    answer.text = "Не удалось отправить запрос: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        speech.stop()
        speech.shutdown()
        super.onDestroy()
    }
}