package com.arm.aichat

/** Enables the fixed five-route GBNF sampler for routing generation. */
object RoutingGrammar {
    @JvmStatic
    private external fun setModeNative(mode: Int)

    fun setMode(mode: Int) = setModeNative(mode)

    const val NONE = 0
    const val ROUTE = 1
    const val SCOPE = 2
    const val LIVE = 3
}
