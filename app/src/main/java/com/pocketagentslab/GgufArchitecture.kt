package com.pocketagentslab

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Read only enough GGUF metadata to choose a runtime; never parse vocabulary arrays. */
internal object GgufArchitecture {
    fun read(file: File): String = RandomAccessFile(file, "r").use { input ->
        fun uint32(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun uint64(): Long = java.lang.Long.reverseBytes(input.readLong()).also {
            if (it < 0) throw IOException("GGUF length exceeds supported range")
        }
        fun skip(length: Long) {
            if (length > input.length() - input.filePointer) throw IOException("Truncated GGUF metadata")
            input.seek(input.filePointer + length)
        }
        fun string(): String {
            val length = uint64()
            if (length > 1_048_576) throw IOException("GGUF metadata string too large")
            val bytes = ByteArray(length.toInt())
            input.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
        fun skipValue(type: Long, depth: Int = 0) {
            if (depth > 8) throw IOException("GGUF metadata nesting too deep")
            when (type) {
                0L, 1L, 7L -> skip(1)
                2L, 3L -> skip(2)
                4L, 5L, 6L -> skip(4)
                10L, 11L, 12L -> skip(8)
                8L -> skip(uint64())
                9L -> {
                    val elementType = uint32()
                    val count = uint64()
                    if (count > 10_000_000) throw IOException("GGUF metadata array too large")
                    repeat(count.toInt()) { skipValue(elementType, depth + 1) }
                }
                else -> throw IOException("Unknown GGUF metadata type $type")
            }
        }
        if (uint32() != 0x46554747L) throw IOException("Not a GGUF file")
        val version = uint32()
        if (version != 2L && version != 3L) throw IOException("Unsupported GGUF version $version")
        uint64() // tensor count
        val count = uint64()
        if (count > 1_000_000) throw IOException("GGUF metadata count too large")
        repeat(count.toInt()) {
            val key = string()
            val type = uint32()
            if (key == "general.architecture") {
                if (type != 8L) throw IOException("Invalid GGUF architecture field")
                return@use string().also { if (it.isBlank()) throw IOException("Empty GGUF architecture") }
            }
            skipValue(type)
        }
        throw IOException("GGUF architecture field is missing")
    }
}
