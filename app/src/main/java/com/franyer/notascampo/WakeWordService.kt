package com.franyer.notascampo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File

/**
 * Escucha continuamente el micrófono con Vosk (100% offline, sin depender
 * de Google Assistant ni de ninguna cuenta externa) y, al detectar la
 * frase clave "asistente campo" en lo transcrito, dispara el mismo flujo
 * de grabación y envío que usa la burbuja flotante.
 *
 * Nota de batería: a diferencia de un wake-word dedicado (Porcupine), Vosk
 * transcribe todo el tiempo, no solo detecta una palabra — consume más
 * batería. Vale la pena probarlo en una jornada real de campo.
 */
class WakeWordService : Service() {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    private val fraseClave = "asistente campo"

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
        actualizarNotificacion("Cargando modelo de voz...")

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

    /**
     * Muestra en la notificación lo último que Vosk transcribió — sin esto
     * no hay forma de saber, en campo, si el micrófono está oyendo bien o
     * si simplemente no reconoce la frase por acento, ruido, etc.
     */
    private fun actualizarNotificacion(texto: String) {
        if (!::notificationManager.isInitialized) return
        notificationManager.notify(2, construirNotificacion(texto))
    }

    private fun cargarModelo() {
        // El modelo viene empaquetado en assets/model-es (lo descarga el
        // workflow de GitHub Actions en cada compilación). StorageService
        // lo copia a almacenamiento interno la primera vez que se ejecuta.
        StorageService.unpack(
            this, "model-es", "model",
            { modeloListo ->
                model = modeloListo
                actualizarNotificacion("Listo — di la frase para dictar")
                iniciarEscucha()
            },
            { excepcion ->
                actualizarNotificacion("Error cargando modelo: ${excepcion.message}")
                mostrarToastEnHilo("Error cargando modelo de voz: ${excepcion.message}")
            }
        )
    }

    private fun iniciarEscucha() {
        val modeloActual = model ?: return
        val recognizer = Recognizer(modeloActual, 16000.0f)

        speechService = SpeechService(recognizer, 16000.0f)
        speechService?.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                revisarTexto(hypothesis)
            }

            override fun onResult(hypothesis: String?) {
                revisarTexto(hypothesis)
            }

            override fun onFinalResult(hypothesis: String?) {
                revisarTexto(hypothesis)
            }

            override fun onError(exception: Exception?) {
                mostrarToastEnHilo("Error de escucha: ${exception?.message}")
            }

            override fun onTimeout() {
                // Vosk se detiene tras un silencio largo; lo reiniciamos
                // para mantener la escucha continua todo el día.
                speechService?.startListening(this)
            }
        })
    }

    private var procesandoNota = false

    private fun revisarTexto(json: String?) {
        if (json == null || procesandoNota) return
        val texto = try {
            JSONObject(json).optString("partial").ifEmpty {
                JSONObject(json).optString("text")
            }
        } catch (e: Exception) {
            return
        }

        if (texto.isNotBlank()) actualizarNotificacion("Oyendo: \"$texto\"")

        if (texto.lowercase().contains(fraseClave)) {
            dispararGrabacion()
        }
    }

    private fun dispararGrabacion() {
        procesandoNota = true
        mostrarToastEnHilo("Frase detectada — grabando nota")
        // Pausamos la escucha de Vosk mientras se graba la nota: el
        // micrófono no puede ser usado por dos grabadores a la vez.
        speechService?.stop()

        val intent = Intent(this, BubbleService::class.java).apply {
            action = BubbleService.ACCION_GRABAR_POR_VOZ
        }
        startService(intent)

        // Reanudamos la escucha pasado el tiempo máximo que puede durar
        // una nota (30s + margen), sin depender de que BubbleService nos
        // avise directamente — mantiene ambos servicios desacoplados.
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

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
    }
}
