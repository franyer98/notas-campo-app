package com.franyer.notascampo

import android.app.*
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Servicio en primer plano que dibuja un botón flotante sobre cualquier
 * pantalla. Un toque empieza a grabar, otro toque la detiene y sube la
 * nota al backend (mismo endpoint que usa el flujo de Tasker).
 */
class BubbleService : Service() {

    companion object {
        const val ACCION_GRABAR_POR_VOZ = "com.franyer.notascampo.GRABAR_POR_VOZ"
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: android.widget.ImageButton? = null
    private var grabando = false
    private var recorder: MediaRecorder? = null
    private var archivoActual: File? = null

    // El cliente OkHttp directo ya no se usa aquí — la subida y la cola de
    // reintentos viven en NotaUploader para poder compartirlas con el
    // Worker en segundo plano (UploadWorker).

    override fun onCreate() {
        super.onCreate()
        iniciarNotificacionForeground()
        // Solo dibujamos la burbuja visual si el permiso de superposición
        // está concedido — si no, seguimos funcionando igual para la
        // grabación disparada por voz, que no necesita ningún elemento
        // visual en pantalla. Antes esto no se validaba y tumbaba toda
        // la app cuando WakeWordService arrancaba este servicio sin que
        // el usuario hubiera dado el permiso de burbuja todavía.
        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                dibujarBurbuja()
            } catch (e: Exception) {
                // No dejamos que un fallo al dibujar tumbe el servicio —
                // la grabación por voz debe seguir funcionando igual.
            }
        }
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_GRABAR_POR_VOZ && !grabando) {
            iniciarGrabacionAutomatica()
        }
        return START_STICKY
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = null

    private fun iniciarNotificacionForeground() {
        val channelId = "notas_campo_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Notas de Campo", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Notas de Campo activo")
            .setContentText("Toca la burbuja flotante para grabar una nota")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun dibujarBurbuja() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val boton = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = null
        }

        val tipoVentana = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            tipoVentana,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        // Permite arrastrar la burbuja a cualquier posición de la pantalla.
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        boton.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 15 || kotlin.math.abs(dy) > 15) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!moved) alternarGrabacion()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(boton, params)
        bubbleView = boton
    }

    private fun alternarGrabacion() {
        if (!grabando) iniciarGrabacion() else detenerYSubir()
    }

    private fun iniciarGrabacion() {
        val nombre = "nota_${System.currentTimeMillis()}.m4a"
        archivoActual = File(cacheDir, nombre)

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(archivoActual!!.absolutePath)
            prepare()
            start()
        }
        grabando = true
        bubbleView?.setColorFilter(android.graphics.Color.RED)
        Toast.makeText(this, "Grabando... toca de nuevo para enviar", Toast.LENGTH_SHORT).show()
    }

    /**
     * Grabación disparada por la frase clave detectada con Vosk: no hay
     * botón que tocar para detenerla, así que se corta sola tras un
     * silencio sostenido (o a los 30s como límite de seguridad).
     */
    private fun iniciarGrabacionAutomatica() {
        iniciarGrabacion()
        Toast.makeText(this, "Grabando nota por voz...", Toast.LENGTH_SHORT).show()

        val handler = android.os.Handler(mainLooper)
        var silencioConsecutivo = 0
        val umbralSilencio = 1500 // amplitud MediaRecorder por debajo de esto cuenta como silencio
        val chequeosParaCortar = 5 // 5 x 500ms = 2.5s de silencio sostenido
        var chequeos = 0
        val maxChequeos = 60 // tope de seguridad: 30s
        huboVozReal = false // se resetea en cada grabación nueva

        val runnable = object : Runnable {
            override fun run() {
                val amplitud = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
                if (amplitud >= umbralSilencio) huboVozReal = true
                if (amplitud in 1 until umbralSilencio) silencioConsecutivo++ else if (amplitud >= umbralSilencio) silencioConsecutivo = 0
                chequeos++

                if ((silencioConsecutivo >= chequeosParaCortar && chequeos > 4) || chequeos >= maxChequeos) {
                    if (grabando) detenerYSubir()
                } else {
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.postDelayed(runnable, 500)
    }

    // Solo se usa en el flujo automático (por voz) — la burbuja manual
    // siempre sube lo grabado, porque ahí el usuario decidió a propósito
    // tocarla y detenerla; el riesgo de "alucinación" de Whisper es
    // específico de grabaciones disparadas solas que pueden quedar vacías.
    private var huboVozReal = true

    private fun detenerYSubir() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Grabación muy corta u otro fallo — igual intentamos limpiar.
        }
        recorder?.release()
        recorder = null
        grabando = false
        bubbleView?.clearColorFilter()

        val archivo = archivoActual ?: return

        if (!huboVozReal) {
            // Nunca se detectó voz real durante la grabación — probable
            // disparo accidental del wake-word. Descartamos aquí mismo
            // para no mandarle audio vacío a Whisper, que en ese caso
            // "alucina" texto de subtítulos genéricos en vez de avisar
            // que no hay nada.
            archivo.delete()
            Toast.makeText(this, "No se detectó voz, nota descartada", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Enviando nota...", Toast.LENGTH_SHORT).show()
        subirNota(archivo)
    }

    private fun subirNota(archivo: File) {
        val taskerId = UUID.randomUUID().toString()
        val fechaIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

        // Corre en un hilo aparte: NotaUploader hace la llamada de red de
        // forma síncrona (execute(), no enqueue()) para poder decidir en
        // el momento si hay que encolar el archivo como pendiente.
        Thread {
            val exito = NotaUploader.subir(archivo, taskerId, "franyer", fechaIso)
            if (exito) {
                archivo.delete()
                mostrarToastEnHilo("Nota enviada ✓")
            } else {
                NotaUploader.encolar(this, archivo, "franyer")
                mostrarToastEnHilo("Sin conexión — nota en cola, se reintentará sola")
                WorkScheduler.programarReintento(this)
            }
        }.start()
    }

    private fun mostrarToastEnHilo(mensaje: String) {
        android.os.Handler(mainLooper).post {
            Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager.removeView(it) }
    }
}
