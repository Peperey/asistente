package com.peperey.asistente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.Locale

class AssistantService : Service() {
    companion object {
        @Volatile var running = false
        var onLog: ((String) -> Unit)? = null
        const val WAKE = "asistente"
    }

    @Volatile private var alive = false
    @Volatile private var speaking = false
    @Volatile private var needReset = false
    @Volatile private var speakStart = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    private fun log(t: String) { onLog?.invoke(t) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (alive) return START_STICKY
        startNotif()
        alive = true
        running = true
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("es", "MX"))
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { endSpeak() }
                    override fun onError(utteranceId: String?) { endSpeak() }
                })
                ttsReady = true
            }
        }
        worker = Thread { loop() }
        worker?.start()
        return START_STICKY
    }

    private fun startNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("asist", "Asistente", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "asist")
            .setContentTitle("Asistente escuchando")
            .setContentText("Di: oye asistente")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun endSpeak() {
        main.postDelayed({ speaking = false; needReset = true }, 700)
    }

    private fun say(t: String) {
        log("Asistente: " + t)
        if (!ttsReady) return
        speakStart = System.currentTimeMillis()
        speaking = true
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "u")
    }

    private fun onCommand(cmd: String) {
        log("Tú: " + cmd)
        say("Entendí: " + cmd)
    }

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path)
        if (list == null || list.isEmpty()) {
            try {
                dest.parentFile?.mkdirs()
                assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: Exception) {
                dest.mkdirs()
            }
        } else {
            dest.mkdirs()
            for (n in list) copyAssets(path + "/" + n, File(dest, n))
        }
    }

    private fun loop() {
        var model: Model? = null
        var rec: Recognizer? = null
        var mic: AudioRecord? = null
        try {
            val dir = File(filesDir, "model")
            val ok = File(dir, "listo")
            if (!ok.exists()) {
                log("Preparando el modelo de voz, solo la primera vez...")
                dir.deleteRecursively()
                copyAssets("model", dir)
                ok.writeText("1")
            }
            log("Cargando modelo...")
            val m = Model(dir.absolutePath)
            model = m
            val r = Recognizer(m, 16000f)
            rec = r
            val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8000)
            val mc = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
            mic = mc
            mc.startRecording()
            val buf = ShortArray(4000)
            var waitingCmd = false
            var deadline = 0L
            log("Listo. Di: oye asistente")
            while (alive) {
                val n = mc.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = System.currentTimeMillis()
                if (speaking) {
                    if (now - speakStart > 20000) speaking = false
                    continue
                }
                if (needReset) {
                    r.reset()
                    needReset = false
                    continue
                }
                if (waitingCmd && now > deadline) {
                    waitingCmd = false
                    r.reset()
                }
                if (r.acceptWaveForm(buf, n)) {
                    val text = JSONObject(r.result).optString("text", "").trim()
                    if (text.isEmpty()) continue
                    if (waitingCmd) {
                        waitingCmd = false
                        onCommand(text)
                    } else {
                        val i = text.indexOf(WAKE)
                        if (i >= 0) {
                            val cmd = text.substring(i + WAKE.length).trim()
                            if (cmd.isEmpty()) {
                                waitingCmd = true
                                deadline = System.currentTimeMillis() + 15000
                                say("Dime")
                            } else {
                                onCommand(cmd)
                            }
                        } else {
                            log("Oí: " + text)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            log("Error: " + e.message)
        } finally {
            try { mic?.stop() } catch (e: Throwable) {}
            mic?.release()
            rec?.close()
            model?.close()
        }
    }

    override fun onDestroy() {
        alive = false
        running = false
        tts?.shutdown()
        super.onDestroy()
    }
}
