package dev.wplayer.app.data

/** A folder on the way to the current one; [path] is in the core's `/a/b` form. */
data class PathSegment(val name: String, val path: String)

/** The folders from the source root (excluded) down to [path] (included). */
fun pathSegments(path: String): List<PathSegment> {
    val names = path.split('/').filter { it.isNotEmpty() }
    return names.indices.map { i -> PathSegment(names[i], "/" + names.subList(0, i + 1).joinToString("/")) }
}

/** The folder containing [path], or null at the source root. */
fun parentPath(path: String): String? {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return null
    return trimmed.substringBeforeLast('/').ifEmpty { "/" }
}

/** The folder holding [path]; the root holds itself. */
fun folderOf(path: String): String = parentPath(path) ?: "/"
