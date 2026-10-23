package com.motointercom.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.io.File
import java.util.ArrayDeque

/**
 * Mantiene la sala por internet conectada y RECIBIENDO audios aunque:
 *  - se cierre la pantalla de la sala (por ejemplo, al darle "atrás"), o
 *  - se esté usando otra app (como WhatsApp) al mismo tiempo.
 *
 * Antes, todo esto vivía dentro de la pantalla (Activity), y Android la
 * "congela" en segundo plano — por eso dejaba de recibir audios. Al vivir
 * dentro de este servicio en primer plano, sigue funcionando igual que
 * cualquier llamada normal en segundo plano.
 */
class SalaInternetService : Service() {

    companion object {
        private const val CANAL_ID = "moto_intercom_sala_internet"
        private const val NOTI_ID = 4001
        private const val URL_BASE_DE_DATOS = "https://motointercom-1235a-default-rtdb.firebaseio.com/"
    }

    interface Escucha {
        fun onEstadoCambiado(mensaje: String)
        fun onParticipantesCambiaron(nombres: List<String>)
    }

    inner class SalaBinder : Binder() {
        fun obtenerServicio(): SalaInternetService = this@SalaInternetService
    }

    private val binder = SalaBinder()
    private var escucha: Escucha? = null

    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null

    var miId: String = ""
    private var nombre: String = "Motero"
    var codigoSalaActual: String? = null
        private set
    private var horaDeEntrada: Long = 0L
    private var listenerMensajes: ChildEventListener? = null
    private var consultaMensajes: Query? = null

    // Diferencia entre el reloj de ESTE celular y el reloj de Firebase. Los
    // mensajes traen la hora de Firebase; si el reloj del celular estaba
    // adelantado, antes se ignoraban los audios nuevos creyendo que eran viejos.
    @Volatile private var desfaseServidor = 0L
    private var listenerDesfase: ValueEventListener? = null

    // Mientras la pantalla de la sala está abierta no hace falta la burbuja
    // (ya está el botón grande de hablar). Igual que Didi/Uber: la burbuja
    // aparece solo cuando estás en OTRA app o en otra pantalla.
    private var pantallaDeSalaVisible = false
    private var listenerParticipantes: ChildEventListener? = null
    private val participantes = LinkedHashMap<String, String>() // id -> nombre

    private val colaReproduccion = ArrayDeque<String>()
    private var reproductor: MediaPlayer? = null
    private var reproduciendoAhora = false

    // ---------- Burbuja flotante (como Didi/Uber) ----------
    private var windowManager: WindowManager? = null
    private var vistaFlotante: TextView? = null
    private val manejadorFlotante = Handler(Looper.getMainLooper())
    private var pendienteDetenerFlotante: Runnable? = null

    private var grabadorFlotante: MediaRecorder? = null
    private var archivoFlotante: File? = null
    private var grabandoFlotante = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        crearCanalNotificacion()
        escucharDesfaseDelServidor()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * La pantalla arranca este servicio con startForegroundService(). Android
     * exige que, en ese caso, el servicio llame a startForeground() en pocos
     * segundos o CIERRA la app. Antes eso solo pasaba dentro de entrarASala(),
     * y si la entrada no ocurría (por ejemplo, si la pantalla todavía no estaba
     * conectada al servicio, o si se volvía a entrar a la misma sala) la app
     * se cerraba sola a los ~10 segundos.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iniciarPrimerPlano(codigoSalaActual?.let { "Sala $it" } ?: "Entrando a la sala...")
        if (codigoSalaActual == null) {
            // Si en 15 segundos no se entró a ninguna sala, se apaga solo.
            manejadorFlotante.postDelayed({
                if (codigoSalaActual == null) {
                    detenerPrimerPlano()
                    stopSelf()
                }
            }, 15000)
        }
        return START_NOT_STICKY
    }

    private fun escucharDesfaseDelServidor() {
        try {
            listenerDesfase = FirebaseDatabase.getInstance(URL_BASE_DE_DATOS)
                .getReference(".info/serverTimeOffset")
                .addValueEventListener(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        desfaseServidor = snapshot.getValue(Long::class.java) ?: 0L
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        } catch (e: Exception) {}
    }

    /** La pantalla de la sala avisa cuándo está a la vista y cuándo no. */
    fun establecerPantallaDeSalaVisible(visible: Boolean) {
        pantallaDeSalaVisible = visible
        if (visible) ocultarBurbuja() else mostrarBurbujaSiHaceFalta()
    }

    fun registrarEscucha(nuevaEscucha: Escucha?) {
        escucha = nuevaEscucha
        nuevaEscucha?.onParticipantesCambiaron(participantes.values.toList())
    }

    fun estaEnSala(): Boolean = codigoSalaActual != null

    /** Se llama cuando la pantalla vuelve a primer plano: si el usuario acaba
     *  de dar el permiso de "mostrar sobre otras apps", la burbuja aparece
     *  de una vez, sin tener que salir y volver a entrar a la sala. */
    fun mostrarBurbujaSiHaceFalta() {
        if (codigoSalaActual != null) mostrarBurbuja()
    }

    fun entrarASala(codigo: String, nombreUsuario: String, idUsuario: String) {
        if (codigoSalaActual == codigo) return // ya está adentro de esta misma sala
        if (codigoSalaActual != null) salirDeSala()

        miId = idUsuario
        nombre = nombreUsuario
        codigoSalaActual = codigo
        // Hora de Firebase (no la del celular), con 3 segundos de margen.
        horaDeEntrada = System.currentTimeMillis() + desfaseServidor - 3000

        iniciarPrimerPlano("Sala $codigo")
        adquirirWakeLock()
        pedirAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        registrarDeteccionDeAudifonos()
        intentarUsarAudifonosBluetooth()

        // Antes se escuchaba TODA la lista de mensajes: al entrar, el celular
        // descargaba todos los audios de las últimas 24 horas solo para
        // ignorarlos, lo que en datos móviles demoraba todo. Ahora solo se piden
        // los que llegan desde que se entró. (Para que Firebase filtre del lado
        // del servidor, en las reglas de la base de datos debe estar
        // ".indexOn": ["timestamp"] en salas/$codigo/mensajes.)
        val consulta = FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference
            .child("salas").child(codigo).child("mensajes")
            .orderByChild("timestamp").startAt(horaDeEntrada.toDouble())
        consultaMensajes = consulta

        listenerMensajes = consulta.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val remitenteId = snapshot.child("remitenteId").getValue(String::class.java) ?: return
                val remitenteNombre = snapshot.child("remitenteNombre").getValue(String::class.java) ?: "Alguien"
                val audioBase64 = snapshot.child("audioBase64").getValue(String::class.java) ?: return
                val marcaDeTiempo = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L

                if (remitenteId == miId) return
                if (marcaDeTiempo != 0L && marcaDeTiempo < horaDeEntrada) return

                notificarEstado("$remitenteNombre está hablando...")
                reproducirMensaje(audioBase64)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })

        notificarEstado("Conectado como $nombre. Mantén presionado para hablar.")

        registrarPresencia(codigo)
        limpiarMensajesViejos(codigo)
        mostrarBurbuja()
    }

    /**
     * Avisa a los demás que estoy en la sala, y me entero de quién más hay.
     * Usa onDisconnect() de Firebase: si se me cae la conexión, se apaga la
     * app, o me quedo sin señal, Firebase me borra solo de la lista — así los
     * demás saben que ya no estoy, sin que yo tenga que avisar a mano.
     */
    private fun registrarPresencia(codigo: String) {
        participantes.clear()
        val referenciaParticipantes = FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference
            .child("salas").child(codigo).child("participantes")

        val miReferencia = referenciaParticipantes.child(miId)
        miReferencia.setValue(mapOf("nombre" to nombre, "desde" to ServerValue.TIMESTAMP))
        miReferencia.onDisconnect().removeValue()

        listenerParticipantes = referenciaParticipantes.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val id = snapshot.key ?: return
                val nombreParticipante = snapshot.child("nombre").getValue(String::class.java) ?: "Alguien"
                val yaEstaba = participantes.containsKey(id)
                participantes[id] = nombreParticipante
                escucha?.onParticipantesCambiaron(participantes.values.toList())
                if (!yaEstaba && id != miId) {
                    notificarEstado("$nombreParticipante se unió a la sala")
                    sonidoAvisoSuave()
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.key ?: return
                val nombreParticipante = participantes.remove(id) ?: "Alguien"
                escucha?.onParticipantesCambiaron(participantes.values.toList())
                if (id != miId) {
                    notificarEstado("$nombreParticipante salió de la sala")
                    sonidoAvisoSuave()
                }
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    /** Un pitido corto y suave, solo para avisar sin sobresaltar. */
    private fun sonidoAvisoSuave() {
        try {
            val generador = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 40) // volumen bajo (0-100)
            generador.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ generador.release() }, 300)
        } catch (e: Exception) {}
    }

    /** Borra de Firebase los mensajes de audio con más de 24 horas, para que
     *  no quede un historial acumulándose para siempre. Como Realtime Database
     *  no tiene un borrado automático incluido (eso solo viene en planes de
     *  pago), esto se hace cada vez que alguien entra a la sala. */
    private fun limpiarMensajesViejos(codigo: String) {
        val limite = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        val referenciaMensajes = FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference
            .child("salas").child(codigo).child("mensajes")

        referenciaMensajes.orderByChild("timestamp").endAt(limite.toDouble())
            .get()
            .addOnSuccessListener { snapshot ->
                for (hijo in snapshot.children) {
                    hijo.ref.removeValue()
                }
            }
    }

    fun salirDeSala() {
        val codigo = codigoSalaActual
        if (listenerMensajes != null) {
            consultaMensajes?.removeEventListener(listenerMensajes!!)
        }
        consultaMensajes = null
        if (codigo != null) {
            val referenciaParticipantes = FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference
                .child("salas").child(codigo).child("participantes")
            if (listenerParticipantes != null) {
                referenciaParticipantes.removeEventListener(listenerParticipantes!!)
            }
            // Se borra a mano de una vez (no hay que esperar a que Firebase
            // detecte la desconexión) para que los demás se enteren al toque.
            referenciaParticipantes.child(miId).removeValue()
        }
        listenerParticipantes = null
        participantes.clear()
        listenerMensajes = null
        codigoSalaActual = null
        ocultarBurbuja()
        colaReproduccion.clear()
        reproductor?.let { try { it.stop(); it.release() } catch (e: Exception) {} }
        reproductor = null
        reproduciendoAhora = false

        try {
            desregistrarDeteccionDeAudifonos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.clearCommunicationDevice()
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {}

        soltarAudioFocus()
        soltarWakeLock()
        detenerPrimerPlano()
        // El servicio se había ARRANCADO de forma independiente al entrar a la
        // sala; ahora que se salió, se detiene solo (si no, seguiría corriendo
        // en segundo plano para siempre, gastando batería sin necesidad).
        stopSelf()
    }

    /** Sube un mensaje ya grabado (archivo .m4a) a la sala actual. */
    fun enviarMensaje(archivo: File, nombreRemitente: String, alTerminar: (exito: Boolean, error: String?) -> Unit) {
        val codigo = codigoSalaActual
        if (codigo == null) {
            alTerminar(false, "No estás en ninguna sala")
            return
        }
        Thread {
            try {
                val bytes = archivo.readBytes()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val datos = mapOf(
                    "remitenteId" to miId,
                    "remitenteNombre" to nombreRemitente,
                    "audioBase64" to base64,
                    "timestamp" to ServerValue.TIMESTAMP
                )
                FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference
                    .child("salas").child(codigo).child("mensajes").push()
                    .setValue(datos)
                    .addOnSuccessListener {
                        archivo.delete()
                        alTerminar(true, null)
                    }
                    .addOnFailureListener {
                        alTerminar(false, "Revisa tu conexión a internet")
                    }
            } catch (e: Exception) {
                alTerminar(false, e.message)
            }
        }.start()
    }

    /**
     * Escucha en tiempo real cuando se conecta o desconecta un dispositivo de
     * audio (como unos audífonos Bluetooth) MIENTRAS ya se está en la sala, y
     * cambia la ruta de audio sola, sin cortar nada.
     */
    private fun registrarDeteccionDeAudifonos() {
        if (audioDeviceCallback != null) return
        audioDeviceCallback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(dispositivosAgregados: Array<out android.media.AudioDeviceInfo>) {
                if (estaEnSala()) intentarUsarAudifonosBluetooth()
            }
            override fun onAudioDevicesRemoved(dispositivosQuitados: Array<out android.media.AudioDeviceInfo>) {
                // Si se desconectan, Android vuelve solo al auricular/altavoz.
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun desregistrarDeteccionDeAudifonos() {
        audioDeviceCallback?.let { try { audioManager.unregisterAudioDeviceCallback(it) } catch (e: Exception) {} }
        audioDeviceCallback = null
    }

    /** Enruta el audio hacia unos audífonos Bluetooth si hay unos conectados
     *  (en vez de salir por el parlante del celular). Usa la API moderna en
     *  Android 12+, y el método clásico (SCO) en versiones más viejas. */
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
            // No hay audífonos disponibles: se sigue escuchando por el
            // auricular o el altavoz del celular, sin problema.
        }
    }

    private fun pedirAudioFocus() {
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(atributos)
            .setWillPauseWhenDucked(false)
            .build()
        try { audioManager.requestAudioFocus(focusRequest!!) } catch (e: Exception) {}
    }

    private fun soltarAudioFocus() {
        focusRequest?.let { try { audioManager.abandonAudioFocusRequest(it) } catch (e: Exception) {} }
        focusRequest = null
    }

    // ---------- Recibir y reproducir ----------

    private fun reproducirMensaje(audioBase64: String) {
        colaReproduccion.add(audioBase64)
        reproducirSiguienteSiLibre()
    }

    private fun reproducirSiguienteSiLibre() {
        if (reproduciendoAhora) return
        val siguiente = colaReproduccion.poll() ?: return
        reproduciendoAhora = true

        Thread {
            // Solo la decodificación va en otro hilo; el reproductor y la fila se
            // manejan siempre en el hilo principal para que no se crucen dos audios.
            try {
                val bytes = android.util.Base64.decode(siguiente, android.util.Base64.NO_WRAP)
                val archivo = File(cacheDir, "recibido_${System.currentTimeMillis()}.m4a")
                archivo.writeBytes(bytes)
                manejadorFlotante.post { reproducirArchivo(archivo) }
            } catch (e: Exception) {
                manejadorFlotante.post {
                    reproduciendoAhora = false
                    reproducirSiguienteSiLibre()
                }
            }
        }.start()
    }

    private fun reproducirArchivo(archivo: File) {
        reproductor = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                it.release()
                reproductor = null
                reproduciendoAhora = false
                archivo.delete()
                if (colaReproduccion.isEmpty()) {
                    notificarEstado("Mantén presionado para hablar")
                }
                reproducirSiguienteSiLibre()
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                reproductor = null
                reproduciendoAhora = false
                archivo.delete()
                reproducirSiguienteSiLibre()
                true
            }
            try {
                setDataSource(archivo.absolutePath)
                prepareAsync()
            } catch (e: Exception) {
                try { release() } catch (e2: Exception) {}
                reproductor = null
                reproduciendoAhora = false
                archivo.delete()
                reproducirSiguienteSiLibre()
            }
        }
    }

    // ---------- Burbuja flotante (estilo Didi/Uber) ----------

    /** Muestra el botón flotante SOLO si el usuario ya dio el permiso de
     *  "Mostrar sobre otras apps" (la pantalla se encarga de pedirlo). Si no
     *  lo dio, simplemente no aparece y la app sigue funcionando normal. */
    @SuppressLint("ClickableViewAccessibility")
    private fun mostrarBurbuja() {
        if (vistaFlotante != null) return
        if (codigoSalaActual == null || pantallaDeSalaVisible) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val tamano = (64 * resources.displayMetrics.density).toInt()

            val boton = TextView(this).apply {
                text = "\uD83C\uDF99\uFE0F"
                textSize = 26f
                gravity = Gravity.CENTER
                background = dibujoBurbuja(false)
            }

            val tipoVentana = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                tamano, tamano, tipoVentana,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 0
            params.y = 300

            var xInicial = 0
            var yInicial = 0
            var toqueXInicial = 0f
            var toqueYInicial = 0f
            var seMovio = false

            boton.setOnTouchListener { vista, evento ->
                when (evento.action) {
                    MotionEvent.ACTION_DOWN -> {
                        xInicial = params.x
                        yInicial = params.y
                        toqueXInicial = evento.rawX
                        toqueYInicial = evento.rawY
                        seMovio = false
                        pendienteDetenerFlotante?.let { manejadorFlotante.removeCallbacks(it) }
                        pendienteDetenerFlotante = null
                        iniciarGrabacionFlotante()
                        (vista as TextView).background = dibujoBurbuja(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (evento.rawX - toqueXInicial).toInt()
                        val dy = (evento.rawY - toqueYInicial).toInt()
                        if (!seMovio && (Math.abs(dx) > 28 || Math.abs(dy) > 28)) {
                            // Se volvió un arrastre: se cancela la grabación
                            // (no se manda nada a medias) y se mueve la burbuja.
                            seMovio = true
                            cancelarGrabacionFlotante()
                            (vista as TextView).background = dibujoBurbuja(false)
                        }
                        if (seMovio) {
                            params.x = xInicial + dx
                            params.y = yInicial + dy
                            try { windowManager?.updateViewLayout(vista, params) } catch (e: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!seMovio) {
                            (vista as TextView).background = dibujoBurbuja(false)
                            // Mismo colchón de 1 segundo que el botón normal.
                            val corte = Runnable {
                                pendienteDetenerFlotante = null
                                detenerYEnviarGrabacionFlotante()
                            }
                            pendienteDetenerFlotante = corte
                            manejadorFlotante.postDelayed(corte, 1000)
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(boton, params)
            vistaFlotante = boton
        } catch (e: Exception) {
            // Si no se puede mostrar (fabricante raro, etc.), la app sigue
            // funcionando igual, solo sin la burbuja.
            vistaFlotante = null
        }
    }

    private fun dibujoBurbuja(grabando: Boolean): GradientDrawable {
        val densidad = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (grabando) "#0D84C4" else "#1C1F24"))
            setStroke((3 * densidad).toInt(), Color.parseColor(if (grabando) "#3DDC84" else "#29B6FF"))
        }
    }

    private fun ocultarBurbuja() {
        // Si se acababa de soltar la burbuja (está en el segundo de colchón),
        // ese audio se manda de una vez en vez de perderse.
        val pendiente = pendienteDetenerFlotante
        if (pendiente != null) {
            manejadorFlotante.removeCallbacks(pendiente)
            pendienteDetenerFlotante = null
            detenerYEnviarGrabacionFlotante()
        } else {
            cancelarGrabacionFlotante()
        }
        vistaFlotante?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        vistaFlotante = null
    }

    @SuppressLint("MissingPermission")
    private fun iniciarGrabacionFlotante() {
        if (grabandoFlotante || codigoSalaActual == null) return
        try {
            archivoFlotante = File(cacheDir, "msg_flotante_${System.currentTimeMillis()}.m4a")
            grabadorFlotante = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            grabadorFlotante?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(24000)
                setAudioSamplingRate(16000)
                setOutputFile(archivoFlotante!!.absolutePath)
                prepare()
                start()
            }
            grabandoFlotante = true
            notificarEstado("Hablando desde la burbuja flotante...")
        } catch (e: Exception) {
            grabandoFlotante = false
        }
    }

    private fun cancelarGrabacionFlotante() {
        if (!grabandoFlotante) return
        grabandoFlotante = false
        try { grabadorFlotante?.stop() } catch (e: Exception) {}
        try { grabadorFlotante?.release() } catch (e: Exception) {}
        grabadorFlotante = null
        archivoFlotante?.delete()
        archivoFlotante = null
    }

    private fun detenerYEnviarGrabacionFlotante() {
        if (!grabandoFlotante) return
        grabandoFlotante = false
        try { grabadorFlotante?.stop() } catch (e: Exception) {}
        try { grabadorFlotante?.release() } catch (e: Exception) {}
        grabadorFlotante = null

        val archivo = archivoFlotante ?: return
        archivoFlotante = null
        if (archivo.length() < 500) {
            notificarEstado("Mantén presionado para hablar")
            return
        }

        notificarEstado("Enviando...")
        enviarMensaje(archivo, nombre) { exito, error ->
            notificarEstado(if (exito) "Mantén presionado para hablar" else (error ?: "No se pudo enviar"))
        }
    }

    // ---------- Notificación / primer plano / wake lock ----------

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(CANAL_ID, "Sala por internet", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun iniciarPrimerPlano(texto: String) {
        val tipos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, construirNotificacion(texto), tipos)
        } else {
            startForeground(NOTI_ID, construirNotificacion(texto))
        }
    }

    private fun detenerPrimerPlano() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun construirNotificacion(texto: String): Notification {
        val abrirApp = PendingIntent.getActivity(
            this, 0, Intent(this, SalaInternetActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CANAL_ID)
            .setContentTitle("MotoIntercom · Sala por internet")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(abrirApp)
            .setOngoing(true)
            .build()
    }

    private fun notificarEstado(texto: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTI_ID, construirNotificacion(texto))
        escucha?.onEstadoCambiado(texto)
    }

    @SuppressLint("WakelockTimeout")
    private fun adquirirWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "MotoIntercom::salaInternet"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {}
    }

    private fun soltarWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        manejadorFlotante.removeCallbacksAndMessages(null)
        listenerDesfase?.let {
            try {
                FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).getReference(".info/serverTimeOffset").removeEventListener(it)
            } catch (e: Exception) {}
        }
        listenerDesfase = null
        salirDeSala()
        super.onDestroy()
    }
}
