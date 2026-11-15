// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.support

import android.content.Context
import android.net.Uri
import android.os.Build
import com.vantaview.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A log file for reporting problems: device details and VantaView's own recent log lines, with logins,
 * keys and tokens blanked out so the file is safe to share.
 */
object LogExport {
    suspend fun write(context: Context, to: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val pid = android.os.Process.myPid()
            val lines = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "4000", "--pid=$pid"))
                .inputStream.bufferedReader().readLines()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            val header = listOf(
                "VantaView ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "Memory: ${mem.totalMem / 1_048_576} MB${if (am.isLowRamDevice) " (low-memory device)" else ""}",
                "TV: ${context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)}",
                "Saved: ${java.util.Date()}",
                "",
            )
            context.contentResolver.openOutputStream(to)!!.bufferedWriter().use { w ->
                (header + lines.map(::redact)).forEach { w.write(it); w.newLine() }
            }
            lines.size
        }
    }

    private val patterns = listOf(
        // Xtream-style logins in links: /live/user/pass/, ?username=…&password=…
        Regex("""(/(?:live|movie|series|timeshift)/)[^/\s]+/[^/\s]+/""", RegexOption.IGNORE_CASE) to "$1***/***/",
        Regex("""((?:username|password|user|pass|token|apikey|api_key|key|auth|mac|sn)=)[^&\s"']+""", RegexOption.IGNORE_CASE) to "$1***",
        Regex("""(Bearer\s+)[A-Za-z0-9._\-]+""") to "$1***",
        // Add-on links often carry a debrid key inside the path.
        Regex("""(https?://[^/\s]+/)[A-Za-z0-9%=_\-]{24,}(/)""") to "$1***$2",
        Regex("""\b[A-Za-z0-9]{32,}\b""") to "***",
        Regex("""\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""") to "**:**:**:**:**:**",
    )

    fun redact(line: String): String = patterns.fold(line) { acc, (re, rep) -> re.replace(acc, rep) }
}
