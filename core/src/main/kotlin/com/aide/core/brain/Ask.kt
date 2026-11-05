package com.aide.core.brain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Claude's answer to a question about the user's own history. */
@Serializable
data class AskAnswer(
    val answer: String,
    /** Ids of the conversations / research reports the answer relies on (so the app can link to them). */
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
    /** False when the history did not contain the answer. */
    val found: Boolean = true,
)

/** One earlier message of an "Ask Aide" chat, so follow-up questions keep their context. */
data class ChatTurn(val fromUser: Boolean, val text: String)

data class AskResult(val answer: AskAnswer, val model: String, val usage: Usage = Usage.NONE)

/** A Markdown text produced by Claude (a briefing or a review). */
data class TextResult(val markdown: String, val model: String, val usage: Usage = Usage.NONE)

/** JSON schema for [AskAnswer], in the same strict shape as [AnalysisSchema] (all keys required). */
object AskSchema {
    val schema: Map<String, Any> = linkedMapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to linkedMapOf(
            "answer" to mapOf(
                "type" to "string",
                "description" to "The answer in Markdown, in the output language",
            ),
            "source_ids" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Ids of the records the answer relies on, copied exactly",
            ),
            "found" to mapOf(
                "type" to "boolean",
                "description" to "False when the history does not contain the answer",
            ),
        ),
        "required" to listOf("answer", "source_ids", "found"),
    )
}
