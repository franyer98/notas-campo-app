package com.franyer.notascampo

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val pendientesAntes = NotaUploader.carpetaPendientes(applicationContext).listFiles()?.size ?: 0
        if (pendientesAntes == 0) return Result.success()

        NotaUploader.reintentarPendientes(applicationContext)
        val quedan = NotaUploader.carpetaPendientes(applicationContext).listFiles()?.size ?: 0

        // Si algo quedó pendiente (sigue sin señal o el servidor falló),
        // WorkManager reintenta automáticamente con backoff exponencial.
        return if (quedan == 0) Result.success() else Result.retry()
    }
}
