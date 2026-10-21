package com.peperey.asistente

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusTv: TextView
    private lateinit var lastTv: TextView
    private lateinit var logTv: TextView
    private lateinit var bigBtn: Button
    private var starting = false
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun isOn(): Boolean = AssistantService.running || starting

    private fun smallBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(Color.parseColor("#1E88E5"))
        b.background = shape(Color.parseColor("#E3F0FD"), 12)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        lp.topMargin = dp(10)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun render(on: Boolean) {
        if (on) {
            bigBtn.text = "Apagar"
            bigBtn.setTextColor(Color.WHITE)
            bigBtn.background = shape(Color.parseColor("#2E9E5B"), 28)
        } else {
            bigBtn.text = "Encender"
            bigBtn.setTextColor(Color.parseColor("#1B2733"))
            bigBtn.background = shape(Color.parseColor("#D5DCE4"), 28)
        }
    }

    private fun toggle() {
        val svc = Intent(this, AssistantService::class.java)
        if (isOn()) {
            stopService(svc)
            starting = false
            AssistantService.running = false
            render(false)
            statusTv.text = "Apagado"
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastTv.text = "Falta el permiso del micrófono. Actívalo en Ajustes > Apps > Asistente > Permisos."
        } else {
            starting = true
            startForegroundService(svc)
            render(true)
            statusTv.text = "Iniciando…"
        }
    }

    private fun show(t: String) {
        runOnUiThread {
            logTv.append(t + "\n\n")
            if (t.startsWith("Tú:") || t.startsWith("Asistente:") || t.startsWith("Error")) lastTv.text = t
            if (t.startsWith("Listo. Di")) statusTv.text = "Escuchando… di «oye asistente»"
            else if (t.startsWith("Preparando") || t.startsWith("Cargando")) statusTv.text = t
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#F5F7FA"))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(24), dp(56), dp(24), dp(32))
        scroll.addView(root)

        val title = TextView(this)
        title.text = "Asistente"
        title.textSize = 30f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(Color.parseColor("#1B2733"))
        root.addView(title)

        statusTv = TextView(this)
        statusTv.text = "Apagado"
        statusTv.textSize = 16f
        statusTv.setTextColor(Color.parseColor("#5B6B7B"))
        root.addView(statusTv)

        bigBtn = Button(this)
        bigBtn.isAllCaps = false
        bigBtn.textSize = 24f
        bigBtn.setTypeface(null, Typeface.BOLD)
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160))
        blp.topMargin = dp(32)
        bigBtn.layoutParams = blp
        bigBtn.setOnClickListener { toggle() }
        root.addView(bigBtn)

        lastTv = TextView(this)
        lastTv.textSize = 18f
        lastTv.setTextColor(Color.parseColor("#1B2733"))
        lastTv.setPadding(0, dp(24), 0, dp(16))
        root.addView(lastTv)

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.visibility = View.GONE

        val keyBox = EditText(this)
        keyBox.hint = "Clave de Groq (gsk_...)"
        keyBox.setTextColor(Color.parseColor("#1B2733"))
        keyBox.setHintTextColor(Color.parseColor("#8A97A5"))
        keyBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyBox.setText(prefs.getString("key", ""))
        panel.addView(keyBox)

        val cityBox = EditText(this)
        cityBox.hint = "Tu ciudad (para el clima)"
        cityBox.setTextColor(Color.parseColor("#1B2733"))
        cityBox.setHintTextColor(Color.parseColor("#8A97A5"))
        cityBox.setText(prefs.getString("ciudad", ""))
        panel.addView(cityBox)

        panel.addView(smallBtn("Guardar") {
            prefs.edit().putString("key", keyBox.text.toString().trim()).putString("ciudad", cityBox.text.toString().trim()).apply()
            lastTv.text = "Guardado"
        })
        panel.addView(smallBtn("Permitir abrir apps en segundo plano") {
            if (Settings.canDrawOverlays(this)) {
                lastTv.text = "Ese permiso ya está activo"
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            }
        })

        logTv = TextView(this)
        logTv.textSize = 14f
        logTv.setTextColor(Color.parseColor("#5B6B7B"))
        logTv.visibility = View.GONE
        panel.addView(smallBtn("Mostrar u ocultar registro") {
            logTv.visibility = if (logTv.visibility == View.GONE) View.VISIBLE else View.GONE
        })
        panel.addView(logTv)

        root.addView(smallBtn("Configuración") {
            panel.visibility = if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
        })
        root.addView(panel)
        setContentView(scroll)

        if (!prefs.getBoolean("pedido", false)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS), 1)
            prefs.edit().putBoolean("pedido", true).apply()
        }

        AssistantService.onLog = { m -> show(m) }

        if (intent?.getBooleanExtra("auto", false) == true && !isOn() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            starting = true
            startForegroundService(Intent(this, AssistantService::class.java))
            statusTv.text = "Iniciando…"
            scroll.postDelayed({ moveTaskToBack(true) }, 1500)
        }
    }

    override fun onResume() {
        super.onResume()
        render(isOn())
        if (!isOn()) {
            statusTv.text = "Apagado"
        } else if (statusTv.text.toString() == "Apagado") {
            statusTv.text = "Escuchando… di «oye asistente»"
        }
    }

    override fun onDestroy() {
        AssistantService.onLog = null
        super.onDestroy()
    }
}
