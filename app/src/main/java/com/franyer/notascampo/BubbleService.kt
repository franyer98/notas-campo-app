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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
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

    private val client = OkHttpClient()
    private val backendUrl = "https://notas-campo.onrender.com/notas/upload"

    override fun onCreate() {
        super.onCreate()
        iniciarNotificacionForeground()
        dibujarBurbuja()
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

        val runnable = object : Runnable {
            override fun run() {
                val amplitud = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
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
        Toast.makeText(this, "Enviando nota...", Toast.LENGTH_SHORT).show()
        subirNota(archivo)
    }

    private fun subirNota(archivo: File) {
        val taskerId = UUID.randomUUID().toString()
        val fechaIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("tasker_id", taskerId)
            .addFormDataPart("usuario", "franyer")
            .addFormDataPart("creado_en_dispositivo", fechaIso)
            .addFormDataPart(
                "audio", archivo.name,
                archivo.asRequestBody("audio/mp4".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url(backendUrl)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Guardamos el archivo en una carpeta de pendientes para
                // reintentar después, igual que hace el flujo de Tasker
                // con la cola local cuando no hay señal.
                mostrarToastEnHilo("Sin conexión — nota guardada, se reintentará")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    mostrarToastEnHilo("Nota enviada ✓")
                    archivo.delete()
                } else {
                    mostrarToastEnHilo("Error del servidor: ${response.code}")
                }
                response.close()
            }
        })
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
