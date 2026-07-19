package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.Structure
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.{Message as ContextMessage, *}

/** The Anthropic completion backend (the `/messages` API).
  *
  * Serializes the conversation to the Anthropic request shape via typed `Content` DTOs that `Json.encode`
  * emits directly: the FIRST context message is extracted into the top-level `system` field and the
  * remaining messages map to user/assistant `Message`s, an assistant message's empty text block is
  * filtered before its `tool_use` blocks, a tool result becomes a USER-role message with a `tool_result`
  * content block, tool definitions carry `input_schema` (the standard JSON-schema form). A user message
  * with an image serializes as a multi-part content list with a `{"type":"text","text":"..."}` block
  * followed by a `{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"..."}}`
  * block. Headers are `x-api-key` and `anthropic-version: 2023-06-01` plus the explicit
  * `content-type: application/json`. A `tool_use.input` in the reply is an arbitrary tool-argument object,
  * so it is carried as a `Structure.Value` (the heterogeneous any-shape value tree) and re-encoded to the
  * raw argument JSON the `Call` contract expects. Transport failures surface as `Abort[HttpException]`.
  * The per-call timeout is installed by the eval loop wrapper.
  */
private[completion] object AnthropicCompletion extends Completion:

    import internal.*

    def apply(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: Maybe[JsonSchema] = Absent
    )(using Frame): Completion.Result < (LLM & Async & Abort[HttpException | AIGenException]) =
        fetch(config, Request(context, config, tools, resultSchema)).map(read)

    private def read(response: Response)(using Frame): Completion.Result < LLM =
        val text = response.content.collect {
            case c if c.`type` == "text" => c.text.getOrElse("")
        }.mkString("")
        val toolCalls = response.content.collect {
            case c if c.`type` == "tool_use" =>
                Call(
                    CallId(c.id.getOrElse("")),
                    c.name.getOrElse(""),
                    Json.encode(c.input.getOrElse(Structure.Value.Record(Chunk.empty)))
                )
        }.to(Chunk)
        // The internal snake_case wire Usage is decoded but was dropped; CONVERT it to the public
        // camelCase Completion.Usage (no cached-tokens field on the Anthropic DTO, so Absent).
        val usage = response.usage.map(u =>
            Completion.Usage(inputTokens = u.input_tokens, outputTokens = u.output_tokens, cachedInputTokens = Absent)
        )
        Completion.Result(Chunk(AssistantMessage(text, toolCalls)), usage)
    end read

    private def fetch(config: Config, req: Request)(using Frame): Response < (LLM & Async & Abort[HttpException | AIGenException]) =
        config.apiKey match
            case Absent =>
                Abort.fail(AIMissingApiKeyException(config.modelName))
            case Present(key) =>
                val headers = Seq(
                    "content-type"      -> "application/json",
                    "x-api-key"         -> key,
                    "anthropic-version" -> "2023-06-01"
                )
                val url = s"${config.apiUrl}/messages"
                HttpClient.withConfig(_.timeout(config.timeout)) {
                    HttpClient.postText(url, Json.encode(req), headers)
                        .map(body =>
                            Abort.get(Json.decode[Response](body).mapFailure(e => HttpJsonDecodeException(e.getMessage, "POST", url)))
                        )
                }

    def streamFragments(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Stream[String, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        Completion.sseFragments(config, streamRequest(config, context, resultSchema, resultTool), parseDeltaArguments)
    end streamFragments

    private[kyo] def streamRequest(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Completion.StreamRequest < Abort[AIStreamException] =
        import internal.*
        val headers =
            Seq("content-type" -> "application/json", "anthropic-version" -> "2023-06-01") ++
                config.apiKey.map(k => "x-api-key" -> k).toList
        Completion.StreamRequest(
            s"${config.apiUrl}/messages",
            headers,
            Json.encode(Request(context, config, resultTool, Present(resultSchema)).copy(stream = Present(true)))
        )
    end streamRequest

    /** Parses one `/messages` SSE data line. Tool-call arguments arrive as `content_block_delta` events whose
      * `delta` is an `input_json_delta` carrying a `partial_json` fragment; every other event type
      * (`message_start`, `content_block_start`, `text_delta`, `message_delta`, `message_stop`, `ping`) carries
      * no tool-argument fragment. There is no `[DONE]` terminator; the stream ends at `message_stop`.
      */
    private[kyo] def parseDeltaArguments(line: String)(using Frame): Result[String, Maybe[String]] =
        import internal.*
        Json.decode[StreamEvent](line) match
            case Result.Success(event) =>
                Result.Success(event.delta match
                    case Present(StreamDelta(Present("input_json_delta"), Present(pj))) if pj.nonEmpty => Present(pj)
                    case _                                                                             => Absent)
            case _ =>
                Result.Failure(s"Not a parseable Anthropic streaming event: $line")
        end match
    end parseDeltaArguments

    private object internal:

        case class Content(
            `type`: String,
            text: Maybe[String] = Absent,
            id: Maybe[String] = Absent,
            name: Maybe[String] = Absent,
            input: Maybe[Structure.Value] = Absent,
            tool_use_id: Maybe[String] = Absent,
            content: Maybe[String] = Absent,
            source: Maybe[Source] = Absent
        ) derives Schema
        case class Source(`type`: String, media_type: String, data: String) derives Schema
        case class Message(role: String, content: List[Content]) derives Schema
        case class ToolDefinition(name: String, description: Maybe[String], input_schema: JsonSchema) derives Schema
        case class ToolChoice(`type`: String, name: Maybe[String] = Absent) derives Schema
        case class Request(
            model: String,
            messages: List[Message],
            system: Maybe[String],
            max_tokens: Int,
            temperature: Maybe[Double],
            tools: Maybe[List[ToolDefinition]] = Absent,
            tool_choice: Maybe[ToolChoice] = Absent,
            stream: Maybe[Boolean] = Absent
        ) derives Schema
        case class Usage(input_tokens: Int, output_tokens: Int) derives Schema

        // Streaming SSE event DTOs: tool-call arguments arrive as `content_block_delta` events whose `delta`
        // is an `input_json_delta` carrying a `partial_json` fragment. Fields are Maybe to tolerate the other
        // event types (message_start, content_block_start, text_delta, message_delta, message_stop, ping).
        case class StreamDelta(`type`: Maybe[String] = Absent, partial_json: Maybe[String] = Absent) derives Schema
        case class StreamEvent(`type`: Maybe[String] = Absent, delta: Maybe[StreamDelta] = Absent) derives Schema
        case class Response(
            id: String,
            content: List[Content],
            model: String,
            role: String,
            stop_reason: Maybe[String],
            stop_sequence: Maybe[String],
            usage: Maybe[Usage]
        ) derives Schema

        object Request:
            def apply(
                ctx: Context,
                config: Config,
                tools: Chunk[Tool.internal.Info[?, ?, LLM]],
                resultSchema: Maybe[JsonSchema]
            )(using Frame): Request =
                // Anthropic carries the system prompt as a top-level field, not a message. Pull it off ONLY when
                // the conversation actually starts with a system message; dropping the first message
                // unconditionally discarded the opening user turn whenever there was no leading system message.
                val (system, body) =
                    ctx.messages.headMaybe match
                        case Present(SystemMessage(c)) => (Present(c), ctx.messages.drop(1))
                        case _                         => (Absent, ctx.messages)
                val mapped =
                    body.map {
                        case UserMessage(content, Present(image)) =>
                            Message(
                                Role.User.name,
                                List(
                                    Content("text", text = Present(content)),
                                    Content("image", source = Present(Source("base64", "image/jpeg", image.base64)))
                                )
                            )
                        case UserMessage(content, _) =>
                            Message(Role.User.name, List(Content("text", text = Present(content))))
                        case AssistantMessage(content, calls) =>
                            Message(
                                Role.Assistant.name,
                                List(Content("text", text = Present(content))).filter(_.text.exists(_.nonEmpty))
                                    ++ calls.map(call =>
                                        Content(
                                            "tool_use",
                                            id = Present(call.id.id),
                                            name = Present(call.function),
                                            input = Json.decode[Structure.Value](call.arguments).toMaybe
                                        )
                                    ).toList
                            )
                        case ToolMessage(callId, content) =>
                            Message(
                                Role.User.name,
                                List(Content("tool_result", tool_use_id = Present(callId.id), content = Present(content)))
                            )
                        case SystemMessage(content) =>
                            Message(Role.User.name, List(Content("text", text = Present(s"[INTERNAL SYSTEM INSTRUCTION] $content"))))
                    }.toList
                // Anthropic rejects consecutive same-role messages, so merge adjacent ones (e.g. the separate user
                // messages from parallel tool results) by concatenating their content blocks into one message.
                val messages =
                    mapped.foldLeft(List.empty[Message]) {
                        case (prev :: rest, msg) if prev.role == msg.role => prev.copy(content = prev.content ++ msg.content) :: rest
                        case (acc, msg)                                   => msg :: acc
                    }.reverse
                val toolDefs =
                    if tools.isEmpty then Absent
                    else
                        Present(tools.map { t =>
                            val schema =
                                if t.name == Completion.resultToolName then resultSchema.getOrElse(Json.jsonSchema(using t.inputSchema))
                                else Json.jsonSchema(using t.inputSchema)
                            ToolDefinition(t.name, Maybe.when(t.description.nonEmpty)(t.description), schema)
                        }.toList)
                val toolChoice =
                    tools.headMaybe match
                        case Present(tool) if tools.size == 1 && tool.name == Completion.resultToolName =>
                            Present(ToolChoice("tool", Present(Completion.resultToolName)))
                        case Present(_) =>
                            Present(ToolChoice("any"))
                        case Absent =>
                            Absent
                Request(config.modelName, messages, system, config.maxTokens.getOrElse(8192), config.temperature, toolDefs, toolChoice)
            end apply
        end Request

    end internal
end AnthropicCompletion
