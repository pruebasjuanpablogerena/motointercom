package com.motointercom.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Mantiene la radio sonando aunque se salga de la pantalla de radio (al menú,
 * a la sala por internet o a otra app). Android exige para eso una
 * notificación fija, como la de Spotify: esa notificación trae un botón
 * "Apagar radio".
 *
 * Lo arranca y lo detiene ReproductorRadio; no hace falta llamarlo desde otro lado.
 */
class RadioService : Service() {

    companion object {
        private const val CANAL_ID = "moto_intercom_radio"
        private const val NOTI_ID = 5001
        private const val ACCION_APAGAR = "com.motointercom.app.APAGAR_RADIO"

        fun iniciar(contexto: Context) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    contexto, Intent(contexto, RadioService::class.java)
                )
            } catch (e: Exception) {
                // Si Android no deja arrancarlo, la radio suena igual mientras la app esté abierta.
            }
        }

        fun detener(contexto: Context) {
            try { contexto.stopService(Intent(contexto, RadioService::class.java)) } catch (e: Exception) {}
        }

        /** Actualiza el texto de la notificación (qué emisora suena). */
        fun actualizar(contexto: Context, texto: String) {
            if (!ReproductorRadio.estaSonando()) return
            try {
                contexto.getSystemService(NotificationManager::class.java)
                    .notify(NOTI_ID, construirNotificacion(contexto, texto))
            } catch (e: Exception) {}
        }

        private fun construirNotificacion(contexto: Context, texto: String): Notification {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val canal = NotificationChannel(CANAL_ID, "Radio MotoIntercom", NotificationManager.IMPORTANCE_LOW)
                contexto.getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
            }
            val abrirRadio = PendingIntent.getActivity(
                contexto, 0, Intent(contexto, RadioActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val apagar = PendingIntent.getService(
                contexto, 1, Intent(contexto, RadioService::class.java).setAction(ACCION_APAGAR),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Builder(contexto, CANAL_ID)
                .setContentTitle("📻 Radio · MotoIntercom")
                .setContentText(texto)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(abrirRadio)
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(contexto, android.R.drawable.ic_media_pause),
                        "Apagar radio", apagar
                    ).build()
                )
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android exige mostrar la notificación de inmediato, también cuando se
        // toca "Apagar radio" (luego se quita sola al detener el servicio).
        val notificacion = construirNotificacion(this, ReproductorRadio.estado)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTI_ID, notificacion, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTI_ID, notificacion)
            }
        } catch (e: Exception) {}

        if (intent?.action == ACCION_APAGAR || !ReproductorRadio.estaSonando()) {
            ReproductorRadio.detener()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
