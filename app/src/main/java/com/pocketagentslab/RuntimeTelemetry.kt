package com.pocketagentslab

import java.io.File

internal data class RuntimeMemorySnapshot(
    val minorFaults: Long?,
    val majorFaults: Long?,
    val readBytes: Long?,
    val rssKb: Long?,
    val filePssKb: Long?,
    val swapPssKb: Long?,
)

internal fun captureRuntimeMemorySnapshot(): RuntimeMemorySnapshot {
    val faults = runCatching { parseProcStat(File("/proc/self/stat").readText()) }.getOrNull()
    val io = runCatching { parseKeyValueLines(File("/proc/self/io").readText()) }.getOrDefault(emptyMap())
    val smaps = runCatching { parseKeyValueLines(File("/proc/self/smaps_rollup").readText()) }
        .getOrDefault(emptyMap())
    return RuntimeMemorySnapshot(
        minorFaults = faults?.first,
        majorFaults = faults?.second,
        readBytes = io["read_bytes"],
        rssKb = smaps["Rss"],
        filePssKb = smaps["Pss_File"],
        swapPssKb = smaps["SwapPss"],
    )
}

// proc(5): fields 10 and 12 are minflt and majflt. The command name may contain spaces.
internal fun parseProcStat(text: String): Pair<Long, Long> {
    val afterCommand = text.substring(text.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))
    require(afterCommand.size > 9) { "Incomplete /proc/self/stat" }
    return afterCommand[7].toLong() to afterCommand[9].toLong()
}

internal fun parseKeyValueLines(text: String): Map<String, Long> = text.lineSequence().mapNotNull { line ->
    val separator = line.indexOf(':')
    if (separator <= 0) return@mapNotNull null
    val value = line.substring(separator + 1).trim().substringBefore(' ').toLongOrNull()
        ?: return@mapNotNull null
    line.substring(0, separator) to value
}.toMap()

internal fun nullableDelta(after: Long?, before: Long?): Long? =
    if (after != null && before != null) after - before else null
