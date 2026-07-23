package kyo.ai.completion

import kyo.*
import kyo.Json.JsonSchema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

/** The provider completion-backend contract: turn a config + conversation + tool set into transcript messages.
  *
  * A `Completion` is the wire layer for one provider family. `apply` serializes the conversation and the
  * tool definitions to the provider's request DTO, posts it over kyo-http, and decodes the reply into one or
  * more `Message` values. Transport failures surface as `Abort[HttpException]` (the
  * typed kyo-http error hierarchy), never `Abort[Throwable]`; a missing API key or an undecodable reply
  * surface as the typed `Abort[AIGenException]` leaves (`AIMissingApiKeyException`, `AIDecodeException`),
  * mapped to `AITransportException` at the eval boundary. The `resultSchema` override, when
  * present, supplies the parameter schema for the dynamic `result_tool` (whose own `inputSchema` is the
  * opaque `Structure.Value` shape): the thought-aware result envelope is assembled by the eval loop and
  * carried here so the request's result-tool definition exposes the real properties. Backends are reached
  * through `Config.Provider`, not constructed by users.
  */
trait Completion:

    def apply(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: Maybe[JsonSchema] = Absent
    )(using Frame): Completion.Reply < (LLM & Async & Abort[HttpException | AIGenException])

    /** Provides raw JSON fragments for the `{ resultValue: ... }` envelope consumed by `LLM.stream`.
      *
      * HTTP providers implement this by posting their native SSE request and projecting tool-call argument
      * deltas. Harness providers implement it through their native event or stream-json output.
      */
    def streamFragments(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]],
        usageSink: AtomicRef[Maybe[Completion.Usage]]
    )(using Frame): Stream[String, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException])

end Completion

object Completion:

    /** Provider-reported token usage for one completion, cache-aware. Distinct from each adapter's
      * internal snake_case wire DTO: the wire DTO is decoded per adapter then CONVERTED to this
      * type, never returned directly. `cachedInputTokens` is `Absent` when the provider does not
      * report it.
      */
    final case class Usage(inputTokens: Int, outputTokens: Int, cachedInputTokens: Maybe[Int] = Absent) derives CanEqual

    /** The widened `apply` result: the reply messages plus optional provider-reported usage.
      * `messages` preserves today's behavior; `usage` is `Absent` when a backend does not report it.
      */
    final case class Reply(messages: Chunk[Message], usage: Maybe[Usage]) derives CanEqual

    /** The OpenAI-compatible backend (OpenAI plus DeepSeek/Gemini/Groq/Baseten/OpenRouter). The concrete
      * implementation is package-private; reach it (and Anthropic) through these accessors.
      */
    val openAI: Completion = OpenAICompletion

    /** The Anthropic Messages backend. */
    val anthropic: Completion = AnthropicCompletion

    /** The Claude Code CLI harness backend. */
    val claudeCode: Completion = ClaudeCodeCompletion

    /** The Codex CLI harness backend. */
    val codex: Completion = CodexCompletion

    /** The reserved name of the result tool. A backend matches a tool by this name to substitute the
      * thought-aware result envelope schema for the tool's opaque `Structure.Value` input schema.
      */
    private[kyo] val resultToolName = "result_tool"

    /** The provider-specific pieces of a streaming completion request: endpoint URL, headers, and body. */
    case class StreamRequest(url: String, headers: Seq[(String, String)], body: String)

    private[completion] def sseFragments(
        config: Config,
        request: StreamRequest < Abort[AIStreamException],
        parseDeltaArguments: String => Result[String, Maybe[String]],
        parseUsage: String => Maybe[Completion.Usage],
        usageSink: AtomicRef[Maybe[Completion.Usage]]
    )(using Frame): Stream[String, Async & Scope & Abort[AIStreamException]] < Async =
        given sseTag: Tag[Emit[Chunk[HttpSseEvent[String]]]] = Tag[Emit[Chunk[HttpSseEvent[String]]]]
        val route                                            = HttpRoute.postRaw("").request(_.bodyText).response(_.bodySseText)
        Stream.unwrap {
            for
                req <- request
                sseStream <- Abort.recover[HttpException](e => Abort.fail(AITransportException(e))) {
                    for
                        baseReq <- Abort.get(HttpRequest.postRaw(req.url))
                        httpRequest = req.headers.foldLeft(baseReq)((r, kv) => r.addHeader(kv._1, kv._2))
                            .addField("body", req.body)
                        sseStream <- HttpClient.withConfig(_.timeout(config.timeout)) {
                            HttpClient.use { client =>
                                client.sendWith(route, httpRequest)(_.fields.body)
                            }
                        }
                    yield sseStream
                }
            yield sseStream.map { event =>
                // Record the provider's stream-end usage into the sink (§5a:370): OpenAI's include_usage
                // final chunk and Anthropic's message_start both carry it on a chunk that projects to no
                // argument fragment, so recording is orthogonal to fragment projection. AtomicRef.set is
                // Sync and Async <: Sync, so this adds nothing to the element effect row.
                val record =
                    parseUsage(event.data) match
                        case Present(usage) => usageSink.set(Present(usage))
                        case Absent         => Kyo.unit
                record.andThen {
                    parseDeltaArguments(event.data) match
                        case Result.Success(Present(fragment)) => fragment
                        case Result.Success(Absent)            => ""
                        case Result.Failure(err)               => Abort.fail(AIStreamDeltaException(err))
                }
            }.filterPure(_.nonEmpty)
        }
    end sseFragments

end Completion
