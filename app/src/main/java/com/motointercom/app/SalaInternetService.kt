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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
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
    private var listenerParticipantes: ChildEventListener? = null
    private val participantes = LinkedHashMap<String, String>() // id -> nombre

    private val colaReproduccion = ArrayDeque<String>()
    private var reproductor: MediaPlayer? = null
    private var reproduciendoAhora = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        crearCanalNotificacion()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun registrarEscucha(nuevaEscucha: Escucha?) {
        escucha = nuevaEscucha
        nuevaEscucha?.onParticipantesCambiaron(participantes.values.toList())
    }

    fun estaEnSala(): Boolean = codigoSalaActual != null

    fun entrarASala(codigo: String, nombreUsuario: String, idUsuario: String) {
        if (codigoSalaActual == codigo) return // ya está adentro de esta misma sala
        if (codigoSalaActual != null) salirDeSala()

        miId = idUsuario
        nombre = nombreUsuario
        codigoSalaActual = codigo
        horaDeEntrada = System.currentTimeMillis()

        iniciarPrimerPlano(codigo)
        adquirirWakeLock()
        pedirAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        registrarDeteccionDeAudifonos()
        intentarUsarAudifonosBluetooth()

        val referenciaMensajes = FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference
            .child("salas").child(codigo).child("mensajes")

        listenerMensajes = referenciaMensajes.addChildEventListener(object : ChildEventListener {
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
        if (codigo != null && listenerMensajes != null) {
            FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference
                .child("salas").child(codigo).child("mensajes")
                .removeEventListener(listenerMensajes!!)
        }
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
            try {
                val bytes = android.util.Base64.decode(siguiente, android.util.Base64.NO_WRAP)
                val archivo = File(cacheDir, "recibido_${System.currentTimeMillis()}.m4a")
                archivo.writeBytes(bytes)
                reproducirArchivo(archivo)
            } catch (e: Exception) {
                reproduciendoAhora = false
                reproducirSiguienteSiLibre()
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
                reproduciendoAhora = false
            }
        }
    }

    // ---------- Notificación / primer plano / wake lock ----------

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(CANAL_ID, "Sala por internet", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun iniciarPrimerPlano(codigo: String) {
        val tipos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, construirNotificacion("Sala $codigo"), tipos)
        } else {
            startForeground(NOTI_ID, construirNotificacion("Sala $codigo"))
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
        salirDeSala()
        super.onDestroy()
    }
}
