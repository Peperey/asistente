package com.peperey.asistente

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var keyBox: EditText
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 100, 40, 40)

        keyBox = EditText(this)
        keyBox.hint = "Clave de Groq (gsk_...)"
        keyBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyBox.setText(prefs.getString("key", ""))
        root.addView(keyBox)

        val save = Button(this)
        save.text = "Guardar clave"
        save.setOnClickListener {
            prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
            show("Clave guardada")
        }
        root.addView(save)

        val talk = Button(this)
        talk.text = "Hablar"
        talk.setOnClickListener { listen() }
        root.addView(talk)

        log = TextView(this)
        log.textSize = 18f
        val sv = ScrollView(this)
        sv.addView(log)
        root.addView(sv)
        setContentView(root)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("es", "MX"))
            }
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun show(t: String) {
        runOnUiThread { log.append(t + "\n\n") }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            show("Falta el permiso del micrófono")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            show("Este celular no tiene reconocimiento de voz")
            return
        }
        tts?.stop()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) {
                    show("No te oí")
                    return
                }
                show("Tú: " + t)
                ask(t)
            }
            override fun onError(error: Int) { show("No te oí (código " + error + ")") }
            override fun onReadyForSpeech(params: Bundle?) { show("Escuchando...") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
        recognizer?.startListening(i)
    }

    private fun ask(q: String) {
        val key = prefs.getString("key", "") ?: ""
        if (key.isEmpty()) {
            show("Primero guarda tu clave de Groq")
            return
        }
        Thread {
            try {
                val body = JSONObject()
                body.put("model", "openai/gpt-oss-20b")
                body.put("reasoning_effort", "low")
                val msgs = JSONArray()
                msgs.put(JSONObject().put("role", "system").put("content", "Eres un asistente de voz. Responde en una sola frase corta, en español, sin símbolos ni listas."))
                msgs.put(JSONObject().put("role", "user").put("content", q))
                body.put("messages", msgs)
                val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.setRequestProperty("Authorization", "Bearer " + key)
                c.setRequestProperty("Content-Type", "application/json")
                c.doOutput = true
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val txt = stream.bufferedReader().use { it.readText() }
                if (code != 200) {
                    show("Error " + code + ": " + txt.take(200))
                    return@Thread
                }
                val r = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                show("Asistente: " + r)
                runOnUiThread { tts?.speak(r, TextToSpeech.QUEUE_FLUSH, null, "r1") }
            } catch (e: Exception) {
                show("Error: " + e.message)
            }
        }.start()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
