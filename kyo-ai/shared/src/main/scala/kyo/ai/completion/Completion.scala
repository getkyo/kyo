package kyo.ai.completion

import kyo.*
import kyo.Json.JsonSchema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

/** The provider completion-backend contract: turn a config + conversation + tool set into one assistant reply.
  *
  * A `Completion` is the wire layer for one provider family. `apply` serializes the conversation and the
  * tool definitions to the provider's request DTO, posts it over kyo-http, and decodes the reply into an
  * `AssistantMessage` (text plus any tool calls). Transport failures surface as `Abort[HttpException]` (the
  * typed kyo-http error hierarchy), never `Abort[Throwable]`; a missing API key or an undecodable reply
  * surface as the typed `Abort[AIGenException]` leaves (`AIMissingApiKeyException`, `AIDecodeException`),
  * mapped to `AITransportException` at the eval boundary. The `resultSchema` override, when
  * present, supplies the parameter schema for the dynamic `result_tool` (whose own `inputSchema` is the
  * opaque `Structure.Value` shape): the thought-aware result envelope is assembled by the eval loop and
  * carried here so the request's result-tool definition exposes the real properties. Two implementations
  * exist: `OpenAICompletion` (OpenAI and 5 compatible providers) and `AnthropicCompletion`. Backends are
  * reached through `Config.Provider`, not constructed by users.
  */
trait Completion:

    def apply(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: Maybe[JsonSchema] = Absent
    )(using Frame): AssistantMessage < (LLM & Async & Abort[HttpException | AIGenException])

    /** Assembles the provider-specific streaming request (endpoint URL, headers, and body) for the result
      * tool over the assembled `resultSchema`, with the provider's stream flag set. The `streamAgainst`
      * projection posts it and feeds each SSE data line back through `parseDeltaArguments`. The URL and
      * headers differ by provider (`/chat/completions` + bearer auth for OpenAI, `/messages` + `x-api-key`
      * for Anthropic), so the whole request is dispatched here, not just the body.
      */
    def streamRequest(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Completion.StreamRequest

    /** Parses one SSE data line into the incremental result-tool argument fragment, if it carries one.
      *
      * `Result.Success(Present(fragment))` is an argument-JSON delta to append; `Result.Success(Absent)` is
      * a line with no argument fragment (a non-delta event, a keep-alive, or the provider's terminator);
      * `Result.Failure` is a line that is not a parseable streaming event for this provider.
      */
    def parseDeltaArguments(line: String)(using Frame): Result[String, Maybe[String]]

end Completion

object Completion:

    /** The OpenAI-compatible backend (OpenAI plus DeepSeek/Gemini/Groq/Baseten/OpenRouter). The concrete
      * implementation is package-private; reach it (and Anthropic) through these accessors.
      */
    val openAI: Completion = OpenAICompletion

    /** The Anthropic Messages backend. */
    val anthropic: Completion = AnthropicCompletion

    /** The reserved name of the result tool. A backend matches a tool by this name to substitute the
      * thought-aware result envelope schema for the tool's opaque `Structure.Value` input schema.
      */
    private[kyo] val resultToolName = "result_tool"

    /** The provider-specific pieces of a streaming completion request: endpoint URL, headers, and body. */
    case class StreamRequest(url: String, headers: Seq[(String, String)], body: String)

end Completion
