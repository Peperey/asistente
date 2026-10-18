package com.peperey.asistente

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = TextView(this)
        t.text = "Hola, soy tu asistente"
        t.textSize = 24f
        t.setPadding(48, 120, 48, 48)
        setContentView(t)
    }
}
