package com.miguel.epgprobe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        val help = TextView(this).apply {
            textSize = 15f
            text = "1) Pulsa \"Abrir accesibilidad\" y activa EPG Probe.\n" +
                "2) Abre Xuper TV, abre la lista de canales y recórrela con el control (todas las categorías).\n" +
                "3) Vuelve aquí: verás cuántos canales se guardaron.\n" +
                "4) Con este box y tu PC en la misma red Wi-Fi, abre en la PC la dirección que aparece abajo para descargar la lista."
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
        val clearBtn = Button(this).apply {
            text = "Borrar lista de canales"
            setOnClickListener {
                ChannelStore.clear(this@MainActivity)
                refresh()
            }
        }

        status = TextView(this).apply {
            textSize = 14f
            isFocusable = true
        }
        val scroll = ScrollView(this).apply { addView(status) }

        layout.addView(help)
        layout.addView(openBtn)
        layout.addView(clearBtn)
        layout.addView(scroll)
        setContentView(layout)

        startServer()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        super.onDestroy()
    }

    private fun refresh() {
        val n = ChannelStore.count(this)
        val last = ChannelStore.last(this, 8).joinToString("\n")
        status.text = "Canales guardados: $n\n\n" +
            "Descarga en la PC: http://${localIp()}:8080\n\n" +
            "Últimos guardados:\n$last"
    }

    private fun localIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        return a.hostAddress ?: "?"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "?"
    }

    private fun startServer() {
        if (server != null) return
        thread(isDaemon = true) {
            try {
                val s = ServerSocket(8080)
                server = s
                while (!s.isClosed) {
                    val c = s.accept()
                    thread(isDaemon = true) { handle(c) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(c: Socket) {
        try {
            c.use {
                it.getInputStream().bufferedReader().readLine()
                val body = ChannelStore.text(this).toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Disposition: attachment; filename=\"canales_xuper.txt\"\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out = it.getOutputStream()
                out.write(header.toByteArray())
                out.write(body)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }
}
