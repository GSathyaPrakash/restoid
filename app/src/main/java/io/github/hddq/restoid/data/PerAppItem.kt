package io.github.hddq.restoid.data

import java.security.MessageDigest

/**
 * Identifies a single backup target in per-app repository mode.
 *
 * Each item is backed up into its own restic repository nested under the
 * selected (base) repository's path. [slug] is the filesystem-safe subdirectory
 * name used to derive that nested repository's path; [displayName] is what we
 * show to the user.
 */
sealed class PerAppItem {
    abstract val slug: String
    abstract val displayName: String

    /** An installed application, identified by its package name. */
    data class App(val packageName: String) : PerAppItem() {
        override val slug: String get() = sanitizeSegment(packageName)
        override val displayName: String get() = packageName
    }

    /**
     * A user-selected custom directory, identified by its SAF tree URI.
     * The slug is derived from the directory's name plus a short hash of the URI
     * so that two directories sharing a name never collide.
     */
    data class CustomDir(val uri: String) : PerAppItem() {
        override val displayName: String = lastSegment(uri)
        override val slug: String = "${sanitizeSegment(displayName)}_${shortHash(uri)}"
    }

    companion object {
        fun sanitizeSegment(name: String): String =
            name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "item" }

        private fun lastSegment(uri: String): String {
            val trimmed = uri.trim().trimEnd('/')
            val seg = trimmed.substringAfterLast('/').substringAfterLast(':')
            return if (seg.isBlank()) trimmed.ifBlank { "directory" } else seg
        }

        private fun shortHash(s: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(8)
    }
}

/**
 * Derives nested per-app repository paths from a base (selected) repository.
 *
 * The same "/<slug>" append rule works for every backend, because restic treats
 * the trailing path component as a hierarchical location:
 *  - LOCAL  : /backups            -> /backups/com.whatsapp
 *  - SFTP   : sftp:h@h:/backups   -> sftp:h@h:/backups/com.whatsapp
 *  - REST   : rest:http://h/b     -> rest:http://h/b/com.whatsapp
 *  - S3     : s3:bucket/prefix    -> s3:bucket/prefix/com.whatsapp
 */
object PerAppRepositoryResolver {
    fun deriveRepoPath(base: LocalRepository, item: PerAppItem): String =
        deriveRepoPath(base, item.slug)

    /** Derive a nested repository path from an explicit [slug] (e.g. read from the registry). */
    fun deriveRepoPath(base: LocalRepository, slug: String): String {
        val basePath = base.path.trimEnd('/')
        return if (basePath.isEmpty()) slug else "$basePath/$slug"
    }
}
