package com.motointercom.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * El reproductor de la radio vive aquí (y no dentro de la pantalla de radio)
 * para que la emisora pueda SEGUIR SONANDO al volver a la sala por internet:
 * así se escucha radio mientras se está en la sala.
 *
 * Cuando llega un audio de la sala, la sala pide el sonido "por un momento"
 * y la radio baja su volumen sola; al terminar el audio, vuelve a subir.
 *
 * Usa ExoPlayer (de Google) en vez del reproductor básico de Android: el
 * básico NO entiende las transmisiones HLS por pedacitos de AAC que usan,
 * por ejemplo, las emisoras de RCN (La Mega, La FM, Alerta, El Sol...), y
 * por eso no sonaban en la app aunque sí en el navegador.
 */
object ReproductorRadio {

    private const val VOLUMEN_BAJITO = 0.2f

    private val principal = Handler(Looper.getMainLooper())
    private var reproductor: ExoPlayer? = null
    private var contextoApp: Context? = null
    private var reintentosToken = 0
    private var audioManager: AudioManager? = null
    private var pedidoDeFoco: AudioFocusRequest? = null

    var emisoraActual: Emisora? = null
        private set
    var estado: String = "Selecciona una emisora"
        private set

    /** La pantalla de radio (si está abierta) se entera aquí de los cambios de estado. */
    var oyente: ((String) -> Unit)? = null

    fun estaSonando(): Boolean = emisoraActual != null

    fun reproducir(contexto: Context, emisora: Emisora) {
        detener()
        contextoApp = contexto.applicationContext
        audioManager = contexto.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        emisoraActual = emisora
        reintentosToken = 0
        pedirFoco()
        if (emisora.pideToken) {
            pedirEnlaceYReproducir(emisora)
        } else {
            cambiarEstado("Cargando: ${emisora.nombre}...")
            iniciarReproductor(emisora, emisora.streaming)
        }
    }

    fun detener() {
        reproductor?.let { try { it.release() } catch (e: Exception) {} }
        reproductor = null
        emisoraActual = null
        soltarFoco()
        cambiarEstado("Selecciona una emisora")
    }

    /** Primero le pide al servidor de la emisora el link del momento (con token)
     *  y después reproduce con ese link. */
    private fun pedirEnlaceYReproducir(emisora: Emisora) {
        cambiarEstado("Conectando con ${emisora.nombre}...")
        Thread {
            val enlace = try { EnlaceConToken.obtenerEnlace(emisora.streaming) } catch (e: Exception) { null }
            principal.post {
                // Si mientras tanto se eligió otra emisora o se detuvo, no se hace nada.
                if (emisoraActual !== emisora) return@post
                if (enlace == null) {
                    cambiarEstado("No se pudo conectar con ${emisora.nombre}. Revisa tu internet.")
                } else {
                    cambiarEstado("Cargando: ${emisora.nombre}...")
                    iniciarReproductor(emisora, enlace)
                }
            }
        }.start()
    }

    private fun iniciarReproductor(emisora: Emisora, enlace: String) {
        reproductor?.let { try { it.release() } catch (e: Exception) {} }
        reproductor = null
        val contexto = contextoApp ?: return
        try {
            // Permite redirecciones de http a https y al revés (varias emisoras las usan).
            val fabricaHttp = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("MotoIntercom/Android")
            val jugador = ExoPlayer.Builder(contexto)
                .setMediaSourceFactory(DefaultMediaSourceFactory(contexto).setDataSourceFactory(fabricaHttp))
                .build()
            jugador.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false // el foco de audio lo maneja este archivo (para bajar con la sala)
            )
            jugador.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(estadoJugador: Int) {
                    if (emisoraActual !== emisora) return
                    when (estadoJugador) {
                        Player.STATE_READY -> {
                            reintentosToken = 0
                            cambiarEstado("🔴 En vivo: ${emisora.nombre}")
                        }
                        Player.STATE_BUFFERING -> if (estado.startsWith("🔴")) cambiarEstado("Cargando: ${emisora.nombre}...")
                        // Una radio en vivo no "termina": si terminó, se cortó (o se venció el token).
                        Player.STATE_ENDED -> reintentar(emisora, enlace)
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (emisoraActual !== emisora) return
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        // Se quedó atrás de la transmisión en vivo: se salta al presente.
                        jugador.seekToDefaultPosition()
                        jugador.prepare()
                    } else {
                        reintentar(emisora, enlace)
                    }
                }
            })
            val item = MediaItem.Builder().setUri(enlace).apply {
                // Si el link no termina en .m3u8 pero es HLS, se le dice a ExoPlayer.
                if (enlace.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            }.build()
            jugador.setMediaItem(item)
            jugador.prepare()
            jugador.playWhenReady = true
            reproductor = jugador
        } catch (e: Exception) {
            cambiarEstado("No se pudo reproducir ${emisora.nombre}")
        }
    }

    /** Si se cortó: las emisoras con token piden un link nuevo; las demás
     *  reintentan con el mismo. Hasta 3 veces seguidas. */
    private fun reintentar(emisora: Emisora, enlace: String) {
        if (emisoraActual !== emisora) return
        if (reintentosToken >= 3) {
            cambiarEstado("No se pudo reproducir ${emisora.nombre}")
            return
        }
        reintentosToken++
        cambiarEstado("Reconectando ${emisora.nombre}...")
        if (emisora.pideToken) {
            pedirEnlaceYReproducir(emisora)
        } else {
            principal.postDelayed({ if (emisoraActual === emisora) iniciarReproductor(emisora, enlace) }, 1500)
        }
    }

    private fun pedirFoco() {
        val am = audioManager ?: return
        val pedido = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener({ cambio ->
                when (cambio) {
                    // Llegó un audio de la sala (u otro aviso corto): la radio baja.
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                        try { reproductor?.volume = VOLUMEN_BAJITO } catch (e: Exception) {}
                    // Terminó el audio: la radio vuelve a su volumen.
                    AudioManager.AUDIOFOCUS_GAIN ->
                        try { reproductor?.volume = 1f } catch (e: Exception) {}
                }
            }, principal)
            .build()
        pedidoDeFoco = pedido
        try { am.requestAudioFocus(pedido) } catch (e: Exception) {}
    }

    private fun soltarFoco() {
        val pedido = pedidoDeFoco ?: return
        try { audioManager?.abandonAudioFocusRequest(pedido) } catch (e: Exception) {}
        pedidoDeFoco = null
    }

    private fun cambiarEstado(texto: String) {
        estado = texto
        oyente?.invoke(texto)
    }
}
