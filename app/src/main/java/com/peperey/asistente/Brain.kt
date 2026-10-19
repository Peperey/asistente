package com.peperey.asistente

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Brain {
    private var torchOn = false
    private var lastIds: List<String> = emptyList()
    private var lastIdx = 0
    private val history = JSONArray()

    private val TOOLS = """[
 {"type":"function","function":{"name":"linterna","description":"Enciende o apaga la linterna del celular","parameters":{"type":"object","properties":{"encender":{"type":"boolean"}},"required":["encender"]}}},
 {"type":"function","function":{"name":"alarma","description":"Pone una alarma, hora en formato 24 horas","parameters":{"type":"object","properties":{"hora":{"type":"integer"},"minutos":{"type":"integer"}},"required":["hora","minutos"]}}},
 {"type":"function","function":{"name":"temporizador","description":"Inicia un temporizador de cuenta regresiva","parameters":{"type":"object","properties":{"segundos":{"type":"integer"}},"required":["segundos"]}}},
 {"type":"function","function":{"name":"bateria","description":"Dice cuanta bateria le queda al celular","parameters":{"type":"object","properties":{}}}},
 {"type":"function","function":{"name":"musica","description":"Pone musica. Usalo cuando pidan una cancion, artista o playlist","parameters":{"type":"object","properties":{"busqueda":{"type":"string"}},"required":["busqueda"]}}},
 {"type":"function","function":{"name":"control_musica","description":"Controla la musica que suena: pausar, reanudar, siguiente cancion, cancion anterior o detener","parameters":{"type":"object","properties":{"accion":{"type":"string","enum":["pausar","reanudar","siguiente","anterior","detener"]}},"required":["accion"]}}},
 {"type":"function","function":{"name":"volumen","description":"Cambia el volumen de la musica: subir, bajar, o poner un nivel en porcentaje","parameters":{"type":"object","properties":{"accion":{"type":"string","enum":["subir","bajar","nivel"]},"porcentaje":{"type":"integer"}},"required":["accion"]}}}
]"""

    private fun openNewPipe(ctx: Context, id: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id))
        i.setPackage("org.schabi.newpipe")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun runTool(ctx: Context, name: String, args: JSONObject): String {
        try {
            when (name) {
                "linterna" -> {
                    val on = if (args.has("encender")) args.optBoolean("encender", true) else !torchOn
                    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
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
                    ctx.startActivity(i)
                    return "Alarma puesta a las %d con %02d".format(h, mi)
                }
                "temporizador" -> {
                    val s = args.optInt("segundos", 60)
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                    i.putExtra(AlarmClock.EXTRA_LENGTH, s)
                    i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    return "Temporizador iniciado"
                }
                "bateria" -> {
                    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
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
                    openNewPipe(ctx, vid)
                    return "Poniendo " + q
                }
                "control_musica" -> {
                    val acc = args.optString("accion", "pausar")
                    if ((acc == "siguiente" || acc == "anterior") && lastIds.isNotEmpty()) {
                        lastIdx = if (acc == "siguiente") (lastIdx + 1) % lastIds.size else (lastIdx - 1 + lastIds.size) % lastIds.size
                        openNewPipe(ctx, lastIds[lastIdx])
                        return "Cambiando de canción"
                    }
                    val code = when (acc) {
                        "reanudar" -> KeyEvent.KEYCODE_MEDIA_PLAY
                        "detener" -> KeyEvent.KEYCODE_MEDIA_STOP
                        else -> KeyEvent.KEYCODE_MEDIA_PAUSE
                    }
                    mediaKey(ctx, code)
                    return "Listo"
                }
                "volumen" -> {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val acc = args.optString("accion", "subir")
                    if (acc == "nivel") {
                        val pct = args.optInt("porcentaje", 50).coerceIn(0, 100)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100.0).toInt(), 0)
                    } else {
                        val dir = if (acc == "bajar") AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
                        for (n in 1..3) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                    }
                    val ahora = Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).toInt()
                    return "Volumen al " + ahora + " por ciento"
                }
            }
        } catch (e: Exception) {
            return "No pude hacerlo: " + e.message
        }
        return "Todavía no sé hacer eso"
    }

    fun answer(ctx: Context, key: String, q: String): String {
        if (key.isEmpty()) return "Primero guarda tu clave de Groq en la app"
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
            if (code != 200) return "Error " + code + " al consultar el modelo"
            val msg = JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val calls = msg.optJSONArray("tool_calls")
            val r: String
            if (calls != null && calls.length() > 0) {
                val f = calls.getJSONObject(0).getJSONObject("function")
                val args = JSONObject(f.optString("arguments", "{}").ifBlank { "{}" })
                r = runTool(ctx, f.getString("name"), args)
            } else {
                r = if (msg.isNull("content")) "No supe qué responder" else msg.getString("content")
            }
            history.put(JSONObject().put("role", "user").put("content", q))
            history.put(JSONObject().put("role", "assistant").put("content", r))
            while (history.length() > 8) history.remove(0)
            return r
        } catch (e: Exception) {
            return "Hubo un error: " + (e.message ?: "desconocido")
        }
    }
}
