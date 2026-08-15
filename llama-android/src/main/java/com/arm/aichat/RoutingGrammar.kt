package com.arm.aichat

/** Enables the fixed five-route GBNF sampler for routing generation. */
object RoutingGrammar {
    @JvmStatic
    private external fun setEnabledNative(enabled: Boolean)

    fun setEnabled(enabled: Boolean) = setEnabledNative(enabled)
}
