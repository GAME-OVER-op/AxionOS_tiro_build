/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.settings.trigger

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Stable profiles stored independently of the currently foreground app. */
internal object TriggerProfileStore {
    private const val TAG = "TriggerProfileStore"
    private val directory = File("/data/system/nubia_triggers")
    private val target = File(directory, "profiles.conf")

    data class Binding(val enabled: Boolean = true, val valid: Boolean = false,
                       val x: Float = 0f, val y: Float = 0f)
    data class Profile(val pkg: String, val rotation: Int = 0,
                       val left: Binding = Binding(), val right: Binding = Binding())

    fun allowedPackage(pkg: String): Boolean = pkg.length in 1..220 &&
        pkg.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' }

    @Synchronized fun get(pkg: String): Profile = load().firstOrNull { it.pkg == pkg }
        ?: Profile(pkg)

    @Synchronized fun load(): List<Profile> {
        if (!target.isFile) return emptyList()
        val profiles = mutableListOf<Profile>()
        try {
            target.forEachLine { line ->
                if (!line.startsWith("profile=")) return@forEachLine
                val parts = line.substringAfter('=').split('|')
                if (parts.size != 10 || !allowedPackage(parts[0])) return@forEachLine
                val rotation = parts[9].toIntOrNull() ?: return@forEachLine
                if (rotation !in 0..3) return@forEachLine
                fun binding(start: Int): Binding? {
                    val x = parts[start + 2].toFloatOrNull() ?: return null
                    val y = parts[start + 3].toFloatOrNull() ?: return null
                    if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f)
                        return null
                    return Binding(parts[start] == "1", parts[start + 1] == "1", x, y)
                }
                val l = binding(1) ?: return@forEachLine
                val r = binding(5) ?: return@forEachLine
                profiles.removeAll { it.pkg == parts[0] }
                profiles += Profile(parts[0], rotation, l, r)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read profiles", e)
            return emptyList()
        }
        return profiles
    }

    @Synchronized fun put(profile: Profile): Boolean {
        if (!allowedPackage(profile.pkg) || profile.rotation !in 0..3) return false
        val all = load().filterNot { it.pkg == profile.pkg }.toMutableList()
        all += profile
        return save(all)
    }

    @Synchronized fun delete(pkg: String): Boolean = save(load().filterNot { it.pkg == pkg })

    private fun save(all: List<Profile>): Boolean {
        val content = buildString {
            appendLine("version=1")
            all.forEach { p ->
                fun b(v: Binding): String = "${if (v.enabled) 1 else 0}|" +
                    "${if (v.valid) 1 else 0}|" +
                    String.format(Locale.US, "%.6f|%.6f", v.x, v.y)
                appendLine("profile=${p.pkg}|${b(p.left)}|${b(p.right)}|${p.rotation}")
            }
        }
        return atomicWrite(target, content)
    }

    fun atomicWrite(file: File, content: String): Boolean {
        if (!directory.isDirectory && !directory.mkdirs()) {
            Log.e(TAG, "Cannot create trigger configuration directory")
            return false
        }
        val temp = File(directory, file.name + ".tmp")
        return try {
            FileOutputStream(temp).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temp.renameTo(file)) error("rename failed: ${file.path}")
            true
        } catch (e: Exception) {
            temp.delete()
            Log.e(TAG, "Could not save ${file.name}", e)
            false
        }
    }
}
