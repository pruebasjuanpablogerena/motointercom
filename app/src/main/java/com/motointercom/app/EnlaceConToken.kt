package com.motointercom.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Algunas emisoras (como Esperanza Colombia) no tienen un link fijo: primero
 * hay que preguntarle a su servidor, y él responde con el link del momento,
 * que trae un "token" que se vence al rato. Esto hace esa pregunta y devuelve
 * el link listo para reproducir.
 *
 * Como cada servidor responde distinto, entiende las formas más comunes:
 *  - una redirección directa al audio,
 *  - un JSON con el link (en "url", "streamUrl", "stream", "src"...),
 *  - el link solo, como texto,
 *  - una lista de reproducción (.m3u / .pls) con el link adentro.
 */
object EnlaceConToken {

    private val CLAVES_PREFERIDAS = listOf(
        "streamurl", "stream_url", "url", "stream", "src", "source",
        "link", "location", "href", "playlist", "hls", "mp3"
    )

    private val PATRON_URL = Regex("https?://[^\\s\"'<>]+")

    /** Devuelve el link para reproducir, o null si el servidor no dio ninguno. Se llama desde un hilo aparte. */
    fun obtenerEnlace(urlDelServidor: String): String? {
        val conexion = (URL(urlDelServidor).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "MotoIntercom/Android")
        }
        try {
            val codigo = conexion.responseCode
            if (codigo !in 200..299) return null

            // Si la respuesta ya es el audio (porque redirigió directo), ese es el link.
            val tipo = (conexion.contentType ?: "").lowercase()
            if (tipo.startsWith("audio/") && !tipo.contains("mpegurl") && !tipo.contains("scpls")) {
                return conexion.url.toString()
            }

            // Solo se leen los primeros 64 KB: la respuesta debería ser chiquita.
            val cuerpo = conexion.inputStream.use { entrada ->
                val bytes = ByteArray(64 * 1024)
                var total = 0
                while (total < bytes.size) {
                    val n = entrada.read(bytes, total, bytes.size - total)
                    if (n < 0) break
                    total += n
                }
                String(bytes, 0, total, Charsets.UTF_8)
            }.trim()

            if (cuerpo.startsWith("{") || cuerpo.startsWith("[")) {
                return try {
                    val json: Any = if (cuerpo.startsWith("{")) JSONObject(cuerpo) else JSONArray(cuerpo)
                    buscarEnlaceEnJson(json, preferidas = true) ?: buscarEnlaceEnJson(json, preferidas = false)
                } catch (e: Exception) {
                    PATRON_URL.find(cuerpo)?.value
                }
            }
            // Texto plano, .m3u o .pls: el primer link que aparezca.
            return PATRON_URL.find(cuerpo)?.value
        } finally {
            conexion.disconnect()
        }
    }

    /** Busca, dentro del JSON (también en sus partes internas), un texto que sea un link. */
    private fun buscarEnlaceEnJson(nodo: Any?, preferidas: Boolean): String? {
        when (nodo) {
            is JSONObject -> {
                if (preferidas) {
                    for (clavePreferida in CLAVES_PREFERIDAS) {
                        val clave = nodo.keys().asSequence().firstOrNull { it.lowercase() == clavePreferida } ?: continue
                        val valor = nodo.opt(clave)
                        if (valor is String && valor.startsWith("http")) return valor
                    }
                } else {
                    for (clave in nodo.keys()) {
                        val valor = nodo.opt(clave)
                        if (valor is String && valor.startsWith("http")) return valor
                    }
                }
                for (clave in nodo.keys()) {
                    val valor = nodo.opt(clave)
                    if (valor is JSONObject || valor is JSONArray) {
                        buscarEnlaceEnJson(valor, preferidas)?.let { return it }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until nodo.length()) {
                    val valor = nodo.opt(i)
                    if (!preferidas && valor is String && valor.startsWith("http")) return valor
                    buscarEnlaceEnJson(valor, preferidas)?.let { return it }
                }
            }
        }
        return null
    }
}
