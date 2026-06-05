package co.tagnology.embed.sdk

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object EmbedApi {
    private const val TAG = "EmbedSDK"
    fun fetchPageBundle(
        baseUrl: String,
        pageUrl: String,
        pageId: String,
        mid: String,
        payloadSecret: String,
    ): Result<List<EmbedWidgetItem>> {
        return runCatching {
            val endpoint = "${baseUrl.trimEnd('/')}/widget/pageBundle"
            Log.d(TAG, "fetchPageBundle endpoint=$endpoint pageUrl=$pageUrl pageId=$pageId mid=$mid")
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            val request = buildEncryptedRequest(
                pageUrl = pageUrl,
                pageId = pageId,
                mid = mid,
                payloadSecret = payloadSecret,
            )

            BufferedOutputStream(connection.outputStream).use { out ->
                out.write(request.toString().toByteArray())
                out.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IllegalStateException("pageBundle API error status=$statusCode")
            }

            val bodyText = connection.inputStream.bufferedReader().use { it.readText() }
            Log.d(TAG, "fetchPageBundle success status=$statusCode bodyLength=${bodyText.length}")
            parsePageBundle(bodyText, pageUrl)
        }
    }

    private fun parsePageBundle(bodyText: String, pageUrl: String): List<EmbedWidgetItem> {
        val root = JSONObject(bodyText)
        val pageBundle = root.optJSONArray("pageBundle") ?: JSONArray()
        if (pageBundle.length() == 0) return emptyList()

        val widgets = mutableListOf<EmbedWidgetItem>()
        for (i in 0 until pageBundle.length()) {
            val item = pageBundle.optJSONObject(i) ?: continue
            val folderId = item.optString("folderId")
            val folderName = item.optString("folderName", "內容牆")
            val embedLocation = item.optString("embedLocation")
            val clickUrl = item.optString("productUrl", pageUrl)
            val mediaId = item.optString("mediaId").ifBlank { null }
            val layout = item.optString("layout").ifBlank { null }

            val position = runCatching { EmbedPosition.valueOf(embedLocation) }.getOrNull() ?: continue
            if (folderId.isBlank()) continue
            if (layout.equals("floatingmedia", ignoreCase = true)) {
                Log.d(
                    TAG,
                    "pageBundle skip floatingMedia index=$i folderId=$folderId embedLocation=$embedLocation folderName=$folderName"
                )
                continue
            }

            Log.d(
                TAG,
                "pageBundle item index=$i folderId=$folderId layout=${layout ?: "null"} embedLocation=$embedLocation folderName=$folderName"
            )

            widgets += EmbedWidgetItem(
                folderId = folderId,
                folderName = folderName,
                position = position,
                html = null,
                layout = layout,
                clickUrl = clickUrl,
                mediaId = mediaId,
            )
        }

        Log.d(TAG, "pageBundle parsed widgets=${widgets.size}")

        return widgets
    }

    private fun buildEncryptedRequest(
        pageUrl: String,
        pageId: String,
        mid: String,
        payloadSecret: String,
    ): JSONObject {
        val midInt = mid.toIntOrNull() ?: throw IllegalArgumentException("mid must be numeric")
        val secretDecoded = try {
            Base64.decode(payloadSecret, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw IllegalArgumentException("payloadSecret must be base64")
        require(secretDecoded.size == 32) { "payloadSecret must decode to 32 bytes" }

        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest((mid + payloadSecret).toByteArray(Charsets.UTF_8))
        val keySpec = SecretKeySpec(keyBytes, "AES")

        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val payload = JSONObject()
            .put("mid", midInt)
            .put("id", pageId)
            .put("url", pageUrl)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(payload)

        val tagLength = 16
        val ciphertext = encrypted.copyOfRange(0, encrypted.size - tagLength)
        val tag = encrypted.copyOfRange(encrypted.size - tagLength, encrypted.size)

        return JSONObject()
            .put("mid", midInt)
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("payload", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .put("tag", Base64.encodeToString(tag, Base64.NO_WRAP))
    }
}
