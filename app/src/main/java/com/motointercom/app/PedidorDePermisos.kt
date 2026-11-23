package com.motointercom.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Pide los permisos EN EL MOMENTO en que se van a usar (no todos de golpe al
 * abrir la app) y SIEMPRE explica antes para qué es cada uno.
 *
 * Si la persona dice que no, la app sigue funcionando en lo que no necesita
 * ese permiso. Si lo negó del todo (Android ya no vuelve a mostrar el cuadro),
 * se le explica y se le ofrece abrir los ajustes de la app para activarlo.
 *
 * Se debe crear como propiedad de la pantalla (antes de que se muestre),
 * porque Android exige registrar el pedido de permisos desde el inicio.
 */
class PedidorDePermisos(private val actividad: AppCompatActivity) {

    companion object {
        val MICROFONO = arrayOf(Manifest.permission.RECORD_AUDIO)
        val CAMARA = arrayOf(Manifest.permission.CAMERA)

        val BLUETOOTH: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            emptyArray() // en Android 11 o anterior no hace falta pedirlo
        }

        val NOTIFICACIONES: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

        private const val PREFS = "moto_intercom_permisos"

        fun tiene(contexto: Context, permisos: Array<String>): Boolean =
            permisos.all { ContextCompat.checkSelfPermission(contexto, it) == PackageManager.PERMISSION_GRANTED }
    }

    private var pendientes: Array<String> = emptyArray()
    private var nombreActual = ""
    private var alTerminar: ((Boolean) -> Unit)? = null

    private val lanzador = actividad.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val concedido = tiene(actividad, pendientes)
        if (!concedido) {
            // Si Android ya no muestra el cuadro ("no volver a preguntar"), solo
            // queda activarlo a mano en los ajustes de la app.
            val bloqueado = pendientes.any {
                ContextCompat.checkSelfPermission(actividad, it) != PackageManager.PERMISSION_GRANTED &&
                    !actividad.shouldShowRequestPermissionRationale(it)
            }
            if (bloqueado) ofrecerAjustes(nombreActual)
        }
        val callback = alTerminar
        alTerminar = null
        callback?.invoke(concedido)
    }

    /**
     * Si ya tiene los permisos, sigue de una. Si no, muestra primero por qué se
     * necesitan y luego el cuadro de Android.
     * @param nombre como se llama el permiso para la persona ("el micrófono").
     * @param motivo para qué se usa, en palabras sencillas.
     */
    fun pedir(permisos: Array<String>, titulo: String, nombre: String, motivo: String, alTerminar: (Boolean) -> Unit) {
        if (tiene(actividad, permisos)) {
            alTerminar(true)
            return
        }
        AlertDialog.Builder(actividad)
            .setTitle(titulo)
            .setMessage(motivo)
            .setPositiveButton("Continuar") { _, _ ->
                pendientes = permisos
                nombreActual = nombre
                this.alTerminar = alTerminar
                lanzador.launch(permisos)
            }
            .setNegativeButton("Ahora no") { _, _ -> alTerminar(false) }
            .setCancelable(false)
            .show()
    }

    /** Pregunta UNA sola vez (en toda la vida de la app). Para permisos que
     *  ayudan pero no son obligatorios, como las notificaciones. */
    fun pedirUnaVez(clave: String, permisos: Array<String>, titulo: String, nombre: String, motivo: String, alTerminar: () -> Unit) {
        val prefs = actividad.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (tiene(actividad, permisos) || prefs.getBoolean(clave, false)) {
            alTerminar()
            return
        }
        prefs.edit().putBoolean(clave, true).apply()
        pedir(permisos, titulo, nombre, motivo) { alTerminar() }
    }

    /** "Batería sin restricciones": se pregunta una sola vez, explicando por qué. */
    fun pedirBateriaUnaVez(alTerminar: () -> Unit) {
        val powerManager = actividad.getSystemService(Context.POWER_SERVICE) as PowerManager
        val prefs = actividad.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (powerManager.isIgnoringBatteryOptimizations(actividad.packageName) ||
            prefs.getBoolean("bateria_preguntada", false)
        ) {
            alTerminar()
            return
        }
        prefs.edit().putBoolean("bateria_preguntada", true).apply()
        AlertDialog.Builder(actividad)
            .setTitle("Batería sin restricciones")
            .setMessage(
                "Para que la conexión NO se corte cuando abras Waze, WhatsApp o la música, " +
                    "Android no debe \"congelar\" MotoIntercom en segundo plano.\n\n" +
                    "En la siguiente pantalla elige \"Permitir\" o \"Sin restricciones\".\n\n" +
                    "Si dices que no, la app funciona igual, pero la conexión se puede cortar " +
                    "cuando la pantalla esté apagada o uses otra app."
            )
            .setPositiveButton("Continuar") { _, _ ->
                try {
                    @android.annotation.SuppressLint("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${actividad.packageName}"))
                    actividad.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(
                        actividad,
                        "Búscalo en Ajustes > Apps > MotoIntercom > Batería",
                        Toast.LENGTH_LONG
                    ).show()
                }
                alTerminar()
            }
            .setNegativeButton("Ahora no") { _, _ -> alTerminar() }
            .setCancelable(false)
            .show()
    }

    private fun ofrecerAjustes(nombre: String) {
        AlertDialog.Builder(actividad)
            .setTitle("Permiso desactivado")
            .setMessage(
                "Android ya no deja volver a preguntar por $nombre.\n\n" +
                    "Si quieres usar esta función, actívalo a mano: en la siguiente pantalla " +
                    "entra a \"Permisos\" y activa $nombre."
            )
            .setPositiveButton("Abrir ajustes") { _, _ ->
                try {
                    actividad.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${actividad.packageName}"))
                    )
                } catch (e: Exception) {}
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }
}
