package com.aide.core.brain

/** Tokens consumed by one or more Claude calls (what the provider bills for). */
data class Usage(val inputTokens: Long = 0, val outputTokens: Long = 0) {
    val total: Long get() = inputTokens + outputTokens

    operator fun plus(other: Usage) = Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)

    companion object { val NONE = Usage() }
}
