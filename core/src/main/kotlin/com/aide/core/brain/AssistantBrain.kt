package com.aide.core.brain

import com.aide.core.model.Analysis
import com.aide.core.model.ResearchSource
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebFetchTool20250910
import com.anthropic.models.beta.messages.BetaWebFetchTool20260209
import com.anthropic.models.beta.messages.BetaWebSearchTool20250305
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.serialization.json.Json
import java.time.Duration

/** Something went wrong talking to Claude. [retryable] tells the caller whether trying later may help. */
class BrainException(message: String, val retryable: Boolean, cause: Throwable? = null) :
    Exception(message, cause)

data class AnalysisInput(
    /** Pre-rendered context block (time, source, contact, owner, language, upcoming events). */
    val context: String,
    val transcript: String,
)

data class AnalysisResult(val analysis: Analysis, val model: String, val usage: Usage = Usage.NONE)

data class ResearchResult(
    val markdown: String,
    val sources: List<ResearchSource>,
    val model: String,
    val usage: Usage = Usage.NONE,
)

data class ModelOption(val id: String, val label: String)

/**
 * The assistant's brain: every Claude call the app makes goes through here.
 * Uses the official Anthropic Java SDK (beta Messages API, for server-side refusal fallbacks).
 */
class AssistantBrain(
    apiKey: String,
    val model: String = DEFAULT_MODEL,
    baseUrl: String? = null,
) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .timeout(Duration.ofMinutes(10))
        .maxRetries(3)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /** Understand a conversation and decide which actions to take. */
    fun analyze(input: AnalysisInput): AnalysisResult {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16_000L)
            .system(Prompts.ANALYSIS_SYSTEM)
            .outputConfig(structuredOutput(BetaOutputConfig.Effort.MEDIUM, AnalysisSchema.schema))
            .addUserMessage(buildString {
                append("<context>\n").append(input.context.trim()).append("\n</context>\n\n")
                append("<conversation>\n").append(input.transcript.trim()).append("\n</conversation>")
            })
            .withRefusalFallback()
            .build()

        val message = call { client.beta().messages().create(params) }
        ensureUsable(message)
        val text = message.textOutput()
        val analysis = try {
            json.decodeFromString(Analysis.serializer(), text)
        } catch (e: Exception) {
            throw BrainException("Claude returned an unexpected format: ${e.message}", retryable = true, cause = e)
        }
        return AnalysisResult(analysis, message.model().asString(), message.toUsage())
    }

    /**
     * Answer a question about the user's own history ("what did Rahul say about the price?").
     * [history] is the pre-rendered `<history>` block from [HistoryContext]; [validIds] are the record ids
     * Claude may cite - any other id it returns is dropped, so the app never links to something invented.
     * [previous] is the earlier part of the chat, so follow-up questions work.
     */
    fun ask(
        question: String,
        history: String,
        previous: List<ChatTurn> = emptyList(),
        validIds: Set<String>? = null,
    ): AskResult {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4_000L)
            .system(Prompts.ASK_SYSTEM)
            .outputConfig(structuredOutput(BetaOutputConfig.Effort.LOW, AskSchema.schema))
            .apply {
                chatHistory(previous).forEach { if (it.fromUser) addUserMessage(it.text) else addAssistantMessage(it.text) }
                addUserMessage(history.trim() + "\n\n<question>\n" + question.trim() + "\n</question>")
            }
            .withRefusalFallback()
            .build()

        val message = call { client.beta().messages().create(params) }
        ensureUsable(message)
        val parsed = try {
            json.decodeFromString(AskAnswer.serializer(), message.textOutput())
        } catch (e: Exception) {
            throw BrainException("Claude returned an unexpected format: ${e.message}", retryable = true, cause = e)
        }
        val cited = if (validIds == null) parsed.sourceIds else parsed.sourceIds.filter { it in validIds }.distinct()
        return AskResult(parsed.copy(sourceIds = cited), message.model().asString(), message.toUsage())
    }

    /** A short brief before a call or meeting with someone, from [PeopleIndex.briefingContext]. */
    fun briefing(context: String): TextResult = textCall(
        Prompts.BRIEFING_SYSTEM, context, maxTokens = 3_000L, effort = BetaOutputConfig.Effort.MEDIUM,
    )

    /** The end-of-week review, from [com.aide.core.review.WeeklyLog.build]. */
    fun weeklyReview(log: String): TextResult = textCall(
        Prompts.WEEKLY_REVIEW_SYSTEM, log, maxTokens = 8_000L, effort = BetaOutputConfig.Effort.MEDIUM,
    )

    private fun textCall(system: String, user: String, maxTokens: Long, effort: BetaOutputConfig.Effort): TextResult {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens)
            .system(system)
            .outputConfig(BetaOutputConfig.builder().effort(effort).build())
            .addUserMessage(user)
            .withRefusalFallback()
            .build()
        val message = call { client.beta().messages().create(params) }
        ensureUsable(message)
        val text = message.textOutput().trim()
        if (text.isEmpty()) throw BrainException("Claude's answer came back empty.", retryable = true)
        return TextResult(text, message.model().asString(), message.toUsage())
    }

    /** Keeps the chat valid for the API: starts with the user, no blank turns, at most the last few turns. */
    private fun chatHistory(previous: List<ChatTurn>): List<ChatTurn> =
        previous.filter { it.text.isNotBlank() }.takeLast(MAX_CHAT_TURNS).dropWhile { !it.fromUser }

    private fun structuredOutput(effort: BetaOutputConfig.Effort, schema: Map<String, Any>): BetaOutputConfig =
        BetaOutputConfig.builder()
            .effort(effort)
            .format(
                BetaJsonOutputFormat.builder()
                    .schema(
                        BetaJsonOutputFormat.Schema.builder()
                            .putAllAdditionalProperties(schema.toJsonValues())
                            .build()
                    )
                    .build()
            )
            .build()

    private fun BetaMessage.toUsage(): Usage = Usage(usage().inputTokens(), usage().outputTokens())

    /** Research a question on the web and return a Markdown report with sources. */
    fun research(question: String, context: String): ResearchResult {
        val dynamicTools = !model.contains("haiku")
        var params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(32_000L)
            .system(Prompts.RESEARCH_SYSTEM)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
            .apply {
                if (dynamicTools) {
                    addTool(BetaWebSearchTool20260209.builder().maxUses(8L).build())
                    addTool(BetaWebFetchTool20260209.builder().maxUses(5L).maxContentTokens(20_000L).build())
                } else {
                    // Haiku does not support the dynamic-filtering tool versions.
                    addTool(BetaWebSearchTool20250305.builder().maxUses(8L).build())
                    addTool(BetaWebFetchTool20250910.builder().maxUses(5L).maxContentTokens(20_000L).build())
                }
            }
            .addUserMessage(buildString {
                append("<context>\n").append(context.trim()).append("\n</context>\n\n")
                append("<question>\n").append(question.trim()).append("\n</question>")
            })
            .withRefusalFallback()
            .build()

        val text = StringBuilder()
        val sources = LinkedHashMap<String, ResearchSource>()
        val searchResults = LinkedHashMap<String, ResearchSource>()
        var finalModel = model
        var usage = Usage.NONE

        // Server-side tools run in a loop on Anthropic's side; a long run returns pause_turn and we
        // resume by sending the assistant turn back unchanged.
        repeat(MAX_CONTINUATIONS) {
            val message = stream(params)
            finalModel = message.model().asString()
            usage += message.toUsage()
            if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                throw BrainException(refusalText(message), retryable = false)
            }
            message.content().forEach { block ->
                block.text().ifPresent { t ->
                    text.append(t.text())
                    t.citations().orElse(emptyList()).forEach { c ->
                        c.webSearchResultLocation().ifPresent { loc ->
                            sources.putIfAbsent(loc.url(), ResearchSource(loc.title().orElse(loc.url()), loc.url()))
                        }
                    }
                }
                block.webSearchToolResult().ifPresent { r ->
                    r.content().resultBlocks().ifPresent { results ->
                        results.forEach { searchResults.putIfAbsent(it.url(), ResearchSource(it.title(), it.url())) }
                    }
                }
            }
            if (message.stopReason().orElse(null) != BetaStopReason.PAUSE_TURN) {
                val markdown = text.toString().trim()
                if (markdown.isEmpty()) throw BrainException("The research came back empty.", retryable = true)
                val allSources = if (sources.isNotEmpty()) sources.values.toList()
                else searchResults.values.take(8)
                return ResearchResult(markdown, allSources, finalModel, usage)
            }
            params = params.toBuilder().addMessage(message).build()
        }
        throw BrainException("The research took too many steps; try a narrower question.", retryable = false)
    }

    /** Write the end-of-day review from a pre-rendered log of the day. */
    fun dailyReview(log: String): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(8_000L)
            .system(Prompts.REVIEW_SYSTEM)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .addUserMessage(log)
            .withRefusalFallback()
            .build()
        val message = call { client.beta().messages().create(params) }
        ensureUsable(message)
        return message.textOutput().trim()
    }

    /** Cheap request used by the "Test connection" button in Settings. */
    fun ping(): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(1_024L)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .addUserMessage("Reply with exactly: Aide is connected.")
            .build()
        val message = call { client.beta().messages().create(params) }
        ensureUsable(message)
        return message.textOutput().trim()
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Opt in to server-side refusal fallbacks: if a safety classifier declines the request, the API
     * re-runs it on Anthropic's recommended fallback model instead of returning a refusal.
     * Not available for Haiku models.
     */
    private fun MessageCreateParams.Builder.withRefusalFallback(): MessageCreateParams.Builder =
        if (supportsServerFallback(model)) addBeta(FALLBACK_BETA).fallbacksDefault() else this

    private fun stream(params: MessageCreateParams): BetaMessage = call {
        client.beta().messages().createStreaming(params).use { response ->
            val accumulator = BetaMessageAccumulator.create()
            response.stream().forEach { event -> accumulator.accumulate(event) }
            accumulator.message()
        }
    }

    private fun ensureUsable(message: BetaMessage) {
        when (message.stopReason().orElse(null)) {
            BetaStopReason.REFUSAL -> throw BrainException(refusalText(message), retryable = false)
            BetaStopReason.MAX_TOKENS ->
                throw BrainException("Claude's answer was cut off (too long).", retryable = false)
            else -> Unit
        }
    }

    private fun refusalText(message: BetaMessage): String {
        val why = message.stopDetails().flatMap { it.explanation() }.orElse(null)
        return "Claude declined this request" + (if (why != null) ": $why" else ".")
    }

    private fun BetaMessage.textOutput(): String =
        content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: UnauthorizedException) {
        throw BrainException("Your Anthropic API key was rejected. Check it in Settings.", false, e)
    } catch (e: PermissionDeniedException) {
        throw BrainException("This API key cannot use $model. Pick another model in Settings.", false, e)
    } catch (e: NotFoundException) {
        throw BrainException("Model $model was not found. Pick another model in Settings.", false, e)
    } catch (e: BadRequestException) {
        throw BrainException("Claude rejected the request: ${e.message}", false, e)
    } catch (e: RateLimitException) {
        throw BrainException("Rate limited by the Claude API; will retry.", true, e)
    } catch (e: InternalServerException) {
        throw BrainException("Claude API had a temporary problem; will retry.", true, e)
    } catch (e: AnthropicServiceException) {
        throw BrainException("Claude API error ${e.statusCode()}: ${e.message}", e.statusCode() >= 500, e)
    } catch (e: AnthropicIoException) {
        throw BrainException("Network problem talking to Claude; will retry.", true, e)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private const val MAX_CONTINUATIONS = 6
        private const val MAX_CHAT_TURNS = 8

        val MODEL_OPTIONS = listOf(
            ModelOption("claude-opus-5-5", "Claude Opus 5.5 - smartest (recommended)"),
            ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5 - faster, about half the cost"),
            ModelOption("claude-haiku-5-5", "Claude Haiku 5.5 - fastest and cheapest"),
        )

        fun supportsServerFallback(model: String): Boolean =
            model.startsWith("claude-opus") || model.startsWith("claude-sonnet-5") ||
                model.startsWith("claude-fable")
    }
}

/** Converts a plain Kotlin map (nested maps / lists / primitives) to the SDK's JsonValue map. */
internal fun Map<String, Any>.toJsonValues(): Map<String, JsonValue> = mapValues { JsonValue.from(it.value) }
