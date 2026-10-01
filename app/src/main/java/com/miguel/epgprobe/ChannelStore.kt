package com.miguel.epgprobe

import android.content.Context
import java.io.File

/** Guarda en un archivo los nombres de canal vistos (nombre<TAB>numero). */
object ChannelStore {
    private val map = LinkedHashMap<String, String>()
    private var loaded = false
    private var dirty = false

    private fun file(ctx: Context) = File(ctx.filesDir, "canales.txt")

    private fun ensure(ctx: Context) {
        if (loaded) return
        val f = file(ctx)
        if (f.exists()) {
            f.readLines().forEach { line ->
                if (line.isNotBlank()) {
                    val p = line.split("\t")
                    map[p[0]] = p.getOrElse(1) { "" }
                }
            }
        }
        loaded = true
    }

    /** Devuelve true si algo cambió. */
    @Synchronized
    fun add(ctx: Context, name: String, index: String): Boolean {
        ensure(ctx)
        val old = map[name]
        if (old == null) {
            map[name] = index
            dirty = true
            return true
        }
        if (old.isEmpty() && index.isNotEmpty()) {
            map[name] = index
            dirty = true
            return true
        }
        return false
    }

    @Synchronized
    fun flush(ctx: Context) {
        if (!dirty) return
        file(ctx).writeText(textLocked())
        dirty = false
    }

    @Synchronized
    fun count(ctx: Context): Int {
        ensure(ctx)
        return map.size
    }

    @Synchronized
    fun text(ctx: Context): String {
        ensure(ctx)
        return textLocked()
    }

    @Synchronized
    fun last(ctx: Context, n: Int): List<String> {
        ensure(ctx)
        return map.keys.toList().takeLast(n)
    }

    @Synchronized
    fun clear(ctx: Context) {
        ensure(ctx)
        map.clear()
        dirty = false
        file(ctx).writeText("")
    }

    private fun textLocked(): String =
        map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
}
