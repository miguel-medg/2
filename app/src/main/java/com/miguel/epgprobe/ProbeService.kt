package com.miguel.epgprobe

import android.content.res.ColorStateList
import android.widget.ProgressBar
import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lee solo Xuper TV (com.lite.fczx):
 *  - mChannelName / mTextChannelIndex: canal en reproduccion
 *  - mTvChannelName: nombres de la lista lateral de canales
 * Guarda los nombres vistos y muestra un cartel grande con el canal actual,
 * el programa que va ahora y el que sigue (si el canal tiene guia).
 */
class ProbeService : AccessibilityService() {

    companion object {
        const val XUPER_PKG = "com.lite.fczx"
        const val SHOW_MS = 8000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var box: LinearLayout? = null
    private var tvChannel: TextView? = null
    private var tvNow: TextView? = null
    private var tvNext: TextView? = null
    private var barNow: ProgressBar? = null
    private var lastSignature = ""

    private val scanRunnable = Runnable { scanScreen() }
    private val hideRunnable = Runnable { box?.visibility = View.GONE }

    private class Found {
        var name: String? = null
        var index: String? = null
        val listNames = ArrayList<String>()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != XUPER_PKG) return
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 300)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        box?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        box = null
        super.onDestroy()
    }

    private fun scanScreen() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != XUPER_PKG) return

        val found = Found()
        walk(root, found, 0)

        var changed = false
        found.name?.let {
            if (ChannelStore.add(this, it, found.index ?: "")) changed = true
        }
        for (n in found.listNames) {
            if (ChannelStore.add(this, n, "")) changed = true
        }
        if (changed) ChannelStore.flush(this)

        val name = found.name ?: return

        // Carga la guia en segundo plano; al terminar, vuelve a mostrar el cartel
        GuideData.ensureLoaded(this) {
            handler.post {
                lastSignature = ""
                handler.removeCallbacks(scanRunnable)
                handler.postDelayed(scanRunnable, 300)
            }
        }

        val info = GuideData.lookup(name)
        val cur = info?.cur
        val nxt = info?.next

        val signature = "${found.index}|$name|${cur?.title}|${GuideData.ready}"
        if (signature == lastSignature) return
        lastSignature = signature

        val num = found.index?.let { "$it  " } ?: ""
        val nowLine: String
        var nowColor = 0xFF7CE38B.toInt()
        when {
            !GuideData.ready -> {
                nowLine = "Cargando guía…"
                nowColor = Color.LTGRAY
            }
            info == null -> {
                nowLine = "Sin guía para este canal"
                nowColor = Color.GRAY
            }
            cur == null -> {
                nowLine = "Sin datos ahora"
                nowColor = Color.GRAY
            }
            else -> {
                nowLine = "Ahora  ${timeFmt.format(Date(cur.start))}–${timeFmt.format(Date(cur.stop))}  ${cur.title}"
            }
        }
        val nextLine = nxt?.let { "Sigue  ${timeFmt.format(Date(it.start))}  ${it.title}" }

                val pct = if (cur != null && cur.stop > cur.start) {
            ((System.currentTimeMillis() - cur.start) * 1000 / (cur.stop - cur.start))
                .toInt().coerceIn(0, 1000)
        } else -1

        showBanner("$num$name", nowLine, nowColor, pct, nextLine)
    }

    private fun walk(node: AccessibilityNodeInfo?, f: Found, depth: Int) {
        if (node == null || depth > 30) return
        val id = node.viewIdResourceName?.substringAfter(":id/")
        val t = node.text?.toString()?.trim()
        if (!id.isNullOrEmpty() && !t.isNullOrEmpty()) {
            when (id) {
                "mChannelName" -> f.name = t
                "mTextChannelIndex" -> f.index = t
                "mTvChannelName" -> f.listNames.add(t)
            }
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), f, depth + 1)
        }
    }

        private fun showBanner(channel: String, now: String, nowColor: Int, progress: Int, next: String?) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // Mismo tamano de letra que eliges en la pantalla de la guia (Texto − / Texto +)
        val scale = getSharedPreferences("guia", MODE_PRIVATE).getFloat("scale", 1.3f)

        if (box == null) {
            val c = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }
            val n = TextView(this)
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(0xFF7CE38B.toInt())
                progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            }
            val nx = TextView(this).apply { setTextColor(Color.LTGRAY) }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xDD000000.toInt())
                setPadding(32, 20, 32, 20)
                addView(c)
                addView(n)
                addView(
                    bar,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 16
                    ).apply { topMargin = 8; bottomMargin = 8 }
                )
                addView(nx)
            }
            val width = (resources.displayMetrics.widthPixels * 0.75f).toInt()
            val lp = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 40
            }
            wm.addView(layout, lp)
            box = layout
            tvChannel = c
            tvNow = n
            barNow = bar
            tvNext = nx
        }

        tvChannel?.apply { text = channel; textSize = 28f * scale }
        tvNow?.apply { text = now; setTextColor(nowColor); textSize = 24f * scale }
        barNow?.apply {
            if (progress >= 0) {
                this.progress = progress
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        tvNext?.apply {
            textSize = 20f * scale
            if (next != null) {
                text = next
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        box?.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, SHOW_MS)
    }
}
