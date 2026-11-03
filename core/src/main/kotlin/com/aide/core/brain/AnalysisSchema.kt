package com.aide.core.brain

/**
 * JSON schema for Claude's structured output (output_config.format). Every object sets
 * additionalProperties=false and lists all of its properties as required; optional values are
 * expressed as `anyOf [<type>, null]` so the model always returns a complete, predictable shape.
 */
object AnalysisSchema {

    private fun str(description: String) = mapOf("type" to "string", "description" to description)

    private fun nullableStr(description: String) = mapOf(
        "anyOf" to listOf(mapOf("type" to "string"), mapOf("type" to "null")),
        "description" to description,
    )

    private fun strList(description: String) = mapOf(
        "type" to "array",
        "items" to mapOf("type" to "string"),
        "description" to description,
    )

    private val actionTypes = listOf(
        "calendar_event", "reminder", "todo", "research", "message_draft", "note", "alarm",
    )

    private val action: Map<String, Any> = linkedMapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to linkedMapOf(
            "type" to mapOf("type" to "string", "enum" to actionTypes),
            "title" to str("Short title, e.g. 'Meet Rahul' or 'Pay electricity bill'"),
            "details" to nullableStr("Extra context, assumptions made, or what is needed"),
            "start_local" to nullableStr("YYYY-MM-DDTHH:MM local time, or YYYY-MM-DD for all-day"),
            "end_local" to nullableStr("YYYY-MM-DDTHH:MM local time, if known"),
            "all_day" to mapOf("type" to "boolean"),
            "location" to nullableStr("Place, if mentioned"),
            "people" to strList("People involved"),
            "recipient" to nullableStr("For message_draft: who the message is for"),
            "message_body" to nullableStr("For message_draft: the message text"),
            "research_question" to nullableStr("For research: complete, self-contained question"),
            "evidence" to nullableStr("Short quote from the conversation that justifies this action"),
            "confidence" to mapOf("type" to "number", "description" to "0 to 1"),
        ),
        "required" to listOf(
            "type", "title", "details", "start_local", "end_local", "all_day", "location", "people",
            "recipient", "message_body", "research_question", "evidence", "confidence",
        ),
    )

    val schema: Map<String, Any> = linkedMapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to linkedMapOf(
            "title" to str("Short label for the conversation"),
            "summary" to str("2-4 sentence summary"),
            "key_points" to strList("Facts worth keeping"),
            "people_mentioned" to strList("Names of people involved or mentioned"),
            "actions" to mapOf("type" to "array", "items" to action),
        ),
        "required" to listOf("title", "summary", "key_points", "people_mentioned", "actions"),
    )
}
