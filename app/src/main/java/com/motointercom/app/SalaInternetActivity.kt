package com.motointercom.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.util.ArrayDeque
import java.util.UUID

/**
 * Walkie-talkie por internet: no es una llamada en tiempo real, es por turnos
 * (como un radio de verdad). Mantienes presionado el botón para grabar, sueltas
 * y el mensaje se sube a internet; los demás en la misma sala lo reciben y
 * se reproduce solo. Funciona en cualquier red (WiFi o datos móviles).
 *
 * El audio se manda como texto (Base64) directo dentro de la Realtime Database,
 * en vez de subirlo a Firebase Storage — así no hace falta tener el plan de
 * pago (Blaze) para usar esta función, ya que los mensajes son cortos.
 */
class SalaInternetActivity : AppCompatActivity() {

    companion object {
        private const val DURACION_MAXIMA_MS = 20_000L // 20 segundos por mensaje, como máximo
        // Dirección fija de la Realtime Database, para que funcione aunque el
        // archivo google-services.json no la traiga incluida.
        private const val URL_BASE_DE_DATOS = "https://motointercom-1235a-default-rtdb.firebaseio.com/"
        // Landing page pública: siempre se puede abrir, tenga o no la app instalada.
        // Si la tiene, la página la redirige sola a la sala; si no, ahí mismo
        // puede descargar el APK.
        private const val URL_LANDING_PAGE = "https://pruebasjuanpablogerena.github.io/motointercom/"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var miId: String

    private lateinit var panelAntesDeConectar: View
    private lateinit var panelConectado: View
    private lateinit var editMiNombre: EditText
    private lateinit var editCodigoUnirse: EditText
    private lateinit var btnCrearSala: Button
    private lateinit var btnUnirseSala: Button
    private lateinit var txtCodigoActual: TextView
    private lateinit var txtEstadoInternet: TextView
    private lateinit var btnHablarInternet: Button
    private lateinit var btnSalirSala: Button
    private lateinit var btnAltavozInternet: Button
    private lateinit var seekVolumenInternet: SeekBar
    private lateinit var imgQrSala: ImageView
    private lateinit var btnCompartirCodigo: Button

    private lateinit var audioManager: AudioManager
    private var altavozActivo = false

    private var codigoSalaActual: String? = null
    private var horaDeEntrada: Long = 0L
    private var listenerMensajes: ChildEventListener? = null

    private var grabador: MediaRecorder? = null
    private var archivoTemporal: File? = null
    private var grabando = false
    private val manejador = Handler(Looper.getMainLooper())
    private val corteAutomatico = Runnable { detenerYEnviarGrabacion() }

    private val colaReproduccion = ArrayDeque<String>()
    private var reproductor: MediaPlayer? = null
    private var reproduciendoAhora = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sala_internet)

        prefs = getSharedPreferences("moto_intercom_prefs", MODE_PRIVATE)
        miId = prefs.getString("mi_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("mi_id", it).apply()
        }

        panelAntesDeConectar = findViewById(R.id.panelAntesDeConectar)
        panelConectado = findViewById(R.id.panelConectado)
        editMiNombre = findViewById(R.id.editMiNombre)
        editCodigoUnirse = findViewById(R.id.editCodigoUnirse)
        btnCrearSala = findViewById(R.id.btnCrearSala)
        btnUnirseSala = findViewById(R.id.btnUnirseSala)
        txtCodigoActual = findViewById(R.id.txtCodigoActual)
        txtEstadoInternet = findViewById(R.id.txtEstadoInternet)
        btnHablarInternet = findViewById(R.id.btnHablarInternet)
        btnSalirSala = findViewById(R.id.btnSalirSala)
        btnAltavozInternet = findViewById(R.id.btnAltavozInternet)
        seekVolumenInternet = findViewById(R.id.seekVolumenInternet)
        imgQrSala = findViewById(R.id.imgQrSala)
        btnCompartirCodigo = findViewById(R.id.btnCompartirCodigo)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // "Modo llamada": permite que Android enrute bien el sonido hacia el
        // auricular o el altavoz, y que el control de volumen de esta pantalla
        // funcione sobre el volumen de llamada (no el de música).
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        try {
            val maximo = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maximo * 0.7).toInt(), 0)
        } catch (e: Exception) {}

        editMiNombre.setText(prefs.getString("mi_nombre", "Motero"))

        btnCrearSala.setOnClickListener { crearSala() }
        btnUnirseSala.setOnClickListener {
            val codigo = editCodigoUnirse.text.toString().trim()
            if (codigo.length < 4) {
                Toast.makeText(this, "Escribe el código completo", Toast.LENGTH_SHORT).show()
            } else {
                entrarASala(codigo.uppercase())
            }
        }
        btnSalirSala.setOnClickListener { salirDeSala() }

        btnHablarInternet.setOnTouchListener { boton, evento ->
            when (evento.action) {
                MotionEvent.ACTION_DOWN -> {
                    iniciarGrabacion()
                    boton.setBackgroundResource(R.drawable.bg_boton_ptt_redondo_hablando)
                    iniciarPulso(boton)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    detenerYEnviarGrabacion()
                    boton.setBackgroundResource(R.drawable.bg_boton_ptt_redondo)
                    detenerPulso(boton)
                    true
                }
                else -> false
            }
        }

        btnAltavozInternet.setOnClickListener {
            altavozActivo = !altavozActivo
            activarAltavoz(altavozActivo)
            btnAltavozInternet.text = if (altavozActivo) "Altavoz: encendido" else "Altavoz: apagado"
            btnAltavozInternet.setBackgroundResource(if (altavozActivo) R.drawable.bg_boton_encendido else R.drawable.bg_boton_apagado)
        }

        seekVolumenInternet.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                try {
                    val maximo = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    val nivel = ((progress / 100f) * maximo).toInt().coerceAtLeast(0)
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, nivel, 0)
                } catch (e: Exception) {}
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnCompartirCodigo.setOnClickListener { compartirCodigo() }

        mostrarPanelDesconectado()
        manejarEnlaceDeEntrada(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        manejarEnlaceDeEntrada(intent)
    }

    /** Si la app se abrió al escanear el código QR de una sala, entra directo a ella. */
    private fun manejarEnlaceDeEntrada(intent: Intent?) {
        val datos = intent?.data ?: return
        if (datos.scheme == "motointercom" && datos.host == "sala") {
            val codigo = datos.lastPathSegment?.uppercase()?.trim()
            if (!codigo.isNullOrBlank() && codigo.length >= 4) {
                editCodigoUnirse.setText(codigo)
                entrarASala(codigo)
            }
        }
    }

    /** Genera el código QR de la sala. Apunta a la landing page (que siempre
     *  abre, tenga o no la app), y esa página redirige sola hacia la app si
     *  ya está instalada, o hacia la descarga si no lo está. */
    private fun generarQr(codigo: String) {
        try {
            val contenido = "$URL_LANDING_PAGE?sala=$codigo"
            val tamano = 600
            val matriz = QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, tamano, tamano)
            val bitmap = Bitmap.createBitmap(tamano, tamano, Bitmap.Config.RGB_565)
            for (x in 0 until tamano) {
                for (y in 0 until tamano) {
                    bitmap.setPixel(x, y, if (matriz.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            imgQrSala.setImageBitmap(bitmap)
        } catch (e: Exception) {
            // Si por lo que sea no se puede generar el QR, no es grave: queda
            // el código de texto y el botón de compartir, que sí funcionan igual.
        }
    }

    /** Abre el selector de apps de Android (WhatsApp, Mensajes, Telegram, etc.)
     *  para compartir el código de la sala. */
    private fun compartirCodigo() {
        val codigo = codigoSalaActual ?: return
        val mensaje = "Únete a mi sala de MotoIntercom con el código: $codigo\n\n" +
            "$URL_LANDING_PAGE?sala=$codigo"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, mensaje)
        }
        startActivity(Intent.createChooser(intent, "Compartir código de la sala"))
    }

    /** Cambia entre el altavoz y el auricular del celular durante la sala por internet. */
    private fun activarAltavoz(activar: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val tipoBuscado = if (activar) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val dispositivo = audioManager.availableCommunicationDevices.firstOrNull { it.type == tipoBuscado }
                if (dispositivo != null) audioManager.setCommunicationDevice(dispositivo)
            } catch (e: Exception) {}
        }
        audioManager.isSpeakerphoneOn = activar
    }

    private fun guardarNombre(): String {
        val nombre = editMiNombre.text.toString().trim().ifBlank { "Motero" }
        prefs.edit().putString("mi_nombre", nombre).apply()
        return nombre
    }

    private fun generarCodigo(): String {
        // Sin 0/O ni 1/I, para que no se confundan al leerlo en voz alta.
        val caracteres = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { caracteres.random() }.joinToString("")
    }

    private fun crearSala() {
        guardarNombre()
        val codigo = generarCodigo()
        val referencia = FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference.child("salas").child(codigo)
        referencia.child("creada").setValue(ServerValue.TIMESTAMP)
        entrarASala(codigo)
    }

    private fun entrarASala(codigo: String) {
        val nombre = guardarNombre()
        codigoSalaActual = codigo
        horaDeEntrada = System.currentTimeMillis()

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

                runOnUiThread {
                    txtEstadoInternet.text = "$remitenteNombre está hablando..."
                    reproducirMensaje(audioBase64)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })

        txtCodigoActual.text = "Sala: $codigo"
        txtEstadoInternet.text = "Conectado como $nombre. Mantén presionado para hablar."
        generarQr(codigo)
        mostrarPanelConectado()
    }

    private fun salirDeSala() {
        val codigo = codigoSalaActual
        if (codigo != null && listenerMensajes != null) {
            FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference
                .child("salas").child(codigo).child("mensajes")
                .removeEventListener(listenerMensajes!!)
        }
        listenerMensajes = null
        codigoSalaActual = null
        manejador.removeCallbacks(corteAutomatico)
        colaReproduccion.clear()
        reproductor?.let { try { it.stop(); it.release() } catch (e: Exception) {} }
        reproductor = null
        reproduciendoAhora = false
        mostrarPanelDesconectado()
    }

    private fun mostrarPanelConectado() {
        panelAntesDeConectar.visibility = View.GONE
        panelConectado.visibility = View.VISIBLE
    }

    private fun mostrarPanelDesconectado() {
        panelAntesDeConectar.visibility = View.VISIBLE
        panelConectado.visibility = View.GONE
    }

    // ---------- Grabar y enviar ----------

    private fun iniciarGrabacion() {
        if (grabando) return
        val codigo = codigoSalaActual ?: return
        try {
            archivoTemporal = File(cacheDir, "msg_${System.currentTimeMillis()}.m4a")
            grabador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            grabador?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(24000)
                setAudioSamplingRate(16000)
                setOutputFile(archivoTemporal!!.absolutePath)
                prepare()
                start()
            }
            grabando = true
            btnHablarInternet.text = "Grabando... suelta para enviar"
            txtEstadoInternet.text = "Hablando en sala $codigo"
            manejador.postDelayed(corteAutomatico, DURACION_MAXIMA_MS)
        } catch (e: Exception) {
            grabando = false
            Toast.makeText(this, "No se pudo grabar: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detenerYEnviarGrabacion() {
        if (!grabando) return
        grabando = false
        manejador.removeCallbacks(corteAutomatico)
        btnHablarInternet.text = "Mantén presionado para hablar"
        try {
            grabador?.stop()
        } catch (e: Exception) {
            // Grabación muy corta o sin audio; se descarta silenciosamente.
        }
        grabador?.release()
        grabador = null

        val archivo = archivoTemporal ?: return
        val codigo = codigoSalaActual ?: return
        if (archivo.length() < 500) return // grabación demasiado corta, probablemente un toque accidental

        subirMensaje(archivo, codigo)
    }

    private fun subirMensaje(archivo: File, codigo: String) {
        txtEstadoInternet.text = "Enviando..."
        val nombre = editMiNombre.text.toString().trim().ifBlank { "Motero" }

        Thread {
            try {
                val bytes = archivo.readBytes()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val datos = mapOf(
                    "remitenteId" to miId,
                    "remitenteNombre" to nombre,
                    "audioBase64" to base64,
                    "timestamp" to ServerValue.TIMESTAMP
                )
                FirebaseDatabase.getInstance(URL_BASE_DE_DATOS).reference
                    .child("salas").child(codigo).child("mensajes").push()
                    .setValue(datos)
                    .addOnSuccessListener {
                        runOnUiThread { txtEstadoInternet.text = "Mantén presionado para hablar" }
                        archivo.delete()
                    }
                    .addOnFailureListener {
                        runOnUiThread {
                            Toast.makeText(this, "No se pudo enviar: revisa tu conexión a internet", Toast.LENGTH_SHORT).show()
                            txtEstadoInternet.text = "Mantén presionado para hablar"
                        }
                    }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "No se pudo preparar el mensaje: ${e.message}", Toast.LENGTH_SHORT).show()
                    txtEstadoInternet.text = "Mantén presionado para hablar"
                }
            }
        }.start()
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
                runOnUiThread { reproducirArchivo(archivo) }
            } catch (e: Exception) {
                runOnUiThread {
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
                    runOnUiThread { txtEstadoInternet.text = "Mantén presionado para hablar" }
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

    /** Mismo efecto de "pulso" que en el modo walkie-talkie por Bluetooth, para
     *  que se note claramente cuándo se está grabando y transmitiendo. */
    private fun iniciarPulso(vista: View) {
        detenerPulso(vista)
        val escalaX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f)
        val escalaY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f)
        val animador = ObjectAnimator.ofPropertyValuesHolder(vista, escalaX, escalaY)
        animador.duration = 380
        animador.repeatMode = ObjectAnimator.REVERSE
        animador.repeatCount = ObjectAnimator.INFINITE
        animador.start()
        vista.tag = animador
    }

    private fun detenerPulso(vista: View) {
        (vista.tag as? ObjectAnimator)?.cancel()
        vista.tag = null
        vista.scaleX = 1f
        vista.scaleY = 1f
    }

    override fun onDestroy() {
        salirDeSala()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
