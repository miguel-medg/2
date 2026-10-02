package com.miguel.epgprobe

import android.content.res.ColorStateList
import android.widget.ProgressBar
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Etapa 1: descarga la guia (guide.xml.gz) y el mapa de canales del release "guia"
 * y muestra, para cada canal de Xuper TV, que programa va ahora y cual sigue.
 */
class GuideActivity : Activity() {

    companion object {
        // Release publicado por el workflow "Generar guia" (el repo debe ser publico)
        const val BASE = "https://github.com/miguel-medg/2/releases/download/guia/"
        const val GUIDE = "guide.xml.gz"
        const val MAPA = "mapa_canales.json"
        const val MAX_AGE_MS = 6L * 60 * 60 * 1000 // vuelve a descargar si la copia tiene mas de 6 h
    }

    private class Prog(val start: Long, val stop: Long, val title: String)
    private class Row(val name: String, val id: String)

    private val handler = Handler(Looper.getMainLooper())
    private var progs: Map<String, List<Prog>> = emptyMap()
    private var allRows: List<Row> = emptyList()
    private var shown: List<Row> = emptyList()
    private var onlyWithData = true
    private var scale = 1.3f // factor de tamano del texto (se guarda)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    private lateinit var status: TextView
    private lateinit var refreshBtn: Button
    private lateinit var filterBtn: Button
    private lateinit var adapter: RowAdapter

    // Refresca "ahora / siguiente" cada 30 s sin volver a descargar nada
    private val tick = object : Runnable {
        override fun run() {
            adapter.notifyDataSetChanged()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scale = getSharedPreferences("guia", MODE_PRIVATE).getFloat("scale", 1.3f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(0xFF101418.toInt())
        }

        status = TextView(this).apply {
            textSize = 18f * scale
            setTextColor(Color.LTGRAY)
            text = "Cargando guía…"
        }

        refreshBtn = Button(this).apply {
            text = "Actualizar guía"
            textSize = 20f
            setOnClickListener { load(true) }
        }
        filterBtn = Button(this).apply {
            text = "Mostrar todos los canales"
            textSize = 20f
            setOnClickListener {
                onlyWithData = !onlyWithData
                text = if (onlyWithData) "Mostrar todos los canales" else "Solo canales con datos"
                applyFilter()
            }
        }
        val smallerBtn = Button(this).apply {
            text = "Texto −"
            textSize = 20f
            setOnClickListener { changeScale(-0.2f) }
        }
        val biggerBtn = Button(this).apply {
            text = "Texto +"
            textSize = 20f
            setOnClickListener { changeScale(0.2f) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refreshBtn)
            addView(filterBtn)
            addView(smallerBtn)
            addView(biggerBtn)
        }

        adapter = RowAdapter()
        val list = ListView(this).apply {
            this.adapter = this@GuideActivity.adapter
            isFocusable = true
            divider = null
        }

        root.addView(status)
        root.addView(buttons)
        root.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)

        load(false)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(tick, 30_000)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun changeScale(d: Float) {
        scale = (scale + d).coerceIn(0.8f, 2.4f)
        getSharedPreferences("guia", MODE_PRIVATE).edit().putFloat("scale", scale).apply()
        status.textSize = 18f * scale
        adapter.notifyDataSetChanged()
    }

    // ---------- carga de datos ----------

    private fun load(force: Boolean) {
        refreshBtn.isEnabled = false
        status.text = "Cargando guía…"
        thread {
            val g = File(filesDir, GUIDE)
            val m = File(filesDir, MAPA)
            var aviso = ""
            val vieja = !g.exists() || !m.exists() ||
                System.currentTimeMillis() - g.lastModified() > MAX_AGE_MS
            if (force || vieja) {
                val ok = download(GUIDE) && download(MAPA)
                if (!ok) {
                    aviso = if (g.exists() && m.exists()) {
                        "No se pudo actualizar; se usa la copia guardada. "
                    } else {
                        "No se pudo descargar la guía. Revisa la conexión y que el release sea público."
                    }
                }
            }
            if (g.exists() && m.exists()) {
                try {
                    val rows = readMapa(m)
                    val p = parseGuide(g)
                    val msg = aviso
                    runOnUiThread { show(rows, p, g.lastModified(), msg) }
                    return@thread
                } catch (e: Exception) {
                    aviso += "Error leyendo la guía: ${e.message}"
                }
            }
            val msg = aviso
            runOnUiThread {
                status.text = msg
                refreshBtn.isEnabled = true
            }
        }
    }

    private fun download(name: String): Boolean {
        return try {
            val c = URL(BASE + name).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) {
                false
            } else {
                val tmp = File(filesDir, "$name.tmp")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(File(filesDir, name))
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun readMapa(f: File): List<Row> {
        val o = JSONObject(f.readText())
        val rows = ArrayList<Row>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            rows.add(Row(k, o.getString(k)))
        }
        rows.sortBy { it.name.lowercase() }
        return rows
    }

    private fun parseGuide(f: File): Map<String, List<Prog>> {
        val out = HashMap<String, MutableList<Prog>>()
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        fun time(s: String?): Long = try {
            if (s == null) 0L else fmt.parse(s)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }

        GZIPInputStream(f.inputStream().buffered()).use { ins ->
            val p = Xml.newPullParser()
            p.setInput(ins, "UTF-8")
            var ev = p.eventType
            var ch: String? = null
            var start = 0L
            var stop = 0L
            var title: String? = null
            var inProg = false
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if (p.name == "programme") {
                        inProg = true
                        ch = p.getAttributeValue(null, "channel")
                        start = time(p.getAttributeValue(null, "start"))
                        stop = time(p.getAttributeValue(null, "stop"))
                        title = null
                    } else if (p.name == "title" && inProg && title == null) {
                        title = p.nextText()
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "programme") {
                    if (ch != null && title != null && start > 0 && stop > 0) {
                        out.getOrPut(ch) { mutableListOf() }.add(Prog(start, stop, title))
                    }
                    inProg = false
                }
                ev = p.next()
            }
        }
        out.values.forEach { l -> l.sortBy { it.start } }
        return out
    }

    private fun show(rows: List<Row>, p: Map<String, List<Prog>>, descargada: Long, aviso: String) {
        allRows = rows
        progs = p
        val conDatos = rows.count { p.containsKey(it.id) }
        val cuando = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(descargada))
        status.text = aviso + "$conDatos de ${rows.size} canales con datos · descargada $cuando"
        refreshBtn.isEnabled = true
        applyFilter()
    }

    private fun applyFilter() {
        shown = if (onlyWithData) allRows.filter { progs.containsKey(it.id) } else allRows
        adapter.notifyDataSetChanged()
    }

    // ---------- lista ----------

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = shown[position]
            val now = System.currentTimeMillis()
            val l = progs[r.id].orEmpty()
            val cur = l.firstOrNull { it.start <= now && now < it.stop }
            val nxt = l.firstOrNull { it.start > now }

            val box = LinearLayout(this@GuideActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
            }
            box.addView(TextView(this@GuideActivity).apply {
                text = r.name
                textSize = 26f * scale
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
                        box.addView(TextView(this@GuideActivity).apply {
                textSize = 22f * scale
                if (cur != null) {
                    text = "Ahora  ${timeFmt.format(Date(cur.start))}–${timeFmt.format(Date(cur.stop))}  ${cur.title}"
                    setTextColor(0xFF7CE38B.toInt())
                } else {
                    text = "Ahora  sin datos"
                    setTextColor(Color.GRAY)
                }
            })
            if (cur != null && cur.stop > cur.start) {
                val pct = ((now - cur.start) * 1000 / (cur.stop - cur.start))
                    .toInt().coerceIn(0, 1000)
                box.addView(
                    ProgressBar(this@GuideActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progress = pct
                        progressTintList = ColorStateList.valueOf(0xFF7CE38B.toInt())
                        progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 14
                    ).apply { topMargin = 8; bottomMargin = 4 }
                )
            }
            if (nxt != null) {
                box.addView(TextView(this@GuideActivity).apply {
                    textSize = 20f * scale
                    text = "Sigue  ${timeFmt.format(Date(nxt.start))}  ${nxt.title}"
                    setTextColor(Color.LTGRAY)
                })
            }
            return box
        }
    }
}
