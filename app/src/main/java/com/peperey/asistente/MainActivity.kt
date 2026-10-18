package com.peperey.asistente

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.media.AudioManager
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.speech.tts.UtteranceProgressListener
import android.provider.AlarmClock
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
import java.net.URLEncoder
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var keyBox: EditText
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var handsFree = false
    private var torchOn = false
    private var musicOn = false
    private var lastIds: List<String> = emptyList()
    private var lastIdx = 0
    private val history = JSONArray()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var modeBtn: Button
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    private val TOOLS = """[
 {"type":"function","function":{"name":"linterna","description":"Enciende o apaga la linterna del celular","parameters":{"type":"object","properties":{"encender":{"type":"boolean"}},"required":["encender"]}}},
 {"type":"function","function":{"name":"alarma","description":"Pone una alarma, hora en formato 24 horas","parameters":{"type":"object","properties":{"hora":{"type":"integer"},"minutos":{"type":"integer"}},"required":["hora","minutos"]}}},
 {"type":"function","function":{"name":"temporizador","description":"Inicia un temporizador de cuenta regresiva","parameters":{"type":"object","properties":{"segundos":{"type":"integer"}},"required":["segundos"]}}},
 {"type":"function","function":{"name":"bateria","description":"Dice cuanta bateria le queda al celular","parameters":{"type":"object","properties":{}}}},
 {"type":"function","function":{"name":"musica","description":"Pone musica. Usalo cuando pidan una cancion, artista o playlist","parameters":{"type":"object","properties":{"busqueda":{"type":"string"}},"required":["busqueda"]}}},
 {"type":"function","function":{"name":"control_musica","description":"Controla la musica que suena: pausar, reanudar, siguiente cancion o cancion anterior","parameters":{"type":"object","properties":{"accion":{"type":"string","enum":["pausar","reanudar","siguiente","anterior","detener"]}},"required":["accion"]}}},
 {"type":"function","function":{"name":"volumen","description":"Cambia el volumen de la musica: subir, bajar, o poner un nivel en porcentaje","parameters":{"type":"object","properties":{"accion":{"type":"string","enum":["subir","bajar","nivel"]},"porcentaje":{"type":"integer"}},"required":["accion"]}}}
]"""

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

        modeBtn = Button(this)
        modeBtn.text = "Manos libres: apagado"
        modeBtn.setOnClickListener { setHandsFree(!handsFree) }
        root.addView(modeBtn)

        log = TextView(this)
        log.textSize = 18f
        val sv = ScrollView(this)
        sv.addView(log)
        root.addView(sv)
        setContentView(root)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("es", "MX"))
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) { again() }
                    override fun onDone(utteranceId: String?) { again() }
                })
            }
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun setHandsFree(on: Boolean) {
        handsFree = on
        runOnUiThread {
            modeBtn.text = if (on) "Manos libres: encendido" else "Manos libres: apagado"
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (on) {
            handler.postDelayed({ listen() }, 500)
        } else {
            handler.removeCallbacksAndMessages(null)
            runOnUiThread { recognizer?.cancel() }
        }
    }

    private fun again() {
        if (handsFree) handler.postDelayed({ listen() }, 800)
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
                    again()
                    return
                }
                show("Tú: " + t)
                ask(t)
            }
            override fun onError(error: Int) { if (!handsFree) show("No te oí (código " + error + ")"); again() }
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

    private fun runTool(name: String, args: JSONObject): String {
        try {
            when (name) {
                "linterna" -> {
                    val on = if (args.has("encender")) args.optBoolean("encender", true) else !torchOn
                    val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    cm.setTorchMode(id, on)
                    torchOn = on
                    return if (on) "Linterna encendida" else "Linterna apagada"
                }
                "alarma" -> {
                    val h = args.optInt("hora", 7)
                    val mi = args.optInt("minutos", 0)
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    i.putExtra(AlarmClock.EXTRA_HOUR, h)
                    i.putExtra(AlarmClock.EXTRA_MINUTES, mi)
                    i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    return "Alarma puesta a las %d con %02d".format(h, mi)
                }
                "temporizador" -> {
                    val s = args.optInt("segundos", 60)
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    i.putExtra(AlarmClock.EXTRA_LENGTH, s)
                    i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    return "Temporizador iniciado"
                }
                "control_musica" -> {
                    val acc = args.optString("accion", "pausar")
                    if ((acc == "siguiente" || acc == "anterior") && lastIds.isNotEmpty()) {
                        lastIdx = if (acc == "siguiente") (lastIdx + 1) % lastIds.size else (lastIdx - 1 + lastIds.size) % lastIds.size
                        val n = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + lastIds[lastIdx]))
                        n.setPackage("org.schabi.newpipe")
                        n.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runOnUiThread { startActivity(n) }
                        setHandsFree(false)
                        musicOn = true
                        return "Cambiando de canción"
                    }
                    musicOn = args.optString("accion", "pausar") != "pausar" && args.optString("accion", "pausar") != "detener"
                    val code = when (args.optString("accion", "pausar")) {
                        "reanudar" -> KeyEvent.KEYCODE_MEDIA_PLAY
                        "siguiente" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "anterior" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        "detener" -> KeyEvent.KEYCODE_MEDIA_STOP
                        else -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    }
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                    if (musicOn) setHandsFree(false)
                    return "Listo"
                }
                "volumen" -> {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val acc = args.optString("accion", "subir")
                    if (acc == "nivel") {
                        val pct = args.optInt("porcentaje", 50).coerceIn(0, 100)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100.0).toInt(), 0)
                    } else {
                        val dir = if (acc == "bajar") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                        for (n in 1..3) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                    }
                    if (musicOn) {
                        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                    }
                    val ahora = Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).toInt()
                    if (musicOn) setHandsFree(false)
                    return "Volumen al " + ahora + " por ciento"
                }
                "bateria" -> {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    return "Te queda el " + p + " por ciento de batería"
                }
                "musica" -> {
                    val q = args.optString("busqueda", "")
                    val c = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "Mozilla/5.0")
                    c.setRequestProperty("Accept-Language", "es")
                    c.connectTimeout = 15000
                    c.readTimeout = 20000
                    val html = c.inputStream.bufferedReader().use { it.readText() }
                    val vid = Regex("\"videoId\":\"([\\w-]{11})\"").find(html)?.groupValues?.get(1)
                    if (vid == null) return "No encontré esa canción"
                    lastIds = html.split("\"videoId\":\"").drop(1).map { it.take(11) }.filter { id -> id.length == 11 && id.all { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' } }.distinct().take(10)
                    lastIdx = lastIds.indexOf(vid).coerceAtLeast(0)
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + vid))
                    i.setPackage("org.schabi.newpipe")
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runOnUiThread { startActivity(i) }
                    setHandsFree(false)
                    musicOn = true
                    return "Poniendo " + q
                }
            }
        } catch (e: Exception) {
            return "No pude hacerlo: " + e.message
        }
        return "Todavía no sé hacer eso"
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
                msgs.put(JSONObject().put("role", "system").put("content", "Eres un asistente de voz. Responde en una sola frase corta, en español, sin símbolos ni listas. Si piden algo que una herramienta puede hacer, usa la herramienta."))
                for (j in 0 until history.length()) msgs.put(history.getJSONObject(j))
                msgs.put(JSONObject().put("role", "user").put("content", q))
                body.put("messages", msgs)
                body.put("tools", JSONArray(TOOLS))
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
                    again()
                    return@Thread
                }
                val msg = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                val calls = msg.optJSONArray("tool_calls")
                val r: String
                if (calls != null && calls.length() > 0) {
                    val f = calls.getJSONObject(0).getJSONObject("function")
                    val args = JSONObject(f.optString("arguments", "{}").ifBlank { "{}" })
                    show("Acción: " + f.getString("name"))
                    r = runTool(f.getString("name"), args)
                } else {
                    r = if (msg.isNull("content")) "No supe qué responder" else msg.getString("content")
                }
                show("Asistente: " + r)
                history.put(JSONObject().put("role", "user").put("content", q))
                history.put(JSONObject().put("role", "assistant").put("content", r))
                while (history.length() > 8) history.remove(0)
                runOnUiThread { tts?.speak(r, TextToSpeech.QUEUE_FLUSH, null, "r1") }
            } catch (e: Exception) {
                show("Error: " + e.message)
                again()
            }
        }.start()
    }

    override fun onPause() {
        setHandsFree(false)
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
