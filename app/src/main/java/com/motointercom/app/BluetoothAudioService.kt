package com.motointercom.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Maneja: 1) la conexión Bluetooth (servidor o cliente),
 *         2) el envío del micrófono propio hacia el otro celular,
 *         3) la reproducción de lo que llega del otro celular,
 *         4) bajar el volumen de otras apps (GPS/música) mientras hay conexión,
 *         5) el enrutamiento de la llamada hacia el altavoz o hacia unos
 *            audífonos Bluetooth conectados al mismo tiempo que el otro celular,
 *         6) el refuerzo de volumen (ganancia) de lo que llega del otro celular.
 */
class BluetoothAudioService(
    private val audioManager: AudioManager,
    private val callback: Callback
) {

    interface Callback {
        fun onEstadoCambiado(mensaje: String)
        fun onError(mensaje: String)
        fun onComandoRadioRecibido(comando: String)
    }

    companion object {
        val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        val CONTROL_UUID: UUID = UUID.fromString("8ce255c1-200a-11e0-ac64-0800200c9a66")
        // 8 kHz = calidad de llamada telefónica: suficiente para entenderse con
        // claridad, y usa la mitad de aire Bluetooth que los 16 kHz de antes.
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // El audio viaja en "paquetes" de 20 ms: 160 muestras (320 bytes de audio
        // sin comprimir, que al comprimirse a 8 bits quedan en 160 bytes).
        private const val FRAME_MUESTRAS = 160
        private const val FRAME_BYTES = FRAME_MUESTRAS * 2

        // Si en el otro lado se acumulan más de 200 ms de audio sin reproducir,
        // se descarta lo viejo para volver a estar casi en tiempo real.
        private const val MAX_ATRASO_BYTES = FRAME_MUESTRAS * 10

        // Máximo de audio "en camino" (ya entregado al Bluetooth de ESTE celular
        // pero que el otro todavía no ha confirmado). Android guarda por dentro
        // varios segundos de datos cuando el enlace se pone lento (por ejemplo,
        // cuando Waze o la música también están usando el Bluetooth), y ese
        // colchón era el que generaba el retraso de varios segundos. Si se pasa
        // de ~400 ms, se descarta el paquete nuevo en vez de seguir acumulando.
        private const val MAX_EN_CAMINO_BYTES = FRAME_MUESTRAS * 20

        // Aviso que el receptor manda por el canal de control cada ~100 ms con
        // cuántos bytes de voz lleva recibidos. Las versiones anteriores de la
        // app no lo mandan, y en ese caso simplemente no se usa el control.
        private const val PREFIJO_ACUSE = "#ACK:"
        private const val CADA_CUANTO_ACUSE_MS = 100L

        // Marca de versión del formato de audio ("MI02"). Si el otro celular
        // tiene una versión anterior de la app, se detecta y se avisa, en vez
        // de dejar que suene a estática.
        private val MAGIC = byteArrayOf(0x4D, 0x49, 0x30, 0x32)

        // ---------- Compresión mu-law (la misma de las llamadas telefónicas) ----------
        // Convierte cada muestra de 16 bits en 1 byte: la mitad de datos por el
        // aire, con una pérdida de calidad que casi no se nota al hablar.
        private const val MULAW_BIAS = 0x84
        private const val MULAW_CLIP = 32635

        private fun linealAMuLaw(muestra: Int): Byte {
            var s = muestra
            val signo = (s shr 8) and 0x80
            if (signo != 0) s = -s
            if (s > MULAW_CLIP) s = MULAW_CLIP
            s += MULAW_BIAS
            var exponente = 7
            var mascara = 0x4000
            while ((s and mascara) == 0 && exponente > 0) {
                exponente--
                mascara = mascara shr 1
            }
            val mantisa = (s shr (exponente + 3)) and 0x0F
            return (signo or (exponente shl 4) or mantisa).inv().toByte()
        }

        private fun muLawALineal(codigo: Int): Int {
            val u = codigo.inv() and 0xFF
            val signo = u and 0x80
            val exponente = (u shr 4) and 0x07
            val mantisa = u and 0x0F
            val s = (((mantisa shl 3) + MULAW_BIAS) shl exponente) - MULAW_BIAS
            return if (signo != 0) -s else s
        }

        // Tabla ya calculada para descomprimir rápido (256 posibles bytes).
        private val TABLA_MULAW_A_PCM = ShortArray(256) { muLawALineal(it).toShort() }
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    // Canal chiquito, separado del de voz, que solo manda avisos de texto como
    // "sintoniza esta emisora" o "deja de sintonizarla". Va por su propia conexión
    // Bluetooth para no mezclarse nunca con el audio de la llamada.
    private var controlServerSocket: BluetoothServerSocket? = null
    private var controlSocket: BluetoothSocket? = null
    private var controlEscritor: java.io.PrintWriter? = null
    private var hiloControl: Thread? = null

    private var hiloEnvio: Thread? = null
    private var hiloRecepcion: Thread? = null
    private var hiloAceptar: Thread? = null

    // Fila de paquetes listos para enviar. El micrófono deja paquetes aquí y OTRO
    // hilo los manda por Bluetooth: así, si el enlace se pone lento, el micrófono
    // no se queda esperando (y por eso ya no pierde audio). Si la fila se llena
    // se descartan los paquetes más viejos, así el retraso nunca pasa de ~200 ms.
    private val colaEnvio = java.util.concurrent.ArrayBlockingQueue<ByteArray>(10)
    private var hiloEnvioRed: Thread? = null

    // Evita pedir la ruta Bluetooth una y otra vez (Android viejo).
    private var scoIniciado = false

    @Volatile private var conectado = false
    @Volatile private var silenciado = false

    // Cada conexión tiene su propio número. Los hilos de una conexión anterior
    // que todavía no han terminado ven que el número cambió y se salen solos,
    // en vez de seguir leyendo el micrófono (o cerrando la conexión nueva) a la vez.
    @Volatile private var sesion = 0

    // Control de flujo (ver MAX_EN_CAMINO_BYTES).
    @Volatile private var bytesEnviados = 0L
    @Volatile private var bytesConfirmados = -1L // -1 = el otro lado no confirma
    @Volatile private var bytesRecibidos = 0L

    // Lo que eligió el usuario, para poder volver a aplicarlo si otra app
    // (como Waze) cambia el modo de audio del celular.
    @Volatile private var altavozDeseado = false
    private var hiloVigilante: Thread? = null

    // Refuerzo de volumen aplicado a lo que se recibe del otro celular.
    // 1.0 = volumen normal, 3.0 = triple de fuerte. Por defecto sale 1.5x
    // más fuerte porque el volumen de voz por Bluetooth suele ser bajo.
    @Volatile private var ganancia = 1.5f

    @Volatile private var usarAudifonosBluetooth = true
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null

    /** Permite desactivar el intento de usar audífonos Bluetooth al mismo tiempo
     *  que la conexión con el otro celular, por si en un teléfono en particular
     *  esa combinación genera retraso (dos conexiones Bluetooth de audio en vivo
     *  al mismo tiempo le exigen mucho a la radio Bluetooth de muchos celulares). */
    fun establecerUsoDeAudifonosBluetooth(usar: Boolean) {
        usarAudifonosBluetooth = usar
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    // Captura del audio del sistema (música/GPS) para compartirlo con el otro celular.
    // Solo se usa si ESTE celular activó "Compartir mi música/GPS".
    private var audioRecordSistema: AudioRecord? = null
    @Volatile private var compartiendoAudioDelSistema = false

    @SuppressLint("MissingPermission")
    fun iniciarComoServidor(adapter: BluetoothAdapter) {
        detener()
        hiloAceptar = Thread {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("MotoIntercom", APP_UUID)
                callback.onEstadoCambiado("Esperando que el pasajero se conecte...")
                val socketAceptado = serverSocket?.accept()
                socketAceptado?.let {
                    socket = it
                    conexionLista()
                }
            } catch (e: SecurityException) {
                callback.onError("Falta el permiso de Bluetooth. Ábrelo desde Ajustes del celular > Apps > MotoIntercom > Permisos y actívalo.")
            } catch (e: Exception) {
                callback.onError("No se pudo esperar conexión: ${e.message}")
            }
        }
        hiloAceptar?.start()
        iniciarControlComoServidor(adapter)
    }

    @SuppressLint("MissingPermission")
    private fun iniciarControlComoServidor(adapter: BluetoothAdapter) {
        Thread {
            try {
                controlServerSocket = adapter.listenUsingRfcommWithServiceRecord("MotoIntercomControl", CONTROL_UUID)
                val s = controlServerSocket?.accept()
                configurarCanalControl(s)
            } catch (e: Exception) {
                // El canal de avisos de radio es opcional: si falla, la llamada de voz
                // sigue funcionando igual, solo no se podrá avisar la emisora al otro lado.
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun conectarComoCliente(dispositivo: BluetoothDevice) {
        detener()
        Thread {
            try {
                callback.onEstadoCambiado("Conectando con ${dispositivo.name}...")
                val s = dispositivo.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                socket = s
                conexionLista()
            } catch (e: SecurityException) {
                callback.onError("Falta el permiso de Bluetooth. Ábrelo desde Ajustes del celular > Apps > MotoIntercom > Permisos y actívalo.")
            } catch (e: Exception) {
                callback.onError("No se pudo conectar: ${e.message}")
            }
        }.start()
        iniciarControlComoCliente(dispositivo)
    }

    @SuppressLint("MissingPermission")
    private fun iniciarControlComoCliente(dispositivo: BluetoothDevice) {
        Thread {
            try {
                // Se espera un poco para darle tiempo al otro celular de abrir su
                // lado del canal de avisos antes de intentar conectarnos a él.
                Thread.sleep(1200)
                val s = dispositivo.createRfcommSocketToServiceRecord(CONTROL_UUID)
                s.connect()
                configurarCanalControl(s)
            } catch (e: Exception) {
                // Opcional: si falla, la llamada de voz sigue funcionando igual.
            }
        }.start()
    }

    private fun configurarCanalControl(socketDeControl: BluetoothSocket?) {
        if (socketDeControl == null) return
        try {
            controlSocket = socketDeControl
            val escritor = java.io.PrintWriter(socketDeControl.outputStream, true)
            controlEscritor = escritor
            val lector = java.io.BufferedReader(java.io.InputStreamReader(socketDeControl.inputStream))
            hiloControl = Thread {
                try {
                    while (true) {
                        val linea = lector.readLine() ?: break
                        if (linea.startsWith(PREFIJO_ACUSE)) {
                            linea.substring(PREFIJO_ACUSE.length).toLongOrNull()?.let { bytesConfirmados = it }
                        } else {
                            callback.onComandoRadioRecibido(linea)
                        }
                    }
                } catch (e: Exception) {
                    // El canal de avisos se cerró; no afecta la llamada de voz.
                }
                // Sin canal de avisos no llegan más confirmaciones: se apaga el
                // control de flujo para no quedarse descartando audio para siempre.
                bytesConfirmados = -1L
            }
            hiloControl?.start()

            // Hilo aparte que le confirma al otro celular cuánto audio ha llegado.
            // Va separado del hilo que reproduce el audio para que, si el canal de
            // avisos se pone lento, el sonido no se quede esperando.
            Thread {
                var ultimoAcuse = -1L
                try {
                    while (controlSocket === socketDeControl) {
                        Thread.sleep(CADA_CUANTO_ACUSE_MS)
                        val recibidos = bytesRecibidos
                        if (conectado && recibidos != ultimoAcuse) {
                            escritor.println(PREFIJO_ACUSE + recibidos)
                            if (escritor.checkError()) break
                            ultimoAcuse = recibidos
                        }
                    }
                } catch (e: InterruptedException) {
                } catch (e: Exception) {}
            }.start()
        } catch (e: Exception) {}
    }

    /** Manda un aviso corto al otro celular por el canal de control (no de voz),
     *  por ejemplo para decirle qué emisora de radio sintonizar. */
    fun enviarComandoRadio(comando: String) {
        try {
            controlEscritor?.println(comando)
        } catch (e: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun conexionLista() {
        try {
            inputStream = socket?.inputStream
            outputStream = socket?.outputStream
            sesion++
            bytesEnviados = 0L
            bytesConfirmados = -1L
            bytesRecibidos = 0L
            altavozDeseado = false
            conectado = true
            pedirDuckingDeOtrasApps()
            activarModoLlamada()
            maximizarVolumenDeLlamada()
            iniciarEnvioDeMicrofono()
            iniciarRecepcionDeAudio()
            iniciarVigilanteDeAudio()
            callback.onEstadoCambiado("Conectado. Ya pueden hablar.")
        } catch (e: SecurityException) {
            callback.onError("Falta el permiso de micrófono o Bluetooth. Ábrelo desde Ajustes del celular > Apps > MotoIntercom > Permisos y actívalo.")
        } catch (e: Exception) {
            callback.onError("Error preparando el audio: ${e.message}")
        }
    }

    private fun pedirDuckingDeOtrasApps() {
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // GAIN_TRANSIENT_MAY_DUCK = "baja el volumen de las demás mientras hablamos".
        // Con AUDIOFOCUS_GAIN (lo de antes) Android trata la llamada como si fuera
        // una app de música nueva: Spotify/Waze PIERDEN el audio del todo y luego
        // pelean por recuperarlo, que es justo lo que hacía que todo se trabara.
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(atributos)
            .setWillPauseWhenDucked(false)
            .build()

        try { audioManager.requestAudioFocus(focusRequest!!) } catch (e: Exception) {}
    }

    /**
     * Waze (y otras apps de GPS) a veces cambian el "modo" de audio del celular
     * para decir sus indicaciones y, al terminar, lo dejan en modo normal. Con
     * eso la voz del intercomunicador se salía de los audífonos o se quedaba
     * muda. Este vigilante revisa cada 2 segundos y, si alguien cambió el modo,
     * lo vuelve a poner como estaba, sin cortar la conexión.
     */
    private fun iniciarVigilanteDeAudio() {
        val miSesion = sesion
        hiloVigilante = Thread {
            try {
                while (conectado && sesion == miSesion) {
                    Thread.sleep(2000)
                    if (!conectado || sesion != miSesion) break
                    if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        reaplicarRuta()
                    }
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {}
        }
        hiloVigilante?.start()
    }

    @SuppressLint("MissingPermission")
    private fun reaplicarRuta() {
        if (altavozDeseado) {
            activarAltavoz(true)
        } else if (usarAudifonosBluetooth) {
            scoIniciado = false
            intentarUsarAudifonosBluetooth()
        }
    }

    private fun soltarDucking() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /**
     * Pone el teléfono en "modo llamada". Esto es lo que permite que Android
     * enrute el audio correctamente hacia el auricular, el altavoz, o unos
     * audífonos Bluetooth, en vez de tratarlo como si fuera música de fondo.
     */
    @SuppressLint("MissingPermission")
    private fun activarModoLlamada() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        registrarDeteccionDeAudifonos()
        if (usarAudifonosBluetooth) {
            intentarUsarAudifonosBluetooth()
        }
    }

    /**
     * Escucha en tiempo real cuando se conecta o desconecta cualquier dispositivo
     * de audio (por ejemplo, un intercomunicador Bluetooth de casco) MIENTRAS la
     * llamada ya está en curso, y cambia la ruta de audio sola, sin cortar ni
     * congelar la transmisión de voz.
     */
    private fun registrarDeteccionDeAudifonos() {
        if (audioDeviceCallback != null) return
        audioDeviceCallback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(dispositivosAgregados: Array<out android.media.AudioDeviceInfo>) {
                // Solo importa si lo que apareció es un dispositivo Bluetooth de
                // voz. Antes se reintentaba la ruta con CUALQUIER cambio de audio
                // (por ejemplo, cuando Waze empieza a hablar), y eso reiniciaba el
                // enlace Bluetooth de voz una y otra vez, quitándole aire a la
                // conexión con el otro celular.
                val hayBluetoothDeVoz = dispositivosAgregados.any {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (hayBluetoothDeVoz && usarAudifonosBluetooth && conectado) {
                    intentarUsarAudifonosBluetooth()
                }
            }

            override fun onAudioDevicesRemoved(dispositivosQuitados: Array<out android.media.AudioDeviceInfo>) {
                // Si se desconectan los audífonos, Android vuelve solo al
                // auricular o altavoz según lo que haya elegido el usuario;
                // no hace falta hacer nada extra aquí.
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun desregistrarDeteccionDeAudifonos() {
        audioDeviceCallback?.let { try { audioManager.unregisterAudioDeviceCallback(it) } catch (e: Exception) {} }
        audioDeviceCallback = null
    }

    /**
     * Si el celular ya tiene unos audífonos Bluetooth (manos libres) conectados,
     * intenta enrutar la voz hacia ellos. Esta es una conexión Bluetooth
     * totalmente distinta a la que se usa para hablar con el otro celular
     * (esa va por un canal de datos, RFCOMM), así que en la mayoría de
     * teléfonos ambas pueden funcionar al mismo tiempo. En algunos modelos
     * el fabricante no permite tener las dos activas a la vez; si eso pasa,
     * la app simplemente sigue usando el altavoz o el auricular del celular.
     *
     * En Android 12 (API 31) en adelante se usa setCommunicationDevice(), la
     * forma moderna y más confiable de elegir el dispositivo de audio de la
     * llamada. En versiones más viejas se usa el método clásico (SCO).
     */
    @SuppressLint("MissingPermission")
    private fun intentarUsarAudifonosBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dispositivoBt = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                // Solo se cambia si todavía no es el dispositivo activo.
                if (dispositivoBt != null && audioManager.communicationDevice?.id != dispositivoBt.id) {
                    audioManager.setCommunicationDevice(dispositivoBt)
                }
            } else if (!scoIniciado && audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                scoIniciado = true
            }
        } catch (e: Exception) {
            // No hay audífonos disponibles o el teléfono no soporta las dos
            // conexiones Bluetooth a la vez: no es un error grave, se sigue
            // escuchando por el altavoz o el auricular.
        }
    }

    @SuppressLint("MissingPermission")
    private fun maximizarVolumenDeLlamada() {
        try {
            val maximo = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maximo, 0)
        } catch (e: Exception) {
            // Si el fabricante no lo permite, no es grave: queda el volumen que ya tenía.
        }
    }

    /** Enciende o apaga el altavoz del celular (si está apagado, usa el auricular). */
    @SuppressLint("MissingPermission")
    fun activarAltavoz(activar: Boolean) {
        altavozDeseado = activar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val tipoBuscado = if (activar) {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val dispositivo = audioManager.availableCommunicationDevices.firstOrNull { it.type == tipoBuscado }
                if (dispositivo != null) {
                    audioManager.setCommunicationDevice(dispositivo)
                }
            } catch (e: Exception) {}
        }
        audioManager.isSpeakerphoneOn = activar
    }

    /** factor entre 1.0 (normal) y 3.0 (triple de fuerte). */
    fun establecerGanancia(factor: Float) {
        ganancia = factor.coerceIn(1.0f, 3.0f)
    }

    @SuppressLint("MissingPermission")
    private fun restaurarAudioNormal() {
        desregistrarDeteccionDeAudifonos()
        scoIniciado = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        } catch (e: Exception) {}
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    /** Sube la prioridad del hilo de audio, para que el sonido no se corte aunque
     *  otra app (como Waze) esté usando mucho el procesador. */
    private fun prioridadDeAudio() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun iniciarEnvioDeMicrofono() {
        // El buffer interno del micrófono es varias veces más grande que un
        // paquete (20 ms), para que un tropiezo momentáneo del celular no haga
        // perder audio.
        val bufferInterno = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT),
            FRAME_BYTES * 8
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, bufferInterno
        )
        audioRecord?.startRecording()
        colaEnvio.clear()

        // Hilo 1: SOLO captura el micrófono, lo comprime y deja el paquete en la
        // fila. Nunca espera al Bluetooth.
        val miSesion = sesion
        hiloEnvio = Thread {
            prioridadDeAudio()
            val pcmMic = ByteArray(FRAME_BYTES)
            val pcmSistema = ByteArray(FRAME_BYTES)
            val pcmMezcla = ByteArray(FRAME_BYTES)
            while (conectado && sesion == miSesion) {
                val leidosMic = audioRecord?.read(pcmMic, 0, pcmMic.size) ?: -1
                if (leidosMic <= 0) {
                    try { Thread.sleep(5) } catch (e: InterruptedException) {}
                    continue
                }
                val n = leidosMic - (leidosMic % 2) // siempre un número par de bytes
                if (n <= 0 || silenciado) continue

                var fuente = pcmMic
                if (compartiendoAudioDelSistema && audioRecordSistema != null) {
                    val leidosSistema = try {
                        audioRecordSistema?.read(pcmSistema, 0, pcmSistema.size) ?: 0
                    } catch (e: Exception) {
                        // Algunas apps (Spotify y otras de streaming) no permiten que se
                        // capture su audio. Si pasa esto, seguimos enviando solo la voz
                        // en vez de tumbar la llamada.
                        0
                    }
                    mezclarVozConMusica(pcmMic, pcmSistema, pcmMezcla, n, maxOf(leidosSistema, 0))
                    fuente = pcmMezcla
                }

                // Comprime: 2 bytes por muestra -> 1 byte por muestra.
                val paquete = ByteArray(n / 2)
                var i = 0
                var j = 0
                while (i < n - 1) {
                    val muestra = (fuente[i + 1].toInt() shl 8) or (fuente[i].toInt() and 0xFF)
                    paquete[j++] = linealAMuLaw(muestra)
                    i += 2
                }

                if (!colaEnvio.offer(paquete)) {
                    colaEnvio.poll()          // fila llena: se descarta el paquete más viejo
                    colaEnvio.offer(paquete)
                }
            }
        }
        hiloEnvio?.start()

        // Hilo 2: saca los paquetes de la fila y los manda por Bluetooth. Si el
        // enlace se pone lento, solo se atrasa este hilo, no el micrófono.
        hiloEnvioRed = Thread {
            prioridadDeAudio()
            try {
                outputStream?.write(MAGIC)
                while (conectado && sesion == miSesion) {
                    val paquete = colaEnvio.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    // Si el otro celular confirma lo que le llega y ya hay demasiado
                    // audio "en camino", este paquete se descarta: es mejor perder
                    // 20 ms de voz que sumar segundos de retraso.
                    val confirmados = bytesConfirmados
                    if (confirmados >= 0 && bytesEnviados - confirmados > MAX_EN_CAMINO_BYTES) continue
                    outputStream?.write(paquete)
                    bytesEnviados += paquete.size
                }
            } catch (e: IOException) {
                if (conectado && sesion == miSesion) {
                    callback.onError("Se perdió la conexión al enviar audio")
                    detener()
                }
            } catch (e: InterruptedException) {
                // Se está cerrando la conexión.
            }
        }
        hiloEnvioRed?.start()
    }

    /**
     * Suma la voz (bufferA) con la música/GPS capturada (bufferB), muestra por muestra
     * (PCM 16 bits), bajando un poco el volumen de la música para que la voz se entienda.
     * Si no hay datos de música en este instante, simplemente se manda la voz sola.
     */
    private fun mezclarVozConMusica(bufferA: ByteArray, bufferB: ByteArray, salida: ByteArray, largoA: Int, largoB: Int) {
        var i = 0
        while (i < largoA - 1) {
            val muestraVoz = ((bufferA[i + 1].toInt() shl 8) or (bufferA[i].toInt() and 0xFF)).toShort()
            val muestraMusica = if (i < largoB - 1)
                ((bufferB[i + 1].toInt() shl 8) or (bufferB[i].toInt() and 0xFF)).toShort()
            else 0

            var mezcla = muestraVoz + (muestraMusica * 0.6).toInt()
            if (mezcla > Short.MAX_VALUE) mezcla = Short.MAX_VALUE.toInt()
            if (mezcla < Short.MIN_VALUE) mezcla = Short.MIN_VALUE.toInt()

            salida[i] = (mezcla and 0xFF).toByte()
            salida[i + 1] = ((mezcla shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /**
     * Multiplica cada muestra de audio recibida del otro celular por el factor
     * de ganancia elegido, cuidando de no pasarse del máximo permitido (para
     * que no se distorsione el sonido en vez de sonar más fuerte).
     */
    private fun aplicarGanancia(buffer: ByteArray, largo: Int) {
        if (ganancia <= 1.01f) return
        var i = 0
        while (i < largo - 1) {
            val muestra = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
            var amplificada = (muestra * ganancia).toInt()
            if (amplificada > Short.MAX_VALUE) amplificada = Short.MAX_VALUE.toInt()
            if (amplificada < Short.MIN_VALUE) amplificada = Short.MIN_VALUE.toInt()
            buffer[i] = (amplificada and 0xFF).toByte()
            buffer[i + 1] = ((amplificada shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /**
     * Activa la captura del audio que suena en ESTE celular (música, Waze, etc.)
     * para mezclarlo con la voz antes de enviarlo. Requiere Android 10+ y el permiso
     * que el usuario concede en la pantalla que Android muestra automáticamente.
     */
    fun activarCompartirAudioDelSistema(mediaProjection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .build()

            val formato = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_IN)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)

            audioRecordSistema = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(formato)
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecordSistema?.startRecording()
            compartiendoAudioDelSistema = true
        } catch (e: Exception) {
            // Si esto falla (por ejemplo porque la app elegida, como Spotify, no permite
            // que se capture su audio) no debe tumbar la llamada: se avisa y se sigue
            // hablando normalmente, solo sin la música de fondo.
            compartiendoAudioDelSistema = false
            audioRecordSistema = null
            callback.onError("No se pudo compartir el audio de esa app (puede que no lo permita). La llamada sigue activa.")
        }
    }

    fun desactivarCompartirAudioDelSistema() {
        compartiendoAudioDelSistema = false
        audioRecordSistema?.let { try { it.stop(); it.release() } catch (e: Exception) {} }
        audioRecordSistema = null
    }

    /** Lee los primeros 4 bytes de la conexión y confirma que el otro celular usa
     *  el mismo formato de audio (misma versión de la app). */
    private fun verificarEncabezado(): Boolean {
        val entrada = inputStream ?: return false
        val cabecera = ByteArray(MAGIC.size)
        var leidos = 0
        while (leidos < cabecera.size && conectado) {
            val n = entrada.read(cabecera, leidos, cabecera.size - leidos)
            if (n < 0) {
                if (conectado) {
                    callback.onError("El otro celular se desconectó")
                    detener()
                }
                return false
            }
            leidos += n
        }
        if (!conectado) return false
        if (!cabecera.contentEquals(MAGIC)) {
            callback.onError("El otro celular tiene una versión anterior de MotoIntercom. Actualiza los dos celulares.")
            detener()
            return false
        }
        return true
    }

    private fun iniciarRecepcionDeAudio() {
        // Buffer de reproducción de ~200 ms: aguanta pequeños tropiezos del
        // celular (por ejemplo, cuando Waze está calculando una ruta) sin que el
        // sonido se corte en pedazos, y sin acumular un retraso notorio.
        val bufferSize = maxOf(
            AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT),
            FRAME_BYTES * 10
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        audioTrack?.play()

        val miSesion = sesion
        hiloRecepcion = Thread {
            prioridadDeAudio()
            val recibido = ByteArray(FRAME_MUESTRAS * 10)
            val pcm = ByteArray(recibido.size * 2)
            try {
                if (verificarEncabezado()) {
                    while (conectado && sesion == miSesion) {
                        // Si se acumuló más audio del que corresponde (el enlace
                        // se atrasó), se descarta lo viejo para volver a quedar
                        // casi en tiempo real. Como cada muestra ocupa 1 byte, se
                        // puede descartar cualquier cantidad sin desalinear nada.
                        val acumulado = inputStream?.available() ?: 0
                        if (acumulado > MAX_ATRASO_BYTES) {
                            val saltados = inputStream?.skip((acumulado - FRAME_MUESTRAS * 3).toLong()) ?: 0L
                            bytesRecibidos += saltados
                        }

                        val leidos = inputStream?.read(recibido) ?: -1
                        if (leidos > 0) {
                            bytesRecibidos += leidos
                            // Descomprime: 1 byte -> 2 bytes (muestra de 16 bits).
                            for (k in 0 until leidos) {
                                val s = TABLA_MULAW_A_PCM[recibido[k].toInt() and 0xFF].toInt()
                                pcm[2 * k] = (s and 0xFF).toByte()
                                pcm[2 * k + 1] = ((s shr 8) and 0xFF).toByte()
                            }
                            aplicarGanancia(pcm, leidos * 2)
                            audioTrack?.write(pcm, 0, leidos * 2)
                        } else if (leidos == -1) {
                            if (conectado && sesion == miSesion) {
                                callback.onError("El otro celular se desconectó")
                                detener()
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (conectado && sesion == miSesion) {
                    callback.onError("Se perdió la conexión al recibir audio")
                    detener()
                }
            }
        }
        hiloRecepcion?.start()
    }

    fun alternarSilencio(): Boolean {
        silenciado = !silenciado
        return silenciado
    }

    /** A diferencia de alternarSilencio(), esto fija el estado exacto en vez de alternarlo.
     *  Lo usa el botón de walkie-talkie: se llama con "false" mientras se mantiene
     *  presionado (para hablar) y con "true" al soltar (para quedar en silencio otra vez). */
    fun establecerSilencio(valor: Boolean) {
        silenciado = valor
    }

    @Synchronized
    fun detener() {
        conectado = false
        sesion++
        hiloVigilante?.interrupt()
        hiloVigilante = null
        colaEnvio.clear()
        try { serverSocket?.close() } catch (e: IOException) {}
        try { socket?.close() } catch (e: IOException) {}
        try { controlServerSocket?.close() } catch (e: Exception) {}
        try { controlSocket?.close() } catch (e: Exception) {}
        controlEscritor = null
        controlServerSocket = null
        controlSocket = null
        audioRecord?.let { try { it.stop(); it.release() } catch (e: Exception) {} }
        audioTrack?.let { try { it.stop(); it.release() } catch (e: Exception) {} }
        audioRecord = null
        audioTrack = null
        soltarDucking()
        restaurarAudioNormal()
        serverSocket = null
        socket = null
    }
}
