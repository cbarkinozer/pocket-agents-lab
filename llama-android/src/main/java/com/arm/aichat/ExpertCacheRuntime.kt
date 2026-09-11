package com.arm.aichat

/** Experimental storage-backed expert cache controls. Not wired into graph dispatch yet. */
object ExpertCacheRuntime {
    @JvmStatic
    private external fun openNative(path: String, budgetBytes: Long): Boolean

    @JvmStatic
    private external fun loadNative(layer: Int, expert: Int, offset: Long, length: Long): Boolean

    @JvmStatic
    private external fun statsNative(): String

    @JvmStatic
    private external fun clearNative()

    @JvmStatic
    private external fun closeNative()

    fun open(path: String, budgetBytes: Long): Boolean = openNative(path, budgetBytes)

    fun load(layer: Int, expert: Int, offset: Long, length: Long): Boolean =
        loadNative(layer, expert, offset, length)

    fun statsJson(): String = statsNative()

    fun clear() = clearNative()

    fun close() = closeNative()
}
