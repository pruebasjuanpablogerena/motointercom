package com.motointercom.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), IntercomService.Escucha {

    private lateinit var txtEstado: TextView
    private lateinit var btnConductor: Button
    private lateinit var btnPasajero: Button
    private lateinit var btnSilenciar: Button
    private lateinit var btnAltavoz: Button
    private lateinit var btnDesconectar: Button
    private lateinit var switchCompartirMusica: Switch
    private lateinit var seekVolumen: SeekBar
    private lateinit var switchWalkie: Switch
    private lateinit var btnHablar: Button
    private lateinit var switchAudifonosBluetooth: Switch

    private lateinit var bluetoothAdapter: BluetoothAdapter

    private var servicio: IntercomService? = null
    private var servicioConectado = false
    private var altavozActivo = false
    private var compartiendoMusica = false

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val conexionServicio = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as IntercomService.IntercomBinder
            servicio = b.obtenerServicio()
            servicioConectado = true
            servicio?.registrarEscucha(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            servicio = null
            servicioConectado = false
        }
    }

    // Se dispara después de que Android muestra el cuadro de permiso para
    // capturar el audio que suena en este celular (música, GPS, etc.)
    private val pedirCapturaDeAudio = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { resultado ->
        if (resultado.resultCode == RESULT_OK && resultado.data != null) {
            val projection = mediaProjectionManager.getMediaProjection(resultado.resultCode, resultado.data!!)
            servicio?.activarCompartirMusica(projection)
            compartiendoMusica = true
        } else {
            Toast.makeText(this, "No se concedió el permiso para compartir el audio", Toast.LENGTH_SHORT).show()
            switchCompartirMusica.isChecked = false
        }
    }

    private val pedirPermisos = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        if (resultados.values.all { it }) {
            Toast.makeText(this, "Permisos concedidos", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Faltan permisos: la app no puede funcionar sin ellos", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mostrarUltimoErrorSiExiste()

        txtEstado = findViewById(R.id.txtEstado)
        btnConductor = findViewById(R.id.btnConductor)
        btnPasajero = findViewById(R.id.btnPasajero)
        btnSilenciar = findViewById(R.id.btnSilenciar)
        btnAltavoz = findViewById(R.id.btnAltavoz)
        btnDesconectar = findViewById(R.id.btnDesconectar)
        switchCompartirMusica = findViewById(R.id.switchCompartirMusica)
        seekVolumen = findViewById(R.id.seekVolumen)
        switchWalkie = findViewById(R.id.switchWalkie)
        btnHablar = findViewById(R.id.btnHablar)
        switchAudifonosBluetooth = findViewById(R.id.switchAudifonosBluetooth)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        solicitarPermisosNecesarios()
        pedirExcepcionDeBateriaSiHaceFalta()

        bindService(Intent(this, IntercomService::class.java), conexionServicio, Context.BIND_AUTO_CREATE)

        btnConductor.setOnClickListener { modoConductor() }
        btnPasajero.setOnClickListener { modoPasajero() }
        btnSilenciar.setOnClickListener {
            val silenciado = servicio?.alternarSilencio() ?: false
            btnSilenciar.text = if (silenciado) "Activar micrófono" else "Silenciar micrófono"
            btnSilenciar.setBackgroundResource(if (silenciado) R.drawable.bg_boton_apagado else R.drawable.bg_boton_encendido)
        }
        btnAltavoz.setOnClickListener {
            altavozActivo = !altavozActivo
            servicio?.activarAltavoz(altavozActivo)
            btnAltavoz.text = if (altavozActivo) "Altavoz: encendido" else "Altavoz: apagado"
            btnAltavoz.setBackgroundResource(if (altavozActivo) R.drawable.bg_boton_encendido else R.drawable.bg_boton_apagado)
        }

        switchAudifonosBluetooth.setOnCheckedChangeListener { _, activar ->
            servicio?.establecerUsoDeAudifonosBluetooth(activar)
        }

        findViewById<Button>(R.id.btnRadioOnline).setOnClickListener {
            startActivity(Intent(this, RadioActivity::class.java))
        }

        findViewById<Button>(R.id.btnSalaInternet).setOnClickListener {
            startActivity(Intent(this, SalaInternetActivity::class.java))
        }

        // Modo walkie-talkie: mientras está activo, el micrófono queda en silencio todo
        // el tiempo, y solo se abre mientras se mantiene presionado btnHablar (como Zello
        // o un radioteléfono). También enciende el altavoz automáticamente, como en un
        // walkie-talkie real.
        switchWalkie.setOnCheckedChangeListener { _, activar ->
            if (activar) {
                servicio?.establecerSilencio(true)
                btnSilenciar.isEnabled = false
                btnHablar.isEnabled = true
                if (!altavozActivo) {
                    altavozActivo = true
                    servicio?.activarAltavoz(true)
                    btnAltavoz.text = "Altavoz: encendido"
                    btnAltavoz.setBackgroundResource(R.drawable.bg_boton_encendido)
                }
            } else {
                servicio?.establecerSilencio(false)
                btnSilenciar.isEnabled = true
                btnHablar.isEnabled = false
                btnHablar.text = "🎙️ Mantén presionado para hablar"
                btnHablar.setBackgroundResource(R.drawable.bg_boton)
            }
        }

        btnHablar.setOnTouchListener { boton, evento ->
            when (evento.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    servicio?.establecerSilencio(false)
                    btnHablar.text = "🎙️ Hablando..."
                    btnHablar.setBackgroundResource(R.drawable.bg_boton_hablando)
                    iniciarPulso(btnHablar)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    servicio?.establecerSilencio(true)
                    btnHablar.text = "🎙️ Mantén presionado para hablar"
                    btnHablar.setBackgroundResource(R.drawable.bg_boton)
                    detenerPulso(boton)
                    boton.performClick()
                    true
                }
                else -> false
            }
        }
        btnDesconectar.setOnClickListener {
            servicio?.detener()
            detenerCompartirMusicaSiEstaActiva()
            switchCompartirMusica.isChecked = false
            switchWalkie.isChecked = false
            altavozActivo = false
            btnAltavoz.text = "Altavoz: apagado"
            actualizarBotones(conectando = false)
            txtEstado.text = "Desconectado"
        }

        switchCompartirMusica.setOnCheckedChangeListener { _, activar ->
            if (activar) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Toast.makeText(this, "Esta función necesita Android 10 o superior", Toast.LENGTH_LONG).show()
                    switchCompartirMusica.isChecked = false
                    return@setOnCheckedChangeListener
                }
                mostrarExplicacionCompartirMusica()
            } else {
                detenerCompartirMusicaSiEstaActiva()
            }
        }

        // Traduce la barra (0 a 100) a una ganancia de volumen de 1.0x a 3.0x.
        seekVolumen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ganancia = 1.0f + (progress / 100f) * 2.0f
                servicio?.establecerGanancia(ganancia)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * Android exige mostrar una pantalla que dice "compartir pantalla" o "compartir una app"
     * para poder tomar el sonido de otra app (Spotify, YouTube, Waze, etc.), aunque en
     * realidad MotoIntercom solo usa ese permiso para el audio, nunca la imagen. Esta
     * explicación aparece antes para que quede claro qué elegir en esa pantalla del sistema.
     */
    private fun mostrarExplicacionCompartirMusica() {
        AlertDialog.Builder(this)
            .setTitle("Compartir música o GPS")
            .setMessage(
                "A continuación Android va a preguntar si quieres \"compartir pantalla\" o " +
                    "\"compartir una app\".\n\n" +
                    "1. Elige \"Compartir una app\".\n" +
                    "2. Selecciona la app que está sonando (Spotify, YouTube, Waze, etc.).\n\n" +
                    "Tranquilo: MotoIntercom solo toma el SONIDO de esa app para enviarlo al " +
                    "otro casco. Nada de lo que se ve en la pantalla se comparte ni se graba."
            )
            .setPositiveButton("Entendido") { _, _ ->
                pedirCapturaDeAudio.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
            .setNegativeButton("Cancelar") { _, _ ->
                switchCompartirMusica.isChecked = false
            }
            .setCancelable(false)
            .show()
    }

    private fun detenerCompartirMusicaSiEstaActiva() {
        if (compartiendoMusica) {
            servicio?.desactivarCompartirMusica()
            compartiendoMusica = false
        }
    }

    private fun solicitarPermisosNecesarios() {
        val permisos = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisos.add(Manifest.permission.BLUETOOTH_CONNECT)
            permisos.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permisos.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permisos.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        pedirPermisos.launch(permisos.toTypedArray())
    }

    private fun mostrarUltimoErrorSiExiste() {
        val prefs = getSharedPreferences("moto_intercom_diagnostico", Context.MODE_PRIVATE)
        val error = prefs.getString("ultimo_error", null)
        if (error != null) {
            prefs.edit().remove("ultimo_error").apply()
            AlertDialog.Builder(this)
                .setTitle("La app se cerró la última vez")
                .setMessage("Este fue el motivo exacto:\n\n$error")
                .setPositiveButton("Copiar y cerrar") { _, _ ->
                    val portapapeles = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    portapapeles.setPrimaryClip(android.content.ClipData.newPlainText("Error MotoIntercom", error))
                    Toast.makeText(this, "Copiado. Puedes pegarlo en el chat.", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    /**
     * Pide que el sistema deje de "optimizar" (congelar) esta app en segundo plano.
     * Esto es clave para que la llamada no se corte cuando se abre otra app, como
     * Spotify, para compartir música — varios fabricantes (Xiaomi, Samsung, Huawei)
     * congelan apps en background muy agresivamente si no se les da esta excepción.
     */
    @SuppressLint("BatteryLife")
    private fun pedirExcepcionDeBateriaSiHaceFalta() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Un permiso más")
                .setMessage(
                    "Para que la llamada no se corte al abrir Spotify, YouTube o el GPS, " +
                        "MotoIntercom necesita que el sistema no la \"congele\" en segundo plano.\n\n" +
                        "En la siguiente pantalla, elige \"Permitir\" o \"Sin restricciones\"."
                )
                .setPositiveButton("Continuar") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this,
                            "Búscalo manualmente en Ajustes > Apps > MotoIntercom > Batería",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton("Ahora no", null)
                .show()
        }
    }

    private fun tienePermisoBluetooth(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun modoConductor() {
        if (!tienePermisoBluetooth()) {
            Toast.makeText(this, "Falta el permiso de Bluetooth. Se va a volver a pedir.", Toast.LENGTH_LONG).show()
            solicitarPermisosNecesarios()
            return
        }
        try {
            if (!bluetoothAdapter.isEnabled) {
                Toast.makeText(this, "Activa el Bluetooth primero", Toast.LENGTH_SHORT).show()
                return
            }
            servicio?.iniciarComoServidor(bluetoothAdapter)
            actualizarBotones(conectando = true)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Falta el permiso de Bluetooth para continuar.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun modoPasajero() {
        if (!tienePermisoBluetooth()) {
            Toast.makeText(this, "Falta el permiso de Bluetooth. Se va a volver a pedir.", Toast.LENGTH_LONG).show()
            solicitarPermisosNecesarios()
            return
        }
        try {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth primero", Toast.LENGTH_SHORT).show()
            return
        }
        val emparejados: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
        if (emparejados.isEmpty()) {
            Toast.makeText(this, "No hay dispositivos emparejados. Empareja los celulares desde Ajustes primero.", Toast.LENGTH_LONG).show()
            return
        }
        val nombres = emparejados.map { it.name ?: it.address }.toTypedArray()
        val lista = emparejados.toList()

        AlertDialog.Builder(this)
            .setTitle("Elige el celular del conductor")
            .setItems(nombres) { _, indice ->
                servicio?.conectarComoCliente(lista[indice])
                actualizarBotones(conectando = true)
            }
            .show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Falta el permiso de Bluetooth para continuar.", Toast.LENGTH_LONG).show()
        }
    }

    private fun actualizarBotones(conectando: Boolean) {
        btnConductor.isEnabled = !conectando
        btnPasajero.isEnabled = !conectando
        btnSilenciar.isEnabled = conectando && !switchWalkie.isChecked
        btnAltavoz.isEnabled = conectando
        btnDesconectar.isEnabled = conectando
        switchCompartirMusica.isEnabled = conectando
        seekVolumen.isEnabled = conectando
        switchWalkie.isEnabled = conectando
        if (conectando) {
            // Al conectar, arranca con el micrófono activo (verde) y el altavoz
            // apagado (rojo), que es el estado real con el que inicia la llamada.
            btnSilenciar.text = "Silenciar micrófono"
            btnSilenciar.setBackgroundResource(R.drawable.bg_boton_encendido)
            btnAltavoz.text = "Altavoz: apagado"
            btnAltavoz.setBackgroundResource(R.drawable.bg_boton_apagado)
        } else {
            btnHablar.isEnabled = false
            btnHablar.text = "🎙️ Mantén presionado para hablar"
            btnHablar.setBackgroundResource(R.drawable.bg_boton)
            detenerPulso(btnHablar)
        }
    }

    /** Efecto visual de "pulso" mientras se mantiene presionado un botón de hablar,
     *  para que se note claramente que se está transmitiendo. */
    private fun iniciarPulso(vista: android.view.View) {
        detenerPulso(vista)
        val escalaX = PropertyValuesHolder.ofFloat(android.view.View.SCALE_X, 1f, 1.08f)
        val escalaY = PropertyValuesHolder.ofFloat(android.view.View.SCALE_Y, 1f, 1.08f)
        val animador = ObjectAnimator.ofPropertyValuesHolder(vista, escalaX, escalaY)
        animador.duration = 380
        animador.repeatMode = ObjectAnimator.REVERSE
        animador.repeatCount = ObjectAnimator.INFINITE
        animador.start()
        vista.tag = animador
    }

    private fun detenerPulso(vista: android.view.View) {
        (vista.tag as? ObjectAnimator)?.cancel()
        vista.tag = null
        vista.scaleX = 1f
        vista.scaleY = 1f
    }

    override fun onEstadoCambiado(mensaje: String) {
        runOnUiThread { txtEstado.text = mensaje }
    }

    override fun onError(mensaje: String) {
        runOnUiThread {
            txtEstado.text = mensaje
            actualizarBotones(conectando = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (servicioConectado) {
            servicio?.registrarEscucha(null)
            unbindService(conexionServicio)
        }
    }
}
