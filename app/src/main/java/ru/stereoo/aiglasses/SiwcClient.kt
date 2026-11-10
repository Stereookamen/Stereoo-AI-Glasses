package ru.stereoo.aiglasses

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Experimental, on-device Sign in with ChatGPT client.
 * No API key, proxy or backend is involved.
 * Access/refresh/id tokens are intentionally kept only in RAM for this proof of concept.
 * OAuth client_id and opaque host ID are the only values persisted.
 */
class SiwcClient(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("siwc_poc_public", Activity.MODE_PRIVATE)
    private val random = SecureRandom()
    @Volatile private var listener: ServerSocket? = null
    @Volatile var isAuthorizing = false
        private set
    @Volatile private var accessToken: String? = null
    @Volatile private var expiryMs: Long = 0L
    private var registeredSubject: String? = prefs.getString("subject", null)

    val signedIn: Boolean get() = accessToken != null && System.currentTimeMillis() < expiryMs - 60_000L
    fun close() { try { listener?.close() } catch (_: Exception) {} }
    fun signOut() {
        close()
        accessToken = null
        expiryMs = 0
        registeredSubject = null
        prefs.edit().remove("client_id").remove("subject").apply()
    }

    fun signIn(report: (String) -> Unit, onSignedIn: () -> Unit) {
        if (listener != null || isAuthorizing) { report("Авторизация уже запущена."); return }
        isAuthorizing = true
        val existingClientId = prefs.getString("client_id", null)
        val hostId = prefs.getString("host_id", null) ?: ("urn:uuid:" + UUID.randomUUID()).also {
            prefs.edit().putString("host_id", it).apply()
        }
        val verifier = randomUrlSafe(48)
        val nonce = randomUrlSafe(32)
        val state = randomUrlSafe(32)

        try {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 180_000
            listener = server
            val callback = "http://127.0.0.1:" + server.localPort + "/auth/callback"
            val clientId = existingClientId ?: "dynamic_agent_client"
            val builder = Uri.parse("https://auth.openai.com/api/accounts/authorize").buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("ext_agent_host_id", hostId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", callback)
                .appendQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                .appendQueryParameter("resource", "https://api.openai.com/v1")
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
            if (existingClientId == null) builder.appendQueryParameter("agent_name_hint", "Stereoo AI Glasses")

            report("ШАГ 1/5: открываем страницу ChatGPT в браузере.")
            activity.startActivity(Intent(Intent.ACTION_VIEW, builder.build()).addCategory(Intent.CATEGORY_BROWSABLE))

            thread(name = "siwc-loopback") {
                try {
                    // Browsers may request /favicon.ico. Only accept our exact callback path.
                    var code: String? = null
                    var issuedClientId: String? = null
                    var validCallback = false
                    while (!validCallback) {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                            val firstLine = input.readLine() ?: ""
                            // Read, but don't log, request headers.
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            val target = firstLine.split(" ").getOrNull(1) ?: ""
                            val uri = Uri.parse("http://127.0.0.1" + target)
                            val correctRoute = uri.path == "/auth/callback" && firstLine.startsWith("GET ")
                            val validState = correctRoute && state == uri.getQueryParameter("state")
                            val msg: String
                            if (correctRoute && !validState) {
                                msg = "Неверный OAuth state. Авторизация отменена."
                                sendBrowserReply(socket, msg, 400)
                                throw IllegalStateException(msg)
                            }
                            if (!correctRoute) {
                                sendBrowserReply(socket, "Not found", 404)
                            } else {
                                val error = uri.getQueryParameter("error")
                                if (!error.isNullOrBlank()) {
                                    sendBrowserReply(socket, "Доступ не предоставлен. Можно закрыть вкладку.", 200)
                                    throw IllegalStateException("Авторизация отклонена: " + error)
                                }
                                code = uri.getQueryParameter("code")
                                val returnedClient = uri.getQueryParameter("client_id")
                                if (existingClientId == null) {
                                    if (returnedClient.isNullOrBlank() || returnedClient == "dynamic_agent_client") {
                                        throw IllegalStateException("OpenAI не вернул выданный client_id.")
                                    }
                                    issuedClientId = returnedClient
                                    // Keep the issued registration, even if the one-time code later expires.
                                    prefs.edit().putString("client_id", returnedClient).apply()
                                } else {
                                    if (!returnedClient.isNullOrBlank() && returnedClient != existingClientId) {
                                        throw IllegalStateException("Неожиданный client_id в OAuth callback.")
                                    }
                                    issuedClientId = existingClientId
                                }
                                if (code.isNullOrBlank()) throw IllegalStateException("Нет OAuth-кода.")
                                sendBrowserReply(socket, "Код входа передан приложению. Подтверждение доступа ещё выполняется. Вернитесь в Stereoo AI.", 200)
                                validCallback = true
                            }
                        }
                    }
                    report("ШАГ 2/5: код OAuth получен. Обмениваем код на токен...")
                    val tokens = tokenExchange(
                        mapOf(
                            "grant_type" to "authorization_code",
                            "client_id" to (issuedClientId ?: throw IllegalStateException("Нет client_id.")),
                            "code" to (code ?: throw IllegalStateException("Нет кода.")),
                            "code_verifier" to verifier,
                            "redirect_uri" to callback,
                            "resource" to "https://api.openai.com/v1"
                        )
                    )
                    report("ШАГ 3/5: ответ сервера получен. Проверяем разрешения...")
                    val scopes = tokens.optString("scope", "").split(" ")
                    if (!scopes.contains("chatgpt.tokens.use.direct") || !scopes.contains("resource.invoke")) {
                        throw IllegalStateException("Вход выполнен, но доступ к лимитам ChatGPT не разрешён.")
                    }
                    report("ШАГ 4/5: разрешения подтверждены. Проверяем подпись ID-токена...")
                    val verified = verifyIdToken(tokens.getString("id_token"), issuedClientId!!, nonce)
                    val sub = verified.getString("sub")
                    if (registeredSubject != null && registeredSubject != sub) {
                        throw IllegalStateException("Получен другой аккаунт. Нажми «Выйти» перед сменой аккаунта.")
                    }
                    registeredSubject = sub
                    prefs.edit().putString("subject", sub).apply()
                    accessToken = tokens.getString("access_token")
                    expiryMs = System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000
                    report("✅ ШАГ 5/5: вход и разрешения подтверждены. Получаем список моделей...")
                    activity.runOnUiThread { onSignedIn() }
                } catch (e: Exception) {
                    val problem = if (e is java.net.UnknownHostException ||
                        e.cause is java.net.UnknownHostException) {
                        "Ошибка DNS: телефон не может найти auth.openai.com. Код из браузера получен, " +
                            "но обменять его на токен через эту сеть невозможно. Это не ошибка подписки."
                    } else {
                        "❌ Авторизация не завершена: " + safeError(e.message ?: e.javaClass.simpleName)
                    }
                    report(problem)
                } finally {
                    close()
                    listener = null
                    isAuthorizing = false
                }
            }
        } catch (e: Exception) {
            close()
            listener = null
            isAuthorizing = false
            report("❌ Не удалось начать вход: " + safeError(e.message ?: "ошибка"))
        }
    }

    fun models(): List<Pair<String, String>> {
        val json = http("GET", "https://api.openai.com/v1/models", null, token())
        val values = json.optJSONArray("models") ?: JSONArray()
        val items = mutableListOf<Pair<String, String>>()
        for (i in 0 until values.length()) {
            val v = values.optJSONObject(i) ?: continue
            if (v.optString("visibility") != "list") continue
            val slug = v.optString("slug")
            if (slug.isNotBlank()) items.add(Pair(slug, v.optString("display_name", slug)))
        }
        return items
    }

    data class Turn(val question: String, val imageDataUrl: String?, val answer: String)

    fun ask(model: String, question: String, imageUri: Uri?, history: List<Turn>): Pair<String, Turn> {
        val dataUrl = imageUri?.let {
            val mime = activity.contentResolver.getType(it) ?: "image/jpeg"
            if (mime !in listOf("image/jpeg", "image/png", "image/webp")) {
                throw IllegalArgumentException("Поддерживаются JPEG, PNG и WEBP.")
            }
            val bytes = activity.contentResolver.openInputStream(it)?.use { stream ->
                stream.readBytes()
            } ?: throw IllegalArgumentException("Не удалось прочитать фотографию.")
            if (bytes.size > 3_000_000) throw IllegalArgumentException("Фото больше 3 МБ. Уменьши изображение.")
            "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        val messages = JSONArray()
        for (turn in history.takeLast(5)) {
            messages.put(userMessage(turn.question, turn.imageDataUrl))
            messages.put(JSONObject().put("role", "assistant").put("content", turn.answer))
        }
        val normalizedQuestion = question.ifBlank { "Что изображено на этой фотографии?" }
        messages.put(userMessage(normalizedQuestion, dataUrl))
        val body = JSONObject()
            .put("model", model)
            .put("input", messages)
            .put("store", false)
            .put("stream", true)

        val conn = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Authorization", "Bearer " + token())
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP " + conn.responseCode + ": " + error.take(500))
            }
            var completed = false
            val output = StringBuilder()
            conn.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!line.startsWith("data: ")) return@forEach
                    val raw = line.removePrefix("data: ").trim()
                    if (raw == "[DONE]") return@forEach
                    val evt = JSONObject(raw)
                    when (evt.optString("type")) {
                        "response.output_text.delta" -> output.append(evt.optString("delta"))
                        "response.failed", "response.incomplete" ->
                            throw IllegalStateException("Ответ не завершён: " + raw.take(300))
                        "response.completed" -> completed = true
                        "error" -> throw IllegalStateException("Ошибка AI: " + raw.take(300))
                    }
                }
            }
            if (!completed) throw IllegalStateException("Поток оборвался без response.completed.")
            return Pair(output.toString(), Turn(normalizedQuestion, dataUrl, output.toString()))
        } finally {
            conn.disconnect()
        }
    }

    private fun userMessage(question: String, image: String?): JSONObject {
        val parts = JSONArray().put(JSONObject().put("type", "input_text").put("text", question))
        if (image != null) parts.put(JSONObject().put("type", "input_image").put("image_url", image))
        return JSONObject().put("role", "user").put("content", parts)
    }

    private fun token(): String =
        if (signedIn) accessToken!! else throw IllegalStateException("Войди заново. Тестовая сессия живёт не дольше часа.")

    private fun tokenExchange(params: Map<String, String>): JSONObject {
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = (URL("https://auth.openai.com/api/accounts/oauth/token").openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val responseText = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            if (conn.responseCode !in 200..299) throw IllegalStateException("OAuth HTTP " + conn.responseCode + ": " + responseText.take(350))
            return JSONObject(responseText)
        } finally { conn.disconnect() }
    }

    private fun verifyIdToken(jwt: String, clientId: String, nonce: String): JSONObject {
        val p = jwt.split(".")
        require(p.size == 3) { "Некорректный ID token." }
        val header = JSONObject(String(Base64.decode(p[0], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Неизвестный алгоритм подписи." }
        val kid = header.getString("kid")
        val jwks = http("GET", "https://auth.openai.com/.well-known/jwks.json", null, null)
        val keys = jwks.getJSONArray("keys")
        var verified = false
        for (i in 0 until keys.length()) {
            val key = keys.getJSONObject(i)
            if (key.optString("kid") != kid || key.optString("kty") != "RSA") continue
            val n = BigInteger(1, Base64.decode(key.getString("n"), Base64.URL_SAFE or Base64.NO_WRAP))
            val e = BigInteger(1, Base64.decode(key.getString("e"), Base64.URL_SAFE or Base64.NO_WRAP))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update((p[0] + "." + p[1]).toByteArray(Charsets.US_ASCII))
            verified = sig.verify(Base64.decode(p[2], Base64.URL_SAFE or Base64.NO_WRAP))
            break
        }
        require(verified) { "Подпись ID token не подтверждена." }
        val claims = JSONObject(String(Base64.decode(p[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
        val aud = claims.opt("aud")
        val audValid = when (aud) {
            is String -> aud == clientId
            is JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
            else -> false
        }
        val now = System.currentTimeMillis() / 1000
        require(claims.optString("iss") == "https://auth.openai.com") { "Неверный issuer." }
        require(audValid) { "Неверный OAuth audience." }
        require(claims.optLong("exp") > now - 60) { "ID token истёк." }
        require(claims.optString("nonce") == nonce) { "Неверный OAuth nonce." }
        require(claims.optString("sub").isNotBlank()) { "Нет идентификатора аккаунта." }
        return claims
    }

    private fun http(method: String, path: String, body: String?, bearer: String?): JSONObject {
        val conn = URL(path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer)
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val responseText = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP " + conn.responseCode + ": " + responseText.take(400))
            return JSONObject(responseText)
        } finally { conn.disconnect() }
    }

    private fun sendBrowserReply(socket: java.net.Socket, message: String, code: Int) {
        val page = "<!doctype html><meta charset='utf-8'><title>Stereoo AI</title><p>" +
            message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</p>"
        val bytes = page.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 " + code + " " + if (code == 200) "OK" else "Error"
        val http = head + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
            "Content-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(http.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    // Keep any security-sensitive OAuth/HTTP response data out of diagnostic screenshots.
    private fun safeError(message: String): String {
        val parsed = message.replace(Regex("(?i)(access_token|refresh_token|id_token|authorization_code|code_verifier|client_secret)[\\\":=\\s]+[^,\\s\\\"}]+"), "$1=[скрыто]")
        return parsed.take(200)
    }

    private fun randomUrlSafe(n: Int): String = ByteArray(n).also { random.nextBytes(it) }.let(::base64Url)
    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
