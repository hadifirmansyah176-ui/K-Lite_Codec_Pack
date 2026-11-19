package com.example.player

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Isi 3 nilai ini dengan akun OpenSubtitles.com milikmu agar "Download Subtitle" bisa
 * mencari dan mengunduh otomatis. Daftar gratis di opensubtitles.com, lalu buat API key
 * di menu "API consumers".
 */
object SubtitleConfig {
    const val API_KEY = ""
    const val USERNAME = ""
    const val PASSWORD = ""
    const val USER_AGENT = "KLiteCodecPackA v1.0"
    val configured get() = API_KEY.isNotBlank() && USERNAME.isNotBlank() && PASSWORD.isNotBlank()
}

object SubtitleDownloader {

    private const val BASE = "https://api.opensubtitles.com/api/v1"

    /** "Street.Fighter.2026.1080p.HC.WEB-DL.x264.AAC.mkv" -> "Street Fighter 2026" */
    fun cleanQuery(fileName: String): String {
        var n = fileName.substringBeforeLast('.', fileName)
        n = n.replace(Regex("[._]"), " ")
        val cut = Regex(
            "\\b(2160p|1080p|720p|480p|360p|4k|web[- ]?dl|webrip|bluray|brrip|bdrip|hdrip|hdtv|dvdrip|x264|x265|h264|h265|hevc|aac|ac3|hc)\\b",
            RegexOption.IGNORE_CASE
        ).find(n)
        if (cut != null) n = n.substring(0, cut.range.first)
        return n.replace(Regex("\\s+"), " ").trim()
    }

    private fun request(method: String, url: String, body: String?, token: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Api-Key", SubtitleConfig.API_KEY)
        c.setRequestProperty("User-Agent", SubtitleConfig.USER_AGENT)
        c.setRequestProperty("Accept", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("HTTP $code")
        return text
    }

    /** Cari subtitle (Indonesia dulu, lalu Inggris), unduh, kembalikan file .srt di cache. */
    fun download(query: String, cacheDir: File): File {
        val login = JSONObject(
            request(
                "POST", "$BASE/login",
                JSONObject().put("username", SubtitleConfig.USERNAME)
                    .put("password", SubtitleConfig.PASSWORD).toString(), null
            )
        )
        val token = login.getString("token")

        val q = URLEncoder.encode(query, "UTF-8")
        val found = JSONObject(request("GET", "$BASE/subtitles?languages=id,en&query=$q", null, null))
        val data = found.getJSONArray("data")
        if (data.length() == 0) throw Exception("Subtitle tidak ditemukan untuk \"$query\"")

        var pick = 0
        for (i in 0 until data.length()) {
            if (data.getJSONObject(i).getJSONObject("attributes").optString("language") == "id") {
                pick = i; break
            }
        }
        val fileId = data.getJSONObject(pick).getJSONObject("attributes")
            .getJSONArray("files").getJSONObject(0).getLong("file_id")

        val dl = JSONObject(
            request("POST", "$BASE/download", JSONObject().put("file_id", fileId).toString(), token)
        )
        val bytes = URL(dl.getString("link")).openStream().use { it.readBytes() }
        val out = File(cacheDir, "sub_$fileId.srt")
        out.writeBytes(bytes)
        return out
    }
}
