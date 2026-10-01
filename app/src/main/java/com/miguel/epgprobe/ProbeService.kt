package com.miguel.epgprobe

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * Lee solo Xuper TV (com.lite.fczx):
 *  - mChannelName / mTextChannelIndex: canal en reproduccion
 *  - mTvChannelName: nombres de la lista lateral de canales
 * Guarda todos los nombres vistos y muestra un recuadro con el canal actual.
 */
class ProbeService : AccessibilityService() {

    companion object {
        const val XUPER_PKG = "com.lite.fczx"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    private var lastSignature = ""

    private val scanRunnable = Runnable { scanScreen() }
    private val hideRunnable = Runnable { overlay?.visibility = View.GONE }

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
        overlay?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
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
        val total = ChannelStore.count(this)
        val signature = "${found.index}|$name|$total"
        if (signature == lastSignature) return
        lastSignature = signature

        val num = found.index?.let { "$it  " } ?: ""
        showOverlay("$num$name\nCanales guardados: $total")
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

    private fun showOverlay(text: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (overlay == null) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xCC000000.toInt())
                textSize = 16f
                maxWidth = 800
                setPadding(24, 16, 24, 16)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
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
            wm.addView(tv, lp)
            overlay = tv
        }
        overlay?.text = text
        overlay?.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, 4000)
    }
}
