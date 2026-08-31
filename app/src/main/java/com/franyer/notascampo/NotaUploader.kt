package com.franyer.notascampo

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Cola local de notas pendientes de subir. Cuando falla el envío (sin
 * señal, timeout, error del servidor), el audio se mueve a la carpeta
 * "pendientes" con el nombre codificando sus metadatos, en vez de perderse.
 * UploadWorker reintenta periódicamente y con cada cambio de conectividad.
 */
object NotaUploader {

    private const val BACKEND_URL = "https://notas-campo.onrender.com/notas/upload"
    private val client = OkHttpClient()

    fun carpetaPendientes(context: Context): File {
        val dir = File(context.filesDir, "pendientes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Codifica los metadatos en el nombre del archivo: id__usuario__fechaIso.m4a */
    fun nombrePendiente(taskerId: String, usuario: String, fechaIso: String): String {
        val fechaSegura = fechaIso.replace(":", "-")
        return "$taskerId__${usuario}__$fechaSegura.m4a"
    }

    private fun parsearNombre(nombre: String): Triple<String, String, String>? {
        val partes = nombre.removeSuffix(".m4a").split("__")
        if (partes.size != 3) return null
        val crudo = partes[2] // ej: 2026-08-30T19-41-19
        val fecha = crudo.substringBefore("T")
        val horaSegura = crudo.substringAfter("T")
        val fechaIso = "$fecha" + "T" + horaSegura.replace("-", ":")
        return Triple(partes[0], partes[1], fechaIso)
    }

    /**
     * Intenta subir un audio. Devuelve true si el servidor lo aceptó.
     * No lanza excepciones — cualquier fallo de red o del servidor
     * devuelve false para que el llamador decida encolar el reintento.
     */
    fun subir(archivo: File, taskerId: String, usuario: String, fechaIso: String): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("tasker_id", taskerId)
                .addFormDataPart("usuario", usuario)
                .addFormDataPart("creado_en_dispositivo", fechaIso)
                .addFormDataPart(
                    "audio", archivo.name,
                    archivo.asRequestBody("audio/mp4".toMediaType())
                )
                .build()

            val request = Request.Builder().url(BACKEND_URL).post(body).build()
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Mueve un audio recién grabado a la cola de pendientes. */
    fun encolar(context: Context, archivoOrigen: File, usuario: String): File {
        val taskerId = UUID.randomUUID().toString()
        val fechaIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        val destino = File(carpetaPendientes(context), nombrePendiente(taskerId, usuario, fechaIso))
        archivoOrigen.copyTo(destino, overwrite = true)
        archivoOrigen.delete()
        return destino
    }

    /** Recorre la cola y reintenta cada nota pendiente. Borra las que sí se suban. */
    fun reintentarPendientes(context: Context): Int {
        var enviadas = 0
        val carpeta = carpetaPendientes(context)
        carpeta.listFiles()?.forEach { archivo ->
            val metadatos = parsearNombre(archivo.name) ?: return@forEach
            val (taskerId, usuario, fechaIso) = metadatos
            if (subir(archivo, taskerId, usuario, fechaIso)) {
                archivo.delete()
                enviadas++
            }
        }
        return enviadas
    }
}
