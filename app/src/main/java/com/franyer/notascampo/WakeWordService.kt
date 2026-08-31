package com.franyer.notascampo

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.concurrent.thread

/**
 * Escucha continuamente el micrófono con Vosk (100% offline, sin depender
 * de Google Assistant ni de ninguna cuenta externa) y, al detectar la
 * frase clave "asistente campo" en lo transcrito, dispara el mismo flujo
 * de grabación y envío que usa la burbuja flotante.
 *
 * Usamos AudioRecord directamente (en vez de org.vosk.android.SpeechService)
 * para poder medir la amplitud real del audio que llega y mostrarla en la
 * notificación — necesario para diagnosticar si el problema es que no
 * llega audio del micrófono, o que sí llega pero Vosk no reconoce la frase.
 *
 * Nota de batería: a diferencia de un wake-word dedicado (Porcupine), Vosk
 * transcribe todo el tiempo, no solo detecta una palabra — consume más
 * batería. Vale la pena probarlo en una jornada real de campo.
 */
class WakeWordService : Service() {

    private var model: Model? = null
    private var audioRecord: AudioRecord? = null
    private var hiloEscucha: Thread? = null
    @Volatile private var seguirEscuchando = false

    private val fraseClave = "asistente campo"
    private val sampleRate = 16000

    override fun onCreate() {
        super.onCreate()
        iniciarNotificacionForeground()
        cargarModelo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private lateinit var notificationManager: NotificationManager
    private val channelId = "wakeword_channel"

    private fun iniciarNotificacionForeground() {
        notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Escucha por voz", NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
        val notification = construirNotificacion("Cargando modelo de voz...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(2, notification)
        }
    }

    private fun construirNotificacion(texto: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Escuchando \"asistente campo\"")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        if (!::notificationManager.isInitialized) return
        notificationManager.notify(2, construirNotificacion(texto))
    }

    private fun cargarModelo() {
        StorageService.unpack(
            this, "model-es", "model",
            { modeloListo ->
                model = modeloListo
                iniciarEscucha()
                iniciarVigilante()
            },
            { excepcion ->
                actualizarNotificacion("Error cargando modelo: ${excepcion.message}")
            }
        )
    }

    private fun iniciarEscucha() {
        val modeloActual = model ?: return
        detenerEscucha()

        val tamanoBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (tamanoBuffer <= 0) {
            actualizarNotificacion("Error: el dispositivo no soporta 16kHz mono (código $tamanoBuffer)")
            return
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                tamanoBuffer * 2
            )
        } catch (e: SecurityException) {
            actualizarNotificacion("Sin permiso de micrófono")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            actualizarNotificacion("Error: AudioRecord no se pudo inicializar (mic en uso por otra app)")
            record.release()
            return
        }

        audioRecord = record
        val recognizer = Recognizer(modeloActual, sampleRate.toFloat())

        seguirEscuchando = true
        record.startRecording()
        ultimaActividad = System.currentTimeMillis()

        hiloEscucha = thread(start = true) {
            val buffer = ShortArray(tamanoBuffer)
            var contadorSilencio = 0
            while (seguirEscuchando) {
                val leidos = record.read(buffer, 0, buffer.size)
                if (leidos <= 0) continue

                // Amplitud máxima del bloque — si se queda en 0 todo el
                // tiempo, el micrófono no está entregando audio real,
                // aunque AudioRecord no reporte ningún error.
                var amplitud = 0
                for (i in 0 until leidos) {
                    val v = kotlin.math.abs(buffer[i].toInt())
                    if (v > amplitud) amplitud = v
                }
                contadorSilencio = if (amplitud < 300) contadorSilencio + 1 else 0

                val bytes = ByteArray(leidos * 2)
                for (i in 0 until leidos) {
                    bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                }

                val huboResultado = recognizer.acceptWaveForm(bytes, bytes.size)
                val json = if (huboResultado) recognizer.result else recognizer.partialResult
                procesarResultado(json, amplitud, contadorSilencio)
            }
            recognizer.close()
        }
    }

    private fun detenerEscucha() {
        seguirEscuchando = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) { /* nada que liberar aún */ }
        audioRecord = null
    }

    private var procesandoNota = false
    private var ultimaActividad = System.currentTimeMillis()
    private var ultimoAvisoAmplitud = 0L

    private fun procesarResultado(json: String?, amplitud: Int, contadorSilencio: Int) {
        if (json == null || procesandoNota) return
        ultimaActividad = System.currentTimeMillis()

        val texto = try {
            JSONObject(json).optString("partial").ifEmpty {
                JSONObject(json).optString("text")
            }
        } catch (e: Exception) {
            ""
        }

        // Mostramos la amplitud en vivo cada segundo aprox, incluso sin
        // texto — así se ve de inmediato si el micrófono capta algo.
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoAvisoAmplitud > 1000) {
            ultimoAvisoAmplitud = ahora
            val estado = if (amplitud < 300) "silencio" else "captando audio (amp=$amplitud)"
            val textoMostrar = if (texto.isNotBlank()) " — oyendo: \"$texto\"" else ""
            actualizarNotificacion("$estado$textoMostrar")
        }

        if (texto.lowercase().contains(fraseClave)) {
            dispararGrabacion()
        }
    }

    private fun dispararGrabacion() {
        procesandoNota = true
        ultimaActividad = System.currentTimeMillis()
        mostrarToastEnHilo("Frase detectada — grabando nota")
        detenerEscucha()

        val intent = Intent(this, BubbleService::class.java).apply {
            action = BubbleService.ACCION_GRABAR_POR_VOZ
        }
        startService(intent)

        android.os.Handler(mainLooper).postDelayed({
            procesandoNota = false
            iniciarEscucha()
        }, 35_000)
    }

    private fun mostrarToastEnHilo(mensaje: String) {
        android.os.Handler(mainLooper).post {
            Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
        }
    }

    private fun iniciarVigilante() {
        val handler = android.os.Handler(mainLooper)
        val intervalo = 20_000L
        val runnable = object : Runnable {
            override fun run() {
                val inactivo = System.currentTimeMillis() - ultimaActividad
                if (!procesandoNota && inactivo > intervalo) {
                    iniciarEscucha()
                }
                handler.postDelayed(this, intervalo)
            }
        }
        handler.postDelayed(runnable, intervalo)
    }

    override fun onDestroy() {
        super.onDestroy()
        detenerEscucha()
    }
}
