package com.pocketagentslab

import java.util.Locale

internal data class DocumentChunk(val index: Int, val text: String, val score: Int)

internal fun retrieveDocumentChunks(
    document: String,
    query: String,
    chunkChars: Int = 900,
    maxChunks: Int = 3,
): List<DocumentChunk> {
    require(chunkChars > 0 && maxChunks > 0)
    val terms = query.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .toSet()
    val paragraphs = document.replace("\r\n", "\n")
        .split(Regex("\n\\s*\n"))
        .flatMap { paragraph -> paragraph.trim().chunked(chunkChars) }
        .filter { it.isNotBlank() }
    return paragraphs.mapIndexed { index, text ->
        val lowered = text.lowercase(Locale.ROOT)
        DocumentChunk(index + 1, text, terms.sumOf { term -> Regex("\\b${Regex.escape(term)}\\b").findAll(lowered).count() })
    }.sortedWith(compareByDescending<DocumentChunk> { it.score }.thenBy { it.index })
        .take(maxChunks)
}

internal fun buildDocumentAnswerPrompt(
    documentName: String,
    question: String,
    chunks: List<DocumentChunk>,
): String = """Answer using only the source excerpts below. If they do not contain the answer, say that the selected document does not provide it. Be concise and cite excerpt numbers like [1].
Return exactly {"action":"answer","text":"YOUR ANSWER"}.
Document: $documentName
Question: $question
Sources:
${chunks.joinToString("\n\n") { "[${it.index}] ${it.text}" }}
JSON:"""
