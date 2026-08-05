package io.github.hddq.restoid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
enum class PerAppItemKind { APP, CUSTOM_DIR }

/**
 * A backed-up item in per-app repository mode: an app (by package name) or a
 * custom directory (by SAF tree URI), plus the [slug] used to derive its nested
 * repository path and a human-readable [displayName] for the UI.
 */
@Serializable
data class PerAppItemDescriptor(
    val slug: String,
    val displayName: String,
    val kind: PerAppItemKind,
    val packageName: String? = null,
    val customDirUri: String? = null
)

/**
 * Persists the set of per-app / per-directory repositories that have been
 * created under a given base (selected) repository, keyed by the base
 * repository's key (its path).
 *
 * Restic cannot list repositories, so for remote backends (SFTP/REST/S3) we
 * cannot scan to discover items. Instead we remember each item here when it is
 * first backed up, and forget it when its history is deleted. This is pure file
 * I/O with no in-memory state, so any number of instances (constructed from a
 * [Context]) are consistent with each other.
 */
class PerAppItemRegistry(context: Context) {
    private val rootDir = File(context.filesDir, "per_app_items")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PerAppItemDescriptor.serializer())

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    suspend fun getItems(baseKey: String): List<PerAppItemDescriptor> = withContext(Dispatchers.IO) {
        readItems(baseKey)
    }

    suspend fun addItem(baseKey: String, descriptor: PerAppItemDescriptor) = withContext(Dispatchers.IO) {
        val current = readItems(baseKey).toMutableList()
        val index = current.indexOfFirst { it.slug == descriptor.slug }
        if (index >= 0) current[index] = descriptor else current.add(descriptor)
        writeItems(baseKey, current)
    }

    suspend fun removeItem(baseKey: String, slug: String) = withContext(Dispatchers.IO) {
        writeItems(baseKey, readItems(baseKey).filter { it.slug != slug })
    }

    private fun readItems(baseKey: String): List<PerAppItemDescriptor> {
        val file = fileFor(baseKey)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(serializer, file.readText()) }
            .getOrDefault(emptyList())
    }

    private fun writeItems(baseKey: String, items: List<PerAppItemDescriptor>) {
        fileFor(baseKey).writeText(json.encodeToString(serializer, items))
    }

    private fun fileFor(baseKey: String): File =
        File(rootDir, "${shortHash(baseKey)}.json")

    private fun shortHash(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
