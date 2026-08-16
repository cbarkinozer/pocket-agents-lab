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
        "(?i)^(?:please\\s+)?(?:write|save|remember|add)\\s+(?:this\\s+)?(?:note\\s*:?\\s*)?(.+)$",
    ).matchEntire(trimmed)
    val content = match?.groupValues?.get(1)?.trim()?.trimEnd('.', '!') ?: return null
    if (content.isBlank()) return null
    return NoteProposal(title = content.take(48), content = content)
}

internal fun parseNoteSearchRequest(request: String): String? {
    val match = Regex(
        "(?i)^(?:please\\s+)?(?:find|search|look\\s+for|what\\s+did\\s+i\\s+write\\s+about)\\s+(.+?)(?:\\s+(?:in|inside)\\s+(?:my\\s+)?notes?)?[?.!]?$",
    ).matchEntire(request.trim()) ?: return null
    return match.groupValues[1].trim().takeIf { it.isNotBlank() }
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
    val terms = query.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .toSet()
    if (terms.isEmpty()) return emptyList()
    return notes.map { note ->
        val title = note.title.lowercase(Locale.ROOT)
        val content = note.content.lowercase(Locale.ROOT)
        note to terms.sumOf { term ->
            (if (title.contains(term)) 3 else 0) + (if (content.contains(term)) 1 else 0)
        }
    }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<PocketNote, Int>> { it.second }.thenByDescending { it.first.createdAtMs })
        .map { it.first }
}
