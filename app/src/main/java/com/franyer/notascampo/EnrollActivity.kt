package com.franyer.notascampo

import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.concurrent.thread

/**
 * Graba ~5 segundos de tu voz y guarda su "huella" (vector numérico) en
 * SharedPreferences. WakeWordService compara cada activación contra esta
 * huella para ignorar voces que no sean la tuya.
 */
class EnrollActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private val sampleRate = 16000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        status = TextView(this).apply {
            text = "Toca el botón y di en voz normal, por unos 5 segundos, " +
                "una frase larga (por ejemplo: describe brevemente tu trabajo " +
                "en Rubiales). Entre más hables, mejor queda tu huella de voz."
            textSize = 16f
        }

        val boton = Button(this).apply {
            text = "Grabar mi huella de voz"
            setOnClickListener { grabarMuestra() }
        }

        layout.addView(status)
        layout.addView(boton)
        setContentView(layout)
    }

    private fun grabarMuestra() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 200)
            return
        }

        status.text = "Cargando modelos..."
        VoiceModels.obtener(this) { modeloVoz, modeloHablante ->
            runOnUiThread { status.text = "Grabando... habla ahora" }
            thread(start = true) {
                try {
                    val tamanoBuffer = AudioRecord.getMinBufferSize(
                        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    val record = AudioRecord(
                        MediaRecorder.AudioSource.MIC, sampleRate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, tamanoBuffer * 2
                    )
                    val recognizer = Recognizer(modeloVoz, sampleRate.toFloat(), modeloHablante)

                    record.startRecording()
                    val buffer = ShortArray(tamanoBuffer)
                    val duracionMs = 5000L
                    val inicio = System.currentTimeMillis()

                    while (System.currentTimeMillis() - inicio < duracionMs) {
                        val leidos = record.read(buffer, 0, buffer.size)
                        if (leidos <= 0) continue
                        val bytes = ByteArray(leidos * 2)
                        for (i in 0 until leidos) {
                            bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                        }
                        recognizer.acceptWaveForm(bytes, bytes.size)
                    }

                    record.stop()
                    record.release()

                    // Forzamos el resultado final para que incluya el
                    // vector "spk" con la huella de voz de esta muestra.
                    val json = recognizer.finalResult
                    recognizer.close()

                    val spk = JSONObject(json).optJSONArray("spk")
                    if (spk == null || spk.length() == 0) {
                        runOnUiThread {
                            status.text = "No se pudo extraer la huella — habla más " +
                                "tiempo o más fuerte, e intenta de nuevo."
                        }
                        return@thread
                    }

                    val vector = (0 until spk.length()).map { spk.getDouble(it) }
                    val texto = vector.joinToString(",")
                    getSharedPreferences("notas_campo", MODE_PRIVATE)
                        .edit()
                        .putString("huella_voz", texto)
                        .apply()

                    runOnUiThread {
                        status.text = "Listo — tu huella de voz quedó guardada. " +
                            "Ya puedes cerrar esta pantalla."
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Error: ${e.message}" }
                }
            }
        }
    }
}
