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
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
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

    @Volatile private var conectado = false
    @Volatile private var silenciado = false

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
        try {
            controlSocket = socketDeControl
            controlEscritor = java.io.PrintWriter(socketDeControl?.outputStream, true)
            val lector = java.io.BufferedReader(java.io.InputStreamReader(socketDeControl?.inputStream))
            hiloControl = Thread {
                try {
                    while (true) {
                        val linea = lector.readLine() ?: break
                        callback.onComandoRadioRecibido(linea)
                    }
                } catch (e: Exception) {
                    // El canal de avisos se cerró; no afecta la llamada de voz.
                }
            }
            hiloControl?.start()
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
            conectado = true
            pedirDuckingDeOtrasApps()
            activarModoLlamada()
            maximizarVolumenDeLlamada()
            iniciarEnvioDeMicrofono()
            iniciarRecepcionDeAudio()
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

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(atributos)
            .setWillPauseWhenDucked(false)
            .build()

        audioManager.requestAudioFocus(focusRequest!!)
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
                if (usarAudifonosBluetooth && conectado) {
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
                if (dispositivoBt != null) {
                    audioManager.setCommunicationDevice(dispositivoBt)
                }
            } else if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
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

    @SuppressLint("MissingPermission")
    private fun iniciarEnvioDeMicrofono() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, bufferSize
        )
        audioRecord?.startRecording()

        hiloEnvio = Thread {
            val bufferMic = ByteArray(bufferSize)
            val bufferSistema = ByteArray(bufferSize)
            val bufferMezcla = ByteArray(bufferSize)
            while (conectado) {
                val leidosMic = audioRecord?.read(bufferMic, 0, bufferMic.size) ?: -1
                if (leidosMic > 0 && !silenciado) {
                    try {
                        if (compartiendoAudioDelSistema && audioRecordSistema != null) {
                            val leidosSistema = try {
                                audioRecordSistema?.read(bufferSistema, 0, bufferSistema.size) ?: 0
                            } catch (e: Exception) {
                                // Algunas apps (Spotify y otras de streaming) no permiten que se
                                // capture su audio. Si pasa esto, seguimos enviando solo la voz
                                // en vez de tumbar la llamada.
                                0
                            }
                            mezclarVozConMusica(bufferMic, bufferSistema, bufferMezcla, leidosMic, maxOf(leidosSistema, 0))
                            outputStream?.write(bufferMezcla, 0, leidosMic)
                        } else {
                            outputStream?.write(bufferMic, 0, leidosMic)
                        }
                    } catch (e: IOException) {
                        callback.onError("Se perdió la conexión al enviar audio")
                        detener()
                    }
                }
            }
        }
        hiloEnvio?.start()
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

    private fun iniciarRecepcionDeAudio() {
        val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT)
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

        hiloRecepcion = Thread {
            val buffer = ByteArray(bufferSize)
            while (conectado) {
                try {
                    // Si se acumuló más audio del que le cabe a 2 "paquetes" de sobra,
                    // es que vamos retrasados (por ejemplo, por saturación de la radio
                    // Bluetooth al usar audífonos + la conexión con el otro celular al
                    // mismo tiempo). En vez de dejar que el retraso crezca y crezca,
                    // se descarta lo viejo para volver a quedar casi en tiempo real.
                    val acumulado = inputStream?.available() ?: 0
                    if (acumulado > bufferSize * 3) {
                        inputStream?.skip((acumulado - bufferSize).toLong())
                    }

                    val leidos = inputStream?.read(buffer) ?: -1
                    if (leidos > 0) {
                        aplicarGanancia(buffer, leidos)
                        audioTrack?.write(buffer, 0, leidos)
                    } else if (leidos == -1) {
                        callback.onError("El otro celular se desconectó")
                        detener()
                    }
                } catch (e: IOException) {
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

    fun detener() {
        conectado = false
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
