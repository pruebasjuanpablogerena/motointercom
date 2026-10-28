package com.motointercom.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.util.UUID

/**
 * Walkie-talkie por internet: no es una llamada en tiempo real, es por turnos
 * (como un radio de verdad). Mantienes presionado el botón para grabar, sueltas
 * y el mensaje se sube a internet; los demás en la misma sala lo reciben y
 * se reproduce solo. Funciona en cualquier red (WiFi o datos móviles).
 *
 * La conexión a la sala y la recepción de audios viven en SalaInternetService
 * (un servicio en primer plano), NO en esta pantalla. Por eso cerrar esta
 * pantalla (o darle "atrás") no te saca de la sala, y sigues recibiendo
 * audios aunque estés usando otra app.
 */
class SalaInternetActivity : AppCompatActivity(), SalaInternetService.Escucha {

    companion object {
        private const val DURACION_MAXIMA_MS = 20_000L
        private const val URL_BASE_DE_DATOS = "https://motointercom-1235a-default-rtdb.firebaseio.com/"
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
    private lateinit var btnLeerQr: Button
    private lateinit var btnRadioSala: Button
    private lateinit var txtCodigoActual: TextView
    private lateinit var txtEstadoInternet: TextView
    private lateinit var txtParticipantes: TextView
    private lateinit var btnHablarInternet: Button
    private lateinit var btnSalirSala: Button
    private lateinit var btnAltavozInternet: Button
    private lateinit var seekVolumenInternet: SeekBar
    private lateinit var imgQrSala: ImageView
    private lateinit var btnCompartirCodigo: Button

    private lateinit var audioManager: AudioManager
    private var altavozActivo = false

    private var servicio: SalaInternetService? = null
    private var servicioConectado = false
    private var pantallaVisible = false
    // Si se pide entrar a una sala antes de que la pantalla termine de
    // conectarse con el servicio, se guarda aquí y se entra apenas conecte.
    private var codigoPendiente: String? = null

    // Los permisos se piden cuando se van a usar y explicando para qué.
    private val permisos = PedidorDePermisos(this)

    private var grabador: MediaRecorder? = null
    private var archivoTemporal: File? = null
    private var grabando = false
    private val manejador = Handler(Looper.getMainLooper())
    private val corteAutomatico = Runnable { detenerYEnviarGrabacion() }
    private var pendienteDetener: Runnable? = null

    // Abre la cámara para leer el QR de una sala. Cuando lo lee, entra directo.
    private val lectorQr = registerForActivityResult(ScanContract()) { resultado ->
        val texto = resultado.contents ?: return@registerForActivityResult // se canceló
        val codigo = extraerCodigoDeSala(texto)
        if (codigo == null) {
            Toast.makeText(this, "Ese QR no es de una sala de MotoIntercom", Toast.LENGTH_LONG).show()
        } else {
            editCodigoUnirse.setText(codigo)
            entrarASala(codigo)
        }
    }

    private val conexionServicio = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as SalaInternetService.SalaBinder
            servicio = b.obtenerServicio()
            servicioConectado = true
            servicio?.registrarEscucha(this@SalaInternetActivity)
            if (pantallaVisible) servicio?.establecerPantallaDeSalaVisible(true)

            // Si ya estábamos en una sala (por ejemplo, volviste a abrir la app
            // después de darle "atrás"), la pantalla se pone al día sola, sin
            // tener que volver a conectarse.
            val codigo = servicio?.codigoSalaActual
            if (codigo != null) {
                txtCodigoActual.text = "Sala: $codigo"
                generarQr(codigo)
                mostrarPanelConectado()
            } else {
                mostrarPanelDesconectado()
            }
            manejarEnlaceDeEntrada(intent)
            codigoPendiente?.let { pendiente ->
                codigoPendiente = null
                entrarASala(pendiente)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            servicio = null
            servicioConectado = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sala_internet)
        BotonRadioOff.instalar(this) // "📻 Radio OFF" mientras suena la radio

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
        btnLeerQr = findViewById(R.id.btnLeerQr)
        btnRadioSala = findViewById(R.id.btnRadioSala)
        findViewById<View>(R.id.btnAtras).setOnClickListener { finish() }
        btnRadioSala.setOnClickListener {
            // La radio desde la sala es solo para escucharla uno mismo (no se comparte).
            startActivity(
                Intent(this, RadioActivity::class.java).putExtra(RadioActivity.EXTRA_SOLO_ESCUCHAR, true)
            )
        }
        txtCodigoActual = findViewById(R.id.txtCodigoActual)
        txtEstadoInternet = findViewById(R.id.txtEstadoInternet)
        txtParticipantes = findViewById(R.id.txtParticipantes)
        btnHablarInternet = findViewById(R.id.btnHablarInternet)
        btnSalirSala = findViewById(R.id.btnSalirSala)
        btnAltavozInternet = findViewById(R.id.btnAltavozInternet)
        seekVolumenInternet = findViewById(R.id.seekVolumenInternet)
        imgQrSala = findViewById(R.id.imgQrSala)
        btnCompartirCodigo = findViewById(R.id.btnCompartirCodigo)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val maximo = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maximo * 0.7).toInt(), 0)
        } catch (e: Exception) {}

        editMiNombre.setText(prefs.getString("mi_nombre", "Motero"))

        // Se ARRANCA el servicio de forma independiente (no solo se conecta a
        // él). Así, aunque cierres esta pantalla con "atrás", el servicio
        // sigue vivo solo por su cuenta, y la sala no se cierra.
        bindService(Intent(this, SalaInternetService::class.java), conexionServicio, Context.BIND_AUTO_CREATE)

        btnCrearSala.setOnClickListener { crearSala() }
        btnUnirseSala.setOnClickListener {
            val codigo = editCodigoUnirse.text.toString().trim()
            if (codigo.length < 4) {
                Toast.makeText(this, "Escribe el código completo", Toast.LENGTH_SHORT).show()
            } else {
                entrarASala(codigo.uppercase())
            }
        }
        btnLeerQr.setOnClickListener { abrirLectorQr() }
        btnSalirSala.setOnClickListener {
            // La radio NO se apaga al salir de la sala: se apaga con "Radio OFF".
            servicio?.salirDeSala()
            mostrarPanelDesconectado()
        }

        btnHablarInternet.setOnTouchListener { boton, evento ->
            when (evento.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Le dice a la pantalla que NO interprete el movimiento del
                    // dedo como "deslizar la pantalla" mientras se mantiene
                    // presionado este botón — así un pequeño temblor de la
                    // mano (normal andando en moto) ya no te suelta el botón.
                    boton.parent?.requestDisallowInterceptTouchEvent(true)

                    // Si soltaste hace un instante y volviste a tocar,
                    // se cancela el corte pendiente y sigues grabando lo mismo
                    // (no se corta ni se reinicia la grabación).
                    pendienteDetener?.let { manejador.removeCallbacks(it) }
                    pendienteDetener = null
                    if (!PedidorDePermisos.tiene(this, PedidorDePermisos.MICROFONO)) {
                        // Sin micrófono se puede escuchar la sala, pero no hablar:
                        // se pide aquí, en el momento en que se quiere hablar.
                        pedirMicrofono {}
                        return@setOnTouchListener true
                    }
                    if (!grabando) {
                        iniciarGrabacion()
                    }
                    boton.setBackgroundResource(R.drawable.bg_boton_ptt_redondo_hablando)
                    iniciarPulso(boton)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    boton.parent?.requestDisallowInterceptTouchEvent(false)

                    // No se corta de inmediato: se espera un instante (0,3 s) por
                    // si fue un toque accidental (el dedo resbaló por la vibración
                    // de la moto, por ejemplo). Antes era 1 segundo completo, y ese
                    // segundo se sumaba a la demora de cada audio.
                    boton.setBackgroundResource(R.drawable.bg_boton_ptt_redondo)
                    detenerPulso(boton)
                    val corte = Runnable {
                        pendienteDetener = null
                        detenerYEnviarGrabacion()
                    }
                    pendienteDetener = corte
                    manejador.postDelayed(corte, SalaInternetService.COLCHON_AL_SOLTAR_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Mientras el dedo se mueva DENTRO del botón (o cerca),
                    // seguimos igual — no se hace nada especial aquí, el
                    // requestDisallowInterceptTouchEvent ya evita el problema.
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
    }

    /**
     * La burbuja flotante (como la de Didi/Uber) necesita un permiso especial de
     * Android: "Mostrar sobre otras apps". No se puede pedir con el cuadro
     * normal, hay que mandar a la persona a la pantalla de ajustes. Solo se
     * pregunta una vez; si dice "Ahora no", la app funciona igual sin burbuja.
     */
    private fun pedirPermisoBurbujaSiHaceFalta() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (Settings.canDrawOverlays(this)) return
        if (prefs.getBoolean("burbuja_ya_preguntada", false)) return
        prefs.edit().putBoolean("burbuja_ya_preguntada", true).apply()

        AlertDialog.Builder(this)
            .setTitle("Botón flotante para hablar")
            .setMessage(
                "Para que puedas hablar en la sala aunque estés usando otra app " +
                    "(como WhatsApp), MotoIntercom puede mostrar un botón flotante " +
                    "encima de todo, igual que Didi o Uber.\n\n" +
                    "En la siguiente pantalla, activa \"Permitir mostrar sobre otras apps\" " +
                    "para MotoIntercom."
            )
            .setPositiveButton("Continuar") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Búscalo en Ajustes > Apps > MotoIntercom > Mostrar sobre otras apps",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        pantallaVisible = true
        // El botón de radio muestra qué emisora está sonando, si hay alguna.
        btnRadioSala.text = ReproductorRadio.emisoraActual?.let { "📻 Sonando: ${it.nombre} (cambiar o detener)" }
            ?: "📻 Escuchar radio (solo para mí)"
        servicio?.establecerPantallaDeSalaVisible(true)
    }

    // Al salir de esta pantalla (a otra app como Waze o WhatsApp, o al
    // escritorio) aparece la burbuja flotante, si se está en una sala.
    override fun onPause() {
        super.onPause()
        pantallaVisible = false
        servicio?.establecerPantallaDeSalaVisible(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        manejarEnlaceDeEntrada(intent)
    }

    private fun abrirLectorQr() {
        guardarNombre()
        permisos.pedir(
            PedidorDePermisos.CAMARA, "Permiso de cámara", "la cámara",
            "Para leer el código QR de la sala y entrar sin escribir el código.\n\n" +
                "La cámara solo se usa mientras está abierto el lector. No se toman ni se guardan fotos."
        ) { ok ->
            if (ok) abrirCamaraQr()
            else Toast.makeText(this, "Sin la cámara puedes escribir el código de la sala a mano.", Toast.LENGTH_LONG).show()
        }
    }

    private fun abrirCamaraQr() {
        val opciones = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Apunta la cámara al código QR de la sala")
            .setBeepEnabled(true)
            .setOrientationLocked(false)
        try {
            lectorQr.launch(opciones)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Saca el código de la sala de lo que trae el QR. Sirve con:
     *  - el link de la página: https://.../motointercom/?sala=A3F9K2 (el que genera la app)
     *  - el link directo de la app: motointercom://sala/A3F9K2
     *  - un QR que tenga solo el código: A3F9K2
     */
    private fun extraerCodigoDeSala(texto: String): String? {
        val limpio = texto.trim()
        val candidato = try {
            val uri = Uri.parse(limpio)
            when {
                uri.scheme == "motointercom" && uri.host == "sala" -> uri.lastPathSegment
                uri.isHierarchical && uri.getQueryParameter("sala") != null -> uri.getQueryParameter("sala")
                else -> limpio
            }
        } catch (e: Exception) {
            limpio
        }
        val codigo = candidato?.trim()?.uppercase() ?: return null
        return if (Regex("^[A-Z0-9]{4,10}$").matches(codigo)) codigo else null
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
    private fun crearBitmapQr(codigo: String): Bitmap {
        val contenido = "$URL_LANDING_PAGE?sala=$codigo"
        val tamano = 600
        val matriz = QRCodeWriter().encode(contenido, BarcodeFormat.QR_CODE, tamano, tamano)
        val pixeles = IntArray(tamano * tamano) { i ->
            if (matriz.get(i % tamano, i / tamano)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixeles, tamano, tamano, Bitmap.Config.ARGB_8888)
    }

    private fun generarQr(codigo: String) {
        try {
            imgQrSala.setImageBitmap(crearBitmapQr(codigo))
        } catch (e: Exception) {
            // Si por lo que sea no se puede generar el QR, no es grave: queda
            // el código de texto y el botón de compartir, que sí funcionan igual.
        }
    }

    /** Abre el selector de apps de Android (WhatsApp, Mensajes, Telegram, etc.)
     *  para compartir la sala: la IMAGEN del código QR junto con el texto
     *  (código y link). Si la imagen no se puede preparar, se comparte solo el texto. */
    private fun compartirCodigo() {
        val codigo = servicio?.codigoSalaActual ?: return
        val mensaje = "Únete a mi sala de MotoIntercom con el código: $codigo\n\n" +
            "Escanea el QR o abre este link (en iPhone funciona desde el navegador, sin instalar nada):\n" +
            "$URL_LANDING_PAGE?sala=$codigo"
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, mensaje)
        }
        val imagen = guardarImagenQrParaCompartir(codigo)
        if (imagen != null) {
            intent.type = "image/png"
            intent.putExtra(Intent.EXTRA_STREAM, imagen)
            // Le da permiso a la app elegida (WhatsApp, etc.) de leer la imagen.
            intent.clipData = android.content.ClipData.newRawUri("QR sala $codigo", imagen)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.type = "text/plain"
        }
        startActivity(Intent.createChooser(intent, "Compartir sala $codigo"))
    }

    /** Guarda una imagen con el QR y el código de la sala debajo, lista para
     *  enviarla. Devuelve su dirección para compartir, o null si falló. */
    private fun guardarImagenQrParaCompartir(codigo: String): Uri? {
        return try {
            val qr = crearBitmapQr(codigo)
            val altoTexto = 110
            val imagen = Bitmap.createBitmap(qr.width, qr.height + altoTexto, Bitmap.Config.ARGB_8888)
            val lienzo = android.graphics.Canvas(imagen)
            lienzo.drawColor(Color.WHITE)
            lienzo.drawBitmap(qr, 0f, 0f, null)
            val pincel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 36f
                isFakeBoldText = true
            }
            lienzo.drawText("MotoIntercom · Sala $codigo", imagen.width / 2f, qr.height + 60f, pincel)

            val carpeta = File(cacheDir, "compartir").apply { mkdirs() }
            val archivo = File(carpeta, "sala_$codigo.png")
            archivo.outputStream().use { imagen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.archivos", archivo)
        } catch (e: Exception) {
            null
        }
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

    /** Micrófono para hablar en la sala. Si se concede estando ya en la sala,
     *  se le avisa al servicio para que la burbuja también pueda grabar. */
    private fun pedirMicrofono(alTerminar: (Boolean) -> Unit) {
        permisos.pedir(
            PedidorDePermisos.MICROFONO, "Permiso de micrófono", "el micrófono",
            "Para grabar lo que dices mientras mantienes presionado el botón de hablar y " +
                "enviarlo a la sala.\n\nSolo graba mientras presionas el botón. Sin este permiso " +
                "igual puedes escuchar a los demás."
        ) { ok ->
            if (ok) servicio?.actualizarPrimerPlano()
            alTerminar(ok)
        }
    }

    /**
     * Antes de entrar a la sala pide, explicando para qué: micrófono (si dice que
     * no, entra igual solo a escuchar), notificaciones y batería (una sola vez).
     * Ya adentro, ofrece el botón flotante.
     */
    private fun entrarASala(codigo: String) {
        if (servicio == null) {
            codigoPendiente = codigo
            return
        }
        pedirMicrofono {
            permisos.pedirUnaVez(
                "notificaciones_preguntadas", PedidorDePermisos.NOTIFICACIONES,
                "Notificaciones", "las notificaciones",
                "Mientras estés en la sala, MotoIntercom muestra una notificación fija. Así Android " +
                    "sabe que la sala sigue activa y te siguen llegando los audios aunque uses otra app." +
                    "\n\nNo se usan para publicidad."
            ) {
                permisos.pedirBateriaUnaVez {
                    entrarASalaConPermisos(codigo)
                    pedirPermisoBurbujaSiHaceFalta()
                }
            }
        }
    }

    private fun entrarASalaConPermisos(codigo: String) {
        if (servicio == null) {
            codigoPendiente = codigo
            return
        }
        val nombre = guardarNombre()
        // Arranca el servicio de forma independiente ANTES de pedirle que
        // entre a la sala: así, aunque luego cierres esta pantalla, el
        // servicio sigue vivo solo por su cuenta.
        androidx.core.content.ContextCompat.startForegroundService(
            this, Intent(this, SalaInternetService::class.java)
        )
        servicio?.entrarASala(codigo, nombre, miId)
        txtCodigoActual.text = "Sala: $codigo"
        txtEstadoInternet.text = "Conectado como $nombre. Mantén presionado para hablar."
        generarQr(codigo)
        mostrarPanelConectado()
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

    private var horaInicioGrabacion = 0L
    private val actualizarCronometro = object : Runnable {
        override fun run() {
            if (!grabando) return
            val segundos = ((System.currentTimeMillis() - horaInicioGrabacion) / 1000).toInt()
            btnHablarInternet.text = "🔴 %d:%02d".format(segundos / 60, segundos % 60)
            manejador.postDelayed(this, 200)
        }
    }

    private fun iniciarGrabacion() {
        if (grabando) return
        if (servicio?.codigoSalaActual == null) return
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
            horaInicioGrabacion = System.currentTimeMillis()
            btnHablarInternet.text = "🔴 0:00"
            manejador.post(actualizarCronometro)
            txtEstadoInternet.text = "Hablando en sala ${servicio?.codigoSalaActual}"
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
        manejador.removeCallbacks(actualizarCronometro)
        btnHablarInternet.text = "Mantén presionado para hablar"
        try {
            grabador?.stop()
        } catch (e: Exception) {
            // Grabación muy corta o sin audio; se descarta silenciosamente.
        }
        grabador?.release()
        grabador = null

        val archivo = archivoTemporal ?: return
        if (archivo.length() < 500) return // grabación demasiado corta, probablemente un toque accidental

        txtEstadoInternet.text = "Enviando..."
        val nombre = editMiNombre.text.toString().trim().ifBlank { "Motero" }
        servicio?.enviarMensaje(archivo, nombre) { exito, error ->
            runOnUiThread {
                if (!exito) {
                    Toast.makeText(this, error ?: "No se pudo enviar", Toast.LENGTH_SHORT).show()
                }
                txtEstadoInternet.text = "Mantén presionado para hablar"
            }
        }
    }

    /** Efecto visual de "pulso" mientras se mantiene presionado el botón de hablar. */
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

    override fun onEstadoCambiado(mensaje: String) {
        runOnUiThread {
            txtEstadoInternet.text = mensaje
            // Los avisos de "alguien entró/salió" son pasajeros: a los pocos
            // segundos, si no cambió nada más, se vuelve al mensaje normal.
            if (mensaje.contains("se unió a la sala") || mensaje.contains("salió de la sala")) {
                manejador.postDelayed({
                    if (txtEstadoInternet.text == mensaje) {
                        txtEstadoInternet.text = "Mantén presionado para hablar"
                    }
                }, 3000)
            }
        }
    }

    override fun onParticipantesCambiaron(nombres: List<String>) {
        runOnUiThread {
            txtParticipantes.text = if (nombres.isEmpty()) {
                "👥 No hay nadie más en la sala todavía"
            } else {
                "👥 En la sala (${nombres.size}): " + nombres.joinToString(", ")
            }
        }
    }

    // OJO: aquí NO se llama a servicio?.salirDeSala(). Cerrar esta pantalla
    // (con "atrás" o cambiando de app) solo cierra la pantalla — la sala
    // sigue conectada en segundo plano hasta que el usuario toque
    // explícitamente "Salir de la sala".
    override fun onDestroy() {
        detenerPulso(btnHablarInternet)
        if (grabando) detenerYEnviarGrabacion()
        if (servicioConectado) {
            servicio?.registrarEscucha(null)
            unbindService(conexionServicio)
        }
        super.onDestroy()
    }
}
