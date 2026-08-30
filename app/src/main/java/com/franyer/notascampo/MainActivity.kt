package com.franyer.notascampo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val permissionsNeeded = arrayOf(
        Manifest.permission.RECORD_AUDIO,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI simple hecha por código, sin XML de layout, para mantener el
        // proyecto mínimo y fácil de mantener.
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val status = TextView(this).apply {
            textSize = 16f
        }

        val btnPermisos = Button(this).apply {
            text = "1. Conceder permisos"
            setOnClickListener { pedirPermisos() }
        }

        val btnOverlay = Button(this).apply {
            text = "2. Permitir burbuja flotante"
            setOnClickListener { pedirPermisoOverlay() }
        }

        val btnIniciar = Button(this).apply {
            text = "3. Activar burbuja"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, BubbleService::class.java))
                status.text = "Burbuja activada. Minimiza la app y búscala flotando en pantalla."
            }
        }

        layout.addView(status)
        layout.addView(btnPermisos)
        layout.addView(btnOverlay)
        layout.addView(btnIniciar)
        setContentView(layout)
    }

    private fun pedirPermisos() {
        val faltantes = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltantes.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), 100)
        }
    }

    private fun pedirPermisoOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}
