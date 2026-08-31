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

    private fun iniciarNotificacionForeground() {
        val channelId = "wakeword_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Escucha por voz", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Escuchando \"asistente campo\"")
            .setContentText("Di la frase para empezar a dictar una nota")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(2, notification)
        }
    }

    private fun cargarModelo() {
        // El modelo viene empaquetado en assets/model-es (lo descarga el
        // workflow de GitHub Actions en cada compilación). StorageService
        // lo copia a almacenamiento interno la primera vez que se ejecuta.
        StorageService.unpack(
            this, "model-es", "model",
            { modeloListo ->
                model = modeloListo
                iniciarEscucha()
            },
            { excepcion ->
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
