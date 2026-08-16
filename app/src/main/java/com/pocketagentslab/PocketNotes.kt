package com.pocketagentslab

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.UUID
import org.json.JSONObject

internal data class PocketNote(
    val id: String,
    val title: String,
    val content: String,
    val createdAtMs: Long,
)

internal data class NoteProposal(val title: String, val content: String)

internal fun parseNoteWriteRequest(request: String): NoteProposal? {
    val trimmed = request.trim()
    val match = Regex(
        "(?i)^(?:please\\s+)?(?:write|save|remember|add)\\s+(.+?)\\s+(?:in|to)\\s+(?:my\\s+)?notes?[.!]?$",
    ).matchEntire(trimmed) ?: Regex(
        "(?i)^(?:please\\s+)?(?:remember|save)\\s+(?:this\\s*:?\\s*|that\\s+)?(.+)$",
    ).matchEntire(trimmed) ?: Regex(
        "(?i)^(?:please\\s+)?write\\s+(?:this\\s+)?(?:in|to)\\s+(?:my\\s+)?notes?\\s*:?\\s*(.+)$",
    ).matchEntire(trimmed) ?: Regex(
        "(?i)^(?:please\\s+)?(?:write|add)\\s+(?:this\\s+)?(?:note\\s*:?\\s*)?(.+)$",
    ).matchEntire(trimmed) ?: Regex(
        "(?i)^(.+?)[,!.]?\\s+(?:please\\s+)?(?:remember|save)(?:\\s+it)?[.!]?$",
    ).matchEntire(trimmed)
    val content = match?.groupValues?.get(1)?.trim()?.trimEnd('.', '!') ?: return null
    if (content.isBlank()) return null
    return NoteProposal(title = content.take(48), content = content)
}

internal fun parseNoteSearchRequest(request: String): String? {
    val explicit = Regex(
        "(?i)^(?:please\\s+)?(?:find|search|look\\s+for|what\\s+did\\s+i\\s+write\\s+about)\\s+(.+?)(?:\\s+(?:in|inside)\\s+(?:my\\s+)?notes?)?[?.!]?$",
    ).matchEntire(request.trim())
    if (explicit != null) return explicit.groupValues[1].trim().takeIf { it.isNotBlank() }
    val recall = Regex(
        "(?i)^(?:please\\s+)?(?:what\\s+was\\s+|what\\s+did\\s+i\\s+(?:tell|ask)\\s+you\\s+(?:about\\s+)?|do\\s+you\\s+remember\\s+|recall\\s+)(.+?)(?:\\s+i\\s+told\\s+you(?:\\s+before)?|\\s+from\\s+(?:my\\s+)?notes?)?[?.!]?$",
    ).matchEntire(request.trim()) ?: Regex(
        "(?i)^(?:please\\s+)?what\\s+is\\s+(.+?)(?:\\s+i\\s+told\\s+you(?:\\s+before)?|\\s+from\\s+(?:my\\s+)?notes?)[?.!]?$",
    ).matchEntire(request.trim()) ?: return null
    return recall.groupValues[1].trim().takeIf { it.isNotBlank() }
}

internal fun savePocketNote(context: Context, proposal: NoteProposal): PocketNote {
    val note = PocketNote(
        id = UUID.randomUUID().toString(),
        title = proposal.title.trim().take(100),
        content = proposal.content.trim().take(20_000),
        createdAtMs = System.currentTimeMillis(),
    )
    require(note.title.isNotBlank() && note.content.isNotBlank()) { "Note title and content are required" }
    val directory = File(context.filesDir, "notes").also { it.mkdirs() }
    File(directory, "${note.id}.json").writeText(
        JSONObject()
            .put("id", note.id)
            .put("title", note.title)
            .put("content", note.content)
            .put("createdAtMs", note.createdAtMs)
            .toString(2),
    )
    return note
}

internal fun loadPocketNotes(context: Context): List<PocketNote> =
    File(context.filesDir, "notes").listFiles { file -> file.extension == "json" }
        .orEmpty()
        .mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                PocketNote(
                    id = json.getString("id"),
                    title = json.getString("title"),
                    content = json.getString("content"),
                    createdAtMs = json.getLong("createdAtMs"),
                )
            }.getOrNull()
        }
        .sortedByDescending { it.createdAtMs }

internal fun searchPocketNotes(notes: List<PocketNote>, query: String): List<PocketNote> {
    val stopWords = setOf(
        "a", "an", "the", "is", "was", "were", "what", "which", "that", "this", "my", "i",
        "you", "me", "to", "of", "for", "in", "on", "it", "before", "told", "tell", "remember",
    )
    val terms = query.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 && it !in stopWords }
        .toSet()
    if (terms.isEmpty()) return emptyList()
    return notes.map { note ->
        val title = note.title.lowercase(Locale.ROOT)
        val content = note.content.lowercase(Locale.ROOT)
        val titleWords = title.split(Regex("[^\\p{L}\\p{N}]+"))
        val contentWords = content.split(Regex("[^\\p{L}\\p{N}]+"))
        note to terms.sumOf { term ->
            (if (title.contains(term) || titleWords.any { isAdjacentSwap(it, term) }) 3 else 0) +
                (if (content.contains(term) || contentWords.any { isAdjacentSwap(it, term) }) 1 else 0)
        }
    }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<PocketNote, Int>> { it.second }.thenByDescending { it.first.createdAtMs })
        .map { it.first }
}

private fun isAdjacentSwap(left: String, right: String): Boolean {
    if (left.length != right.length || left.length < 2) return false
    val differences = left.indices.filter { left[it] != right[it] }
    return differences.size == 2 && differences[1] == differences[0] + 1 &&
        left[differences[0]] == right[differences[1]] && left[differences[1]] == right[differences[0]]
}
