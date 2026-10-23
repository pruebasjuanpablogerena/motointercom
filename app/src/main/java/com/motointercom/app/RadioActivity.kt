package com.motointercom.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

data class Emisora(
    val departamento: String,
    val nombre: String,
    val info: String,
    val streaming: String,
    val web: String
)

class RadioActivity : AppCompatActivity() {

    private lateinit var txtBuscar: EditText
    private lateinit var spinnerDepartamento: Spinner
    private lateinit var listaEmisoras: ListView
    private lateinit var txtReproduciendo: TextView
    private lateinit var btnDetenerRadio: Button
    private lateinit var switchCompartirRadio: Switch

    private var todasLasEmisoras: List<Emisora> = emptyList()
    private var emisorasFiltradas: List<Emisora> = emptyList()
    private var adaptador: AdaptadorEmisoras? = null

    private var reproductor: MediaPlayer? = null
    private var emisoraActual: Emisora? = null
    private var reproduciendoPorAvisoDelOtro = false

    private var servicio: IntercomService? = null
    private var servicioConectado = false

    private val conexionServicio = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as IntercomService.IntercomBinder
            servicio = b.obtenerServicio()
            servicioConectado = true
            servicio?.oyenteComandoRadio = { comando -> runOnUiThread { manejarComandoRecibido(comando) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            servicio = null
            servicioConectado = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radio)

        txtBuscar = findViewById(R.id.txtBuscar)
        spinnerDepartamento = findViewById(R.id.spinnerDepartamento)
        listaEmisoras = findViewById(R.id.listaEmisoras)
        txtReproduciendo = findViewById(R.id.txtReproduciendo)
        btnDetenerRadio = findViewById(R.id.btnDetenerRadio)
        switchCompartirRadio = findViewById(R.id.switchCompartirRadio)

        bindService(Intent(this, IntercomService::class.java), conexionServicio, Context.BIND_AUTO_CREATE)

        cargarEmisorasEnSegundoPlano()

        txtBuscar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { aplicarFiltros() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        listaEmisoras.setOnItemClickListener { _, _, posicion, _ ->
            val emisora = emisorasFiltradas.getOrNull(posicion) ?: return@setOnItemClickListener
            reproducir(emisora, avisarAlOtroCelular = switchCompartirRadio.isChecked)
        }

        btnDetenerRadio.setOnClickListener {
            detenerRadio()
            if (switchCompartirRadio.isChecked) {
                servicio?.enviarComandoRadio("RADIO_STOP")
            }
        }

        // El interruptor ya NO abre ningún cuadro de "compartir pantalla". Simplemente
        // manda un aviso por Bluetooth al otro celular para que sintonice (o deje de
        // sintonizar) la misma emisora, cada uno reproduciéndola desde su propio internet.
        switchCompartirRadio.setOnCheckedChangeListener { _, activar ->
            if (activar) {
                val emisora = emisoraActual
                if (emisora == null) {
                    Toast.makeText(this, "Primero elige y reproduce una emisora", Toast.LENGTH_SHORT).show()
                    switchCompartirRadio.isChecked = false
                    return@setOnCheckedChangeListener
                }
                servicio?.enviarComandoRadio("RADIO_PLAY|${emisora.streaming}|${emisora.nombre}")
                Toast.makeText(this, "Avisando al otro casco que sintonice esta emisora...", Toast.LENGTH_SHORT).show()
            } else {
                servicio?.enviarComandoRadio("RADIO_STOP")
            }
        }
    }

    /** Se llama cuando el OTRO celular (conductor o pasajero) avisó por Bluetooth
     *  que hay que sintonizar o dejar de sintonizar una emisora. */
    private fun manejarComandoRecibido(comando: String) {
        if (comando == "RADIO_STOP") {
            if (reproduciendoPorAvisoDelOtro) {
                detenerRadio()
            }
            return
        }
        if (comando.startsWith("RADIO_PLAY|")) {
            val partes = comando.split("|")
            if (partes.size >= 3) {
                val url = partes[1]
                val nombre = partes.subList(2, partes.size).joinToString("|")
                val emisora = Emisora("", nombre, "Sintonizada por el otro casco", url, "")
                reproduciendoPorAvisoDelOtro = true
                reproducir(emisora, avisarAlOtroCelular = false)
                Toast.makeText(this, "El otro casco te compartió una emisora", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cargarEmisorasEnSegundoPlano() {
        Thread {
            try {
                val texto = assets.open("emisoras.json").bufferedReader().use { it.readText() }
                val arreglo = JSONArray(texto)
                val lista = mutableListOf<Emisora>()
                for (i in 0 until arreglo.length()) {
                    val o = arreglo.getJSONObject(i)
                    val url = o.optString("s", "")
                    lista.add(
                        Emisora(
                            departamento = o.optString("d", ""),
                            nombre = o.optString("n", ""),
                            info = o.optString("i", ""),
                            streaming = url,
                            web = o.optString("w", "")
                        )
                    )
                }
                todasLasEmisoras = lista.sortedBy { it.nombre }
                runOnUiThread {
                    prepararSpinnerDepartamentos()
                    aplicarFiltros()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "No se pudo cargar la lista de emisoras", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun prepararSpinnerDepartamentos() {
        val departamentos = mutableListOf("Todos los departamentos")
        departamentos.addAll(todasLasEmisoras.map { it.departamento }.distinct().sorted())
        val adaptadorSpinner = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, departamentos)
        spinnerDepartamento.adapter = adaptadorSpinner
        spinnerDepartamento.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                aplicarFiltros()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun aplicarFiltros() {
        val texto = txtBuscar.text.toString().trim().lowercase()
        val departamentoElegido = (spinnerDepartamento.selectedItem as? String) ?: "Todos los departamentos"

        emisorasFiltradas = todasLasEmisoras.filter { emisora ->
            val coincideDepartamento = departamentoElegido == "Todos los departamentos" || emisora.departamento == departamentoElegido
            val coincideTexto = texto.isEmpty() ||
                emisora.nombre.lowercase().contains(texto) ||
                emisora.info.lowercase().contains(texto)
            coincideDepartamento && coincideTexto
        }

        if (adaptador == null) {
            adaptador = AdaptadorEmisoras()
            listaEmisoras.adapter = adaptador
        } else {
            adaptador?.notifyDataSetChanged()
        }
    }

    private fun reproducir(emisora: Emisora, avisarAlOtroCelular: Boolean) {
        if (emisora.streaming.isBlank()) {
            Toast.makeText(
                this,
                "Esta emisora todavía no tiene enlace de transmisión configurado.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        detenerRadio()
        emisoraActual = emisora
        txtReproduciendo.text = "Cargando: ${emisora.nombre}..."
        btnDetenerRadio.isEnabled = true

        try {
            reproductor = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(emisora.streaming)
                setOnPreparedListener {
                    it.start()
                    txtReproduciendo.text = "🔴 En vivo: ${emisora.nombre}"
                }
                setOnErrorListener { _, _, _ ->
                    txtReproduciendo.text = "No se pudo reproducir ${emisora.nombre}"
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            txtReproduciendo.text = "No se pudo reproducir ${emisora.nombre}"
        }

        if (avisarAlOtroCelular) {
            servicio?.enviarComandoRadio("RADIO_PLAY|${emisora.streaming}|${emisora.nombre}")
        }
    }

    private fun detenerRadio() {
        reproductor?.let {
            try { it.stop(); it.release() } catch (e: Exception) {}
        }
        reproductor = null
        emisoraActual = null
        reproduciendoPorAvisoDelOtro = false
        btnDetenerRadio.isEnabled = false
        txtReproduciendo.text = "Selecciona una emisora"
        if (switchCompartirRadio.isChecked) {
            switchCompartirRadio.isChecked = false
        }
    }

    private inner class AdaptadorEmisoras : BaseAdapter() {
        override fun getCount(): Int = emisorasFiltradas.size
        override fun getItem(position: Int): Any = emisorasFiltradas[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val vista = convertView ?: LayoutInflater.from(this@RadioActivity)
                .inflate(R.layout.item_emisora, parent, false)
            val emisora = emisorasFiltradas[position]
            vista.findViewById<TextView>(R.id.txtNombreEmisora).text = emisora.nombre
            vista.findViewById<TextView>(R.id.txtInfoEmisora).text = emisora.info
            return vista
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detenerRadio()
        if (servicioConectado) {
            servicio?.oyenteComandoRadio = null
            unbindService(conexionServicio)
        }
    }
}
