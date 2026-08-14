package com.arm.aichat

/** Resets chat history while preserving loaded weights and the decoded system prompt. */
object ConversationReset {
    @JvmStatic
    private external fun resetNative()

    fun reset() = resetNative()
}
