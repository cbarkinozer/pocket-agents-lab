package com.pocketagentslab

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

internal data class LocalFileMatch(
    val uri: Uri,
    val name: String,
    val relativePath: String,
    val excerpt: String,
    val score: Int,
)

internal suspend fun searchDocumentTree(
    context: Context,
    treeUri: Uri,
    query: String,
): List<LocalFileMatch> {
    val terms = fileSearchTerms(query)
    if (terms.isEmpty()) return emptyList()
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("The selected folder is unavailable")
    val candidates = mutableListOf<LocalFileMatch>()
    var inspected = 0

    fun visit(directory: DocumentFile, path: String, depth: Int) {
        if (depth > MAX_FOLDER_DEPTH || inspected >= MAX_FOLDER_FILES) return
        directory.listFiles().forEach { entry ->
            if (inspected >= MAX_FOLDER_FILES) return@forEach
            val name = entry.name ?: return@forEach
            val childPath = if (path.isBlank()) name else "$path/$name"
            if (entry.isDirectory) {
                visit(entry, childPath, depth + 1)
            } else if (entry.isFile) {
                inspected++
                val searchableText = if (isReadableTextFile(name, entry.type)) {
                    runCatching {
                        context.contentResolver.openInputStream(entry.uri)?.use { input ->
                            input.readNBytes(MAX_INDEXED_FILE_BYTES).toString(Charsets.UTF_8)
                        }.orEmpty()
                    }.getOrDefault("")
                } else ""
                val score = scoreLocalFile(name, searchableText, terms)
                if (score > 0) {
                    candidates += LocalFileMatch(
                        uri = entry.uri,
                        name = name,
                        relativePath = childPath,
                        excerpt = matchingExcerpt(searchableText, terms),
                        score = score,
                    )
                }
            }
        }
    }

    visit(root, "", 0)
    return candidates.sortedWith(compareByDescending<LocalFileMatch> { it.score }.thenBy { it.relativePath }).take(20)
}

internal fun fileSearchTerms(query: String): Set<String> {
    val stopWords = setOf("a", "an", "the", "file", "document", "find", "search", "for", "about", "with", "in", "my")
    return query.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 && it !in stopWords }
        .toSet()
}

internal fun scoreLocalFile(name: String, text: String, terms: Set<String>): Int {
    val lowerName = name.lowercase(Locale.ROOT)
    val lowerText = text.lowercase(Locale.ROOT)
    return terms.sumOf { term -> (if (lowerName.contains(term)) 5 else 0) + (if (lowerText.contains(term)) 1 else 0) }
}

private fun matchingExcerpt(text: String, terms: Set<String>): String {
    if (text.isBlank()) return "Filename match"
    val lower = text.lowercase(Locale.ROOT)
    val index = terms.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
    val start = (index - 80).coerceAtLeast(0)
    val end = (index + 240).coerceAtMost(text.length)
    return text.substring(start, end).replace(Regex("\\s+"), " ").trim()
}

private fun isReadableTextFile(name: String, mimeType: String?): Boolean =
    mimeType?.startsWith("text/") == true || name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("txt", "md", "csv", "json", "log", "xml", "kt", "java", "py", "js", "html", "yaml", "yml")

private const val MAX_FOLDER_FILES = 500
private const val MAX_FOLDER_DEPTH = 6
private const val MAX_INDEXED_FILE_BYTES = 256 * 1024
