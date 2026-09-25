package com.motointercom.app

import android.app.Application
import android.content.Context

/**
 * Si la app se cierra por un error no controlado, esto guarda el motivo exacto
 * (con la línea de código donde pasó) para poder mostrarlo la próxima vez que
 * se abra la app. Así se puede ver el error real sin necesitar un computador
 * conectado por cable.
 */
class MotoIntercomApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val manejadorAnterior = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { hilo, error ->
            try {
                val texto = "${error.javaClass.simpleName}: ${error.message}\n\n" +
                    error.stackTrace.take(12).joinToString("\n") { "  en $it" }
                getSharedPreferences("moto_intercom_diagnostico", Context.MODE_PRIVATE)
                    .edit()
                    .putString("ultimo_error", texto)
                    .apply()
            } catch (e: Exception) {
                // Si ni siquiera se puede guardar el error, no hay más remedio que dejar
                // que el sistema cierre la app de la forma normal.
            }
            manejadorAnterior?.uncaughtException(hilo, error)
        }
    }
}
