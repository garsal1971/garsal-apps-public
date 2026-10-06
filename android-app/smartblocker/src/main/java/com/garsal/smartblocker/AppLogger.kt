package com.garsal.smartblocker

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {

    private const val FILE_NAME = "smartblocker.log"
    private const val MAX_BYTES = 150_000L  // ~150 KB, poi ruota
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun log(ctx: Context, tag: String, msg: String) {
        try {
            val f = file(ctx)
            if (f.exists() && f.length() > MAX_BYTES) {
                // Tieni solo la seconda metà del file
                val lines = f.readLines()
                f.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
            }
            val ts = sdf.format(Date())
            f.appendText("$ts [$tag] $msg\n")
        } catch (_: Exception) {}
    }

    fun read(ctx: Context): String {
        return try { file(ctx).readText() } catch (_: Exception) { "(nessun log)" }
    }

    fun clear(ctx: Context) {
        try { file(ctx).writeText("") } catch (_: Exception) {}
    }

    fun path(ctx: Context) = file(ctx).absolutePath
}
