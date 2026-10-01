package com.miguel.epgprobe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        val help = TextView(this).apply {
            textSize = 22f
            text = "1) Pulsa \"Abrir accesibilidad\" y activa EPG Probe.\n" +
                "2) Abre Xuper TV y cambia de canal.\n" +
                "3) Mira el recuadro arriba a la izquierda (dura 8 s).\n" +
                "4) Vuelve aquí y pulsa \"Actualizar registro\"."
        }

        val guideBtn = Button(this).apply {
            text = "Abrir guía (ahora / siguiente)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, GuideActivity::class.java))
            }
        }

        val openBtn = Button(this).apply {
            text = "Abrir accesibilidad"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ve a Ajustes > Accesibilidad y activa EPG Probe",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        val refreshBtn = Button(this).apply {
            text = "Actualizar registro"
            setOnClickListener { showLog() }
        }
        val clearBtn = Button(this).apply {
            text = "Borrar registro"
            setOnClickListener {
                logFile().writeText("")
                showLog()
            }
        }

        logView = TextView(this).apply {
            textSize = 18f
            isFocusable = true
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        listOf(guideBtn, openBtn, refreshBtn, clearBtn).forEach { it.textSize = 22f }

        layout.addView(guideBtn)
        layout.addView(help)
        layout.addView(openBtn)
        layout.addView(refreshBtn)
        layout.addView(clearBtn)
        layout.addView(scroll)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        showLog()
    }

    private fun logFile() = File(getExternalFilesDir(null), "probe_log.txt")

    private fun showLog() {
        val f = logFile()
        val text = if (f.exists()) f.readText() else ""
        logView.text = if (text.isEmpty()) "(registro vacío)" else text.takeLast(6000)
    }
}
