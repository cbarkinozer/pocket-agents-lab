package com.pocketagentslab

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufArchitectureTest {
    @Test
    fun readsArchitectureWithoutNeedingTensorOrTokenizerData() {
        val bytes = ByteArrayOutputStream()
        fun int(value: Int) { bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        fun long(value: Long) { bytes.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()) }
        fun string(value: String) { val data = value.toByteArray(); long(data.size.toLong()); bytes.write(data) }
        int(0x46554747); int(3); long(500); long(99)
        string("general.architecture"); int(8); string("clef")
        val file = File.createTempFile("gguf-header", ".gguf")
        try {
            file.writeBytes(bytes.toByteArray())
            assertEquals("clef", GgufArchitecture.read(file))
        } finally { file.delete() }
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsNonGgufBeforeAttemptingRuntimeLoad() {
        val file = File.createTempFile("bad-gguf", ".gguf")
        try { file.writeText("not a GGUF model"); GgufArchitecture.read(file) } finally { file.delete() }
    }
}
