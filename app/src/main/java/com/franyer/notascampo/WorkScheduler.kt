package com.franyer.notascampo

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WorkScheduler {

    private const val NOMBRE_TRABAJO_PERIODICO = "reintento_notas_periodico"

    private fun restriccionRed() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Llamar justo después de que una subida falle: encola un intento
     * inmediato que WorkManager ejecuta en cuanto detecta señal, sin
     * esperar al ciclo periódico de 15 minutos.
     */
    fun programarReintento(context: Context) {
        val solicitud = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(restriccionRed())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(solicitud)
    }

    /**
     * Respaldo: aunque la app nunca detecte el fallo original (por
     * ejemplo, si el proceso muere), este trabajo periódico revisa la
     * carpeta de pendientes cada 15 minutos (el mínimo que permite
     * WorkManager) mientras haya conexión.
     */
    fun programarRevisionPeriodica(context: Context) {
        val solicitud = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(restriccionRed())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NOMBRE_TRABAJO_PERIODICO,
            ExistingPeriodicWorkPolicy.KEEP,
            solicitud
        )
    }
}
