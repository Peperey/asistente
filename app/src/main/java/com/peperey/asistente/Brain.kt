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
    @Volatile var pending: (() -> String)? = null
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
 {"type":"function","function":{"name":"video","description":"Abre un video con imagen en pantalla, por ejemplo un tutorial, trailer o clip. Para solo escuchar musica usa la herramienta musica","parameters":{"type":"object","properties":{"busqueda":{"type":"string"}},"required":["busqueda"]}}},
 {"type":"function","function":{"name":"recordatorio","description":"Crea un recordatorio con texto. Usa en_minutos si dicen en cuanto tiempo, por ejemplo en 20 minutos. Usa hora y minutos (formato 24 horas) si dicen a que hora","parameters":{"type":"object","properties":{"texto":{"type":"string"},"en_minutos":{"type":"integer"},"hora":{"type":"integer"},"minutos":{"type":"integer"}},"required":["texto"]}}},
 {"type":"function","function":{"name":"clima","description":"Dice el clima de hoy o de manana en una ciudad. Si no dicen ciudad, deja ciudad vacia para usar la ciudad guardada","parameters":{"type":"object","properties":{"ciudad":{"type":"string"},"dia":{"type":"string","enum":["hoy","manana"]}},"required":[]}}},
 {"type":"function","function":{"name":"guardar_ciudad","description":"Guarda la ciudad donde vive el usuario para consultar el clima","parameters":{"type":"object","properties":{"ciudad":{"type":"string"}},"required":["ciudad"]}}},
 {"type":"function","function":{"name":"llamar","description":"Llama por telefono a un contacto","parameters":{"type":"object","properties":{"contacto":{"type":"string"}},"required":["contacto"]}}},
 {"type":"function","function":{"name":"mensaje","description":"Manda un mensaje de texto SMS a un contacto","parameters":{"type":"object","properties":{"contacto":{"type":"string"},"texto":{"type":"string"}},"required":["contacto","texto"]}}},
 {"type":"function","function":{"name":"control_musica","description":"Controla la musica que suena: pausar, reanudar, siguiente cancion, cancion anterior o detener","parameters":{"type":"object","properties":{"accion":{"type":"string","enum":["pausar","reanudar","siguiente","anterior","detener"]}},"required":["accion"]}}},
 {"type":"function","function":{"name":"volumen","description":"Cambia el volumen de la musica: subir, bajar, o poner un nivel en porcentaje","parameters":{"type":"object","properties":{"accion":{"type":"string","enum":["subir","bajar","nivel"]},"porcentaje":{"type":"integer"}},"required":["accion"]}}}
]"""

    private fun ahora(): String {
        return java.text.SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", java.util.Locale("es", "MX")).format(java.util.Date())
    }

    private fun getJson(u: String): JSONObject {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        val t = c.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(t)
    }

    private fun cielo(code: Int): String {
        return when (code) {
            0 -> "despejado"
            1, 2 -> "algo nublado"
            3 -> "nublado"
            45, 48 -> "niebla"
            in 51..57 -> "llovizna"
            in 61..67 -> "lluvia"
            in 71..77 -> "nieve"
            in 80..82 -> "chubascos"
            85, 86 -> "nieve"
            in 95..99 -> "tormenta"
            else -> "variable"
        }
    }

    private fun clima(ctx: Context, ciudadArg: String, dia: String): String {
        var ciudad = ciudadArg.trim()
        if (ciudad.isEmpty()) ciudad = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("ciudad", "") ?: ""
        if (ciudad.isEmpty()) return "Dime tu ciudad, por ejemplo: mi ciudad es Guadalajara"
        val g = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=es&format=json&name=" + URLEncoder.encode(ciudad, "UTF-8"))
        val res = g.optJSONArray("results")
        if (res == null || res.length() == 0) return "No encontré la ciudad " + ciudad
        val pl = res.getJSONObject(0)
        val lat = pl.getDouble("latitude")
        val lon = pl.getDouble("longitude")
        val nombre = pl.optString("name", ciudad)
        val f = getJson("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=2")
        val d = f.getJSONObject("daily")
        val i = if (dia.lowercase().startsWith("ma")) 1 else 0
        val max = Math.round(d.getJSONArray("temperature_2m_max").getDouble(i))
        val min = Math.round(d.getJSONArray("temperature_2m_min").getDouble(i))
        val lluvia = d.getJSONArray("precipitation_probability_max").optInt(i, 0)
        val cod = d.getJSONArray("weather_code").getInt(i)
        if (i == 1) return "Mañana en " + nombre + ": " + cielo(cod) + ". Máxima de " + max + " grados, mínima de " + min + ", y " + lluvia + " por ciento de probabilidad de lluvia"
        val cur = f.getJSONObject("current")
        val tempAhora = Math.round(cur.getDouble("temperature_2m"))
        return "En " + nombre + " ahora hay " + tempAhora + " grados, " + cielo(cur.getInt("weather_code")) + ". Hoy la máxima es de " + max + ", la mínima de " + min + ", y hay " + lluvia + " por ciento de probabilidad de lluvia"
    }

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
                "video" -> {
                    val q = args.optString("busqueda", "")
                    val c = URL("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "Mozilla/5.0")
                    c.setRequestProperty("Accept-Language", "es")
                    c.connectTimeout = 15000
                    c.readTimeout = 20000
                    val html = c.inputStream.bufferedReader().use { it.readText() }
                    val vid = if (html.contains("videoId")) html.substringAfter("videoId").drop(3).take(11) else ""
                    if (vid.length != 11) return "No encontré ese video"
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + vid))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    return "Abriendo " + q
                }
                "recordatorio" -> {
                    val texto = args.optString("texto", "Recordatorio")
                    val enMin = args.optInt("en_minutos", 0)
                    if (enMin > 0) {
                        val t = Intent(AlarmClock.ACTION_SET_TIMER)
                        t.putExtra(AlarmClock.EXTRA_LENGTH, enMin * 60)
                        t.putExtra(AlarmClock.EXTRA_MESSAGE, texto)
                        t.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(t)
                        return "Listo, te aviso en " + enMin + " minutos: " + texto
                    }
                    if (!args.has("hora")) return "¿A qué hora te lo recuerdo?"
                    val h = args.optInt("hora", 8)
                    val mi = args.optInt("minutos", 0)
                    val a = Intent(AlarmClock.ACTION_SET_ALARM)
                    a.putExtra(AlarmClock.EXTRA_HOUR, h)
                    a.putExtra(AlarmClock.EXTRA_MINUTES, mi)
                    a.putExtra(AlarmClock.EXTRA_MESSAGE, texto)
                    a.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(a)
                    return "Listo, te lo recuerdo a las %d con %02d: %s".format(h, mi, texto)
                }
                "guardar_ciudad" -> {
                    val ciudad = args.optString("ciudad", "").trim()
                    if (ciudad.isEmpty()) return "No entendí la ciudad"
                    ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("ciudad", ciudad).apply()
                    return "Listo, tu ciudad es " + ciudad
                }
                "clima" -> {
                    return clima(ctx, args.optString("ciudad", ""), args.optString("dia", "hoy"))
                }
                "llamar" -> {
                    val quien = args.optString("contacto", "")
                    if (!Acciones.tiene(ctx, android.Manifest.permission.READ_CONTACTS)) return "Falta el permiso de contactos en la app"
                    val c = Acciones.buscar(ctx, quien) ?: return "No encontré a " + quien + " en tus contactos"
                    pending = { Acciones.llamar(ctx, c) }
                    return "¿Llamo a " + c.nombre + "?"
                }
                "mensaje" -> {
                    val quien = args.optString("contacto", "")
                    val texto = args.optString("texto", "")
                    if (!Acciones.tiene(ctx, android.Manifest.permission.READ_CONTACTS)) return "Falta el permiso de contactos en la app"
                    val c = Acciones.buscar(ctx, quien) ?: return "No encontré a " + quien + " en tus contactos"
                    pending = { Acciones.mensaje(ctx, c, texto) }
                    return "¿Le mando a " + c.nombre + " este mensaje: " + texto + "?"
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
            msgs.put(JSONObject().put("role", "system").put("content", "Fecha y hora actuales: " + ahora() + ". Eres un asistente de voz. Responde en una sola frase corta, en español, sin símbolos ni listas. Si piden algo que una herramienta puede hacer, usa la herramienta. Puedes llamar por teléfono con la herramienta llamar y mandar mensajes de texto con la herramienta mensaje. Para el clima usa la herramienta clima. Para recordatorios del tipo recuérdame algo usa la herramienta recordatorio, no alarma ni temporizador. Si te dicen en qué ciudad vive el usuario, usa guardar_ciudad. Cuando te pregunten la hora o la fecha, respóndela de forma natural y hablada, usando la fecha y hora actuales que te di."))
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
