package com.arm.aichat

/** Experimental storage-backed expert cache controls. Not wired into graph dispatch yet. */
object ExpertCacheRuntime {
    @JvmStatic
    private external fun openNative(path: String, budgetBytes: Long): Boolean

    @JvmStatic
    private external fun setPageWarmNative(enabled: Boolean)

    @JvmStatic
    private external fun setPageEvictNative(enabled: Boolean)

    @JvmStatic
    private external fun releaseAllExpertPagesNative(): Long

    @JvmStatic
    private external fun loadNative(layer: Int, expert: Int, offset: Long, length: Long): Boolean

    @JvmStatic
    private external fun prefetchNative(layer: Int, expert: Int, offset: Long, length: Long): Boolean

    @JvmStatic
    private external fun prefetchExpertNative(layer: Int, expert: Int): Boolean

    @JvmStatic
    private external fun expertIndexNative(): String

    @JvmStatic
    private external fun indexFileNative(path: String): String

    @JvmStatic
    private external fun setTelemetryNative(enabled: Boolean)

    @JvmStatic
    private external fun setPredictorNative(enabled: Boolean)

    @JvmStatic
    private external fun telemetryJsonNative(): String

    @JvmStatic
    private external fun statsNative(): String

    @JvmStatic
    private external fun clearNative()

    @JvmStatic
    private external fun closeNative()

    fun open(path: String, budgetBytes: Long): Boolean = openNative(path, budgetBytes)

    /** Warm the kernel page cache without retaining a duplicate expert buffer. */
    fun setPageWarm(enabled: Boolean) = setPageWarmNative(enabled)

    /** Experimental: drop clean pages for experts absent from the previous route. */
    fun setPageEvict(enabled: Boolean) = setPageEvictNative(enabled)

    /** Drop clean pages for every loaded MoE expert tensor; returns tensors advised. */
    fun releaseAllExpertPages(): Long = releaseAllExpertPagesNative()

    fun load(layer: Int, expert: Int, offset: Long, length: Long): Boolean =
        loadNative(layer, expert, offset, length)

    fun prefetch(layer: Int, expert: Int, offset: Long, length: Long): Boolean =
        prefetchNative(layer, expert, offset, length)

    fun prefetchExpert(layer: Int, expert: Int): Boolean = prefetchExpertNative(layer, expert)

    fun expertIndexJson(): String = expertIndexNative()

    fun indexFileJson(path: String): String = indexFileNative(path)

    fun setTelemetry(enabled: Boolean) = setTelemetryNative(enabled)

    fun setPredictor(enabled: Boolean) = setPredictorNative(enabled)

    fun telemetryJson(): String = telemetryJsonNative()

    fun statsJson(): String = statsNative()

    fun clear() = clearNative()

    fun close() = closeNative()
}
