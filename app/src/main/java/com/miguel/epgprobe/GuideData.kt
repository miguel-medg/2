package com.miguel.epgprobe

import android.content.Context
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Guia en memoria para el servicio de accesibilidad.
 * Usa los mismos archivos que GuideActivity (guide.xml.gz y mapa_canales.json en filesDir),
 * y los descarga solos si faltan o tienen mas de 6 h.
 */
object GuideData {

    class Prog(val start: Long, val stop: Long, val title: String)
    class Result(val cur: Prog?, val next: Prog?)

    private const val RETRY_MS = 5L * 60 * 1000

    @Volatile var ready = false
        private set
    @Volatile private var loading = false
    private var lastAttempt = 0L
    private var loadedAt = 0L

    private var progs: Map<String, List<Prog>> = emptyMap()
    private var exact: Map<String, String> = emptyMap() // nombre en minusculas -> id
    private var loose: Map<String, String> = emptyMap() // nombre sin HD/acentos/simbolos -> id

    /** Carga en segundo plano. onReady se llama (desde otro hilo) solo cuando termina bien. */
    fun ensureLoaded(ctx: Context, onReady: () -> Unit) {
        val now = System.currentTimeMillis()
        if (loading) return
        if (ready && now - loadedAt < GuideActivity.MAX_AGE_MS) return
        if (now - lastAttempt < RETRY_MS) return
        loading = true
        lastAttempt = now
        val app = ctx.applicationContext
        thread {
            try {
                val g = File(app.filesDir, GuideActivity.GUIDE)
                val m = File(app.filesDir, GuideActivity.MAPA)
                val vieja = !g.exists() || !m.exists() ||
                    System.currentTimeMillis() - g.lastModified() > GuideActivity.MAX_AGE_MS
                if (vieja) {
                    download(app, GuideActivity.GUIDE)
                    download(app, GuideActivity.MAPA)
                }
                if (g.exists() && m.exists()) {
                    val mapa = readMapa(m)
                    val wanted = mapa.values.toSet()
                    val p = parseGuide(g, wanted)
                    val ex = HashMap<String, String>()
                    val lo = HashMap<String, String>()
                    for ((n, id) in mapa) {
                        ex[n.trim().lowercase()] = id
                        lo.putIfAbsent(loose(n), id)
                    }
                    progs = p
                    exact = ex
                    loose = lo
                    loadedAt = System.currentTimeMillis()
                    ready = true
                    onReady()
                }
            } catch (_: Exception) {
            } finally {
                loading = false
            }
        }
    }

    /** null = el canal no esta en el mapa (sin guia). */
    fun lookup(channelName: String): Result? {
        if (!ready) return null
        val id = exact[channelName.trim().lowercase()] ?: loose[loose(channelName)] ?: return null
        val l = progs[id].orEmpty()
        val now = System.currentTimeMillis()
        val cur = l.firstOrNull { it.start <= now && now < it.stop }
        val nxt = l.firstOrNull { it.start > now }
        return Result(cur, nxt)
    }

    private fun loose(s: String): String {
        val t = Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
        return t.split(" ")
            .filter { it.isNotEmpty() && it != "hd" && it != "fhd" }
            .joinToString(" ")
    }

    private fun download(ctx: Context, name: String): Boolean {
        return try {
            val c = URL(GuideActivity.BASE + name).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) {
                false
            } else {
                val tmp = File(ctx.filesDir, "$name.tmp")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(File(ctx.filesDir, name))
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun readMapa(f: File): Map<String, String> {
        val o = JSONObject(f.readText())
        val out = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.getString(k)
        }
        return out
    }

    private fun parseGuide(f: File, wanted: Set<String>): Map<String, List<Prog>> {
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
                    if (ch != null && ch in wanted && title != null && start > 0 && stop > 0) {
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
}
