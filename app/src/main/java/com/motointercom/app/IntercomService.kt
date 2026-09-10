package com.motointercom.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servicio en primer plano que mantiene viva la llamada (conexión Bluetooth
 * con el otro celular + micrófono) aunque la pantalla de MotoIntercom pase
 * a segundo plano. Antes, al abrir Spotify para compartir música, Android
 * le quitaba el acceso al micrófono a la app (por estar en segundo plano)
 * y la llamada se cortaba. Al correr todo dentro de este servicio, eso ya
 * no pasa.
 */
class IntercomService : Service(), BluetoothAudioService.Callback {

    companion object {
        private const val CANAL_ID = "moto_intercom_llamada"
        private const val NOTI_ID = 2001
    }

    interface Escucha {
        fun onEstadoCambiado(mensaje: String)
        fun onError(mensaje: String)
    }

    inner class IntercomBinder : Binder() {
        fun obtenerServicio(): IntercomService = this@IntercomService
    }

    private val binder = IntercomBinder()
    private var escucha: Escucha? = null
    private lateinit var audioManager: AudioManager
    private lateinit var motor: BluetoothAudioService
    private var enPrimerPlano = false
    private var ultimoEstado = "Desconectado"
    private var wakeLock: PowerManager.WakeLock? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    /** La pantalla de radio se conecta aquí para recibir avisos del otro celular
     *  (por ejemplo, qué emisora sintonizar). No forma parte de Escucha para no
     *  obligar a MainActivity a implementarlo también. */
    var oyenteComandoRadio: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        motor = BluetoothAudioService(audioManager, this)
        crearCanalNotificacion()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun registrarEscucha(nuevaEscucha: Escucha?) {
        escucha = nuevaEscucha
        nuevaEscucha?.onEstadoCambiado(ultimoEstado)
    }

    @SuppressLint("MissingPermission")
    fun iniciarComoServidor(adapter: BluetoothAdapter) {
        iniciarPrimerPlano()
        motor.iniciarComoServidor(adapter)
    }

    @SuppressLint("MissingPermission")
    fun conectarComoCliente(dispositivo: BluetoothDevice) {
        iniciarPrimerPlano()
        motor.conectarComoCliente(dispositivo)
    }

    fun alternarSilencio(): Boolean = motor.alternarSilencio()

    fun establecerSilencio(valor: Boolean) = motor.establecerSilencio(valor)

    fun establecerUsoDeAudifonosBluetooth(usar: Boolean) = motor.establecerUsoDeAudifonosBluetooth(usar)

    fun activarAltavoz(activar: Boolean) = motor.activarAltavoz(activar)

    fun establecerGanancia(factor: Float) = motor.establecerGanancia(factor)

    /** Manda un aviso al otro celular (por ejemplo, qué emisora sintonizar),
     *  sin tocar para nada el canal de voz. */
    fun enviarComandoRadio(comando: String) = motor.enviarComandoRadio(comando)

    /**
     * IMPORTANTE: el tipo "mediaProjection" del servicio en primer plano solo se puede
     * declarar en el momento en que ya existe un permiso de captura activo (a partir de
     * Android 14, declararlo antes de tiempo hace que el sistema cierre la app). Por eso
     * se agrega ese tipo únicamente aquí, cuando ya se recibió el permiso, y no desde el
     * inicio de la llamada.
     */
    fun activarCompartirMusica(projection: MediaProjection) {
        iniciarPrimerPlano(incluirMediaProjection = true)
        motor.activarCompartirAudioDelSistema(projection)
    }

    fun desactivarCompartirMusica() {
        motor.desactivarCompartirAudioDelSistema()
        if (enPrimerPlano) iniciarPrimerPlano(incluirMediaProjection = false)
    }

    fun detener() {
        motor.detener()
        detenerPrimerPlano()
    }

    private fun iniciarPrimerPlano(incluirMediaProjection: Boolean = false) {
        enPrimerPlano = true
        val tipos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (incluirMediaProjection) {
                t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            t
        } else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTI_ID, construirNotificacion(ultimoEstado), tipos)
        } else {
            startForeground(NOTI_ID, construirNotificacion(ultimoEstado))
        }
        adquirirWakeLock()
        adquirirSensorDeProximidad()
    }

    /**
     * Evita que el celular "duerma" el proceso de la app mientras hay una llamada
     * activa. En varios fabricantes (Xiaomi, Samsung, Huawei...) el administrador
     * de batería congela apps en segundo plano incluso teniendo un servicio en
     * primer plano; este wake lock ayuda a que eso no corte la conexión Bluetooth.
     */
    private fun adquirirWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "MotoIntercom::llamada"
        ).apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L) // máximo 2 horas seguidas, por seguridad
        }
    }

    private fun soltarWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * Apaga la pantalla automáticamente cuando el sensor de proximidad detecta
     * que el celular está cerca de la oreja (como en una llamada normal), y la
     * vuelve a encender al alejarlo. Solo funciona en celulares que traen este
     * tipo de sensor de "proximidad para llamadas"; si no lo tienen, no hace nada.
     */
    private fun adquirirSensorDeProximidad() {
        if (proximityWakeLock?.isHeld == true) return
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "MotoIntercom::proximidad"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {}
    }

    private fun soltarSensorDeProximidad() {
        proximityWakeLock?.let { if (it.isHeld) it.release() }
        proximityWakeLock = null
    }

    private fun detenerPrimerPlano() {
        enPrimerPlano = false
        soltarWakeLock()
        soltarSensorDeProximidad()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onEstadoCambiado(mensaje: String) {
        ultimoEstado = mensaje
        if (enPrimerPlano) actualizarNotificacion(mensaje)
        escucha?.onEstadoCambiado(mensaje)
    }

    override fun onError(mensaje: String) {
        ultimoEstado = mensaje
        if (enPrimerPlano) actualizarNotificacion(mensaje)
        escucha?.onError(mensaje)
    }

    override fun onComandoRadioRecibido(comando: String) {
        oyenteComandoRadio?.invoke(comando)
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(CANAL_ID, "Llamada MotoIntercom", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun construirNotificacion(texto: String): Notification {
        val abrirApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CANAL_ID)
            .setContentTitle("MotoIntercom")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(abrirApp)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTI_ID, construirNotificacion(texto))
    }

    override fun onDestroy() {
        motor.detener()
        soltarWakeLock()
        soltarSensorDeProximidad()
        super.onDestroy()
    }
}
