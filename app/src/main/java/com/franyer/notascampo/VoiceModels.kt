package com.franyer.notascampo

import android.content.Context
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import java.io.FileOutputStream

object VoiceModels {

    @Volatile private var modeloVoz: Model? = null
    @Volatile private var modeloHablante: SpeakerModel? = null
    @Volatile private var cargando = false
    private val callbacksPendientes = mutableListOf<(Model, SpeakerModel) -> Unit>()

    /** Llama al callback cuando ambos modelos estén listos (puede ser inmediato si ya se cargaron antes). */
    @Synchronized
    fun obtener(context: Context, callback: (Model, SpeakerModel) -> Unit) {
        val voz = modeloVoz
        val hablante = modeloHablante
        if (voz != null && hablante != null) {
            callback(voz, hablante)
            return
        }
        callbacksPendientes.add(callback)
        if (cargando) return
        cargando = true

        Thread {
            try {
                val dirVoz = copiarAssetsRecursivo(context, "model-es", File(context.filesDir, "model-es"))
                val dirHablante = copiarAssetsRecursivo(context, "model-spk", File(context.filesDir, "model-spk"))
                val voz2 = Model(dirVoz.absolutePath)
                val hablante2 = SpeakerModel(dirHablante.absolutePath)
                synchronized(this) {
                    modeloVoz = voz2
                    modeloHablante = hablante2
                    callbacksPendientes.forEach { it(voz2, hablante2) }
                    callbacksPendientes.clear()
                    cargando = false
                }
            } catch (e: Exception) {
                cargando = false
                // Los callbacks pendientes se quedan sin llamar — quien
                // pidió el modelo debería tener su propio timeout/aviso.
            }
        }.start()
    }

    /** Copia una carpeta de assets al almacenamiento interno solo si no existe ya (evita recopiar en cada arranque). */
    private fun copiarAssetsRecursivo(context: Context, assetPath: String, destino: File): File {
        if (destino.exists() && destino.listFiles()?.isNotEmpty() == true) return destino
        destino.mkdirs()
        copiarRecursivoInterno(context, assetPath, destino)
        return destino
    }

    private fun copiarRecursivoInterno(context: Context, assetPath: String, destino: File) {
        val am = context.assets
        val items = am.list(assetPath) ?: emptyArray()
        if (items.isEmpty()) {
            am.open(assetPath).use { input ->
                FileOutputStream(destino).use { output -> input.copyTo(output) }
            }
        } else {
            destino.mkdirs()
            for (item in items) {
                copiarRecursivoInterno(context, "$assetPath/$item", File(destino, item))
            }
        }
    }
}
