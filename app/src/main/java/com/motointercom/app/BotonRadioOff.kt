package com.motointercom.app

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Botón flotante "📻 Radio OFF" DENTRO de la app (no encima de otras apps).
 * Solo aparece mientras suena la radio; al tocarlo, la radio se apaga.
 * Se puede arrastrar por si tapa algo de la pantalla.
 *
 * Se agrega por código encima de la pantalla, sin tocar sus diseños, para
 * que nada de lo que ya hay se mueva de lugar.
 */
object BotonRadioOff {

    @SuppressLint("ClickableViewAccessibility")
    fun instalar(actividad: AppCompatActivity) {
        val raiz = actividad.findViewById<FrameLayout>(android.R.id.content) ?: return
        val densidad = actividad.resources.displayMetrics.density
        fun dp(valor: Int) = (valor * densidad).toInt()

        val boton = TextView(actividad).apply {
            text = "📻 Radio OFF"
            setTextColor(Color.parseColor("#EEEEE6"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#1C1F24"))
                setStroke(dp(2), Color.parseColor("#FF3B30"))
            }
            elevation = dp(8).toFloat()
            contentDescription = "Apagar la radio"
            visibility = View.GONE
        }
        val parametros = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(dp(16), dp(16), dp(16), dp(24)) }
        raiz.addView(boton, parametros)

        // Tocar = apagar la radio. Arrastrar = moverlo de lugar.
        var toqueX = 0f
        var toqueY = 0f
        var inicioX = 0f
        var inicioY = 0f
        var seMovio = false
        boton.setOnTouchListener { vista, evento ->
            when (evento.action) {
                MotionEvent.ACTION_DOWN -> {
                    toqueX = evento.rawX; toqueY = evento.rawY
                    inicioX = vista.translationX; inicioY = vista.translationY
                    seMovio = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = evento.rawX - toqueX
                    val dy = evento.rawY - toqueY
                    if (!seMovio && (Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10))) seMovio = true
                    if (seMovio) {
                        vista.translationX = inicioX + dx
                        vista.translationY = inicioY + dy
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!seMovio) {
                        ReproductorRadio.detener()
                        Toast.makeText(actividad, "Radio apagada", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> true
            }
        }

        val actualizar: () -> Unit = {
            boton.visibility = if (ReproductorRadio.estaSonando()) View.VISIBLE else View.GONE
        }
        actualizar()
        ReproductorRadio.observar(actualizar)
        actividad.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = actualizar()
            override fun onDestroy(owner: LifecycleOwner) = ReproductorRadio.dejarDeObservar(actualizar)
        })
    }
}
