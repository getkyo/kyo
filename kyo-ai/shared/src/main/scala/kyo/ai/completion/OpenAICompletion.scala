package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

/** The OpenAI-compatible completion backend (OpenAI plus DeepSeek/Gemini/Groq/Baseten/OpenRouter).
  *
  * Serializes the conversation to the `/chat/completions` request shape: each message becomes a typed
  * entry in the `messages` array, emitted directly by `Json.encode` of the request DTO. A user message
  * with an image serializes as a content-parts array (`{"type":"text","text":"..."}` then
  * `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`); a user message without an
  * image serializes with a plain string `content` field. The polymorphic `content` is carried as a
  * `Structure.Value`, whose identity Schema writes shape-aware (a string stays a string, a sequence becomes
  * an array), reproducing both wire shapes from one field. Tool definitions carry `parameters` (the standard JSON-schema form)
  * plus the `type:"function"` discriminator and `strict:false`, the request forces a tool call with
  * `tool_choice:"required"`, and `seed`/`max_completion_tokens` ride the request when configured. The
  * reply decode is null-tolerant (absent content -> "", absent tool_calls -> Nil); an empty `choices`
  * aborts with `AIDecodeException`. The content-type header is set explicitly to `application/json` (kyo-http
  * defaults to text/plain). The per-call timeout is installed by the eval loop wrapper, not here.
  */
private[completion] object OpenAICompletion extends Completion:

    import internal.*

    def apply(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: Maybe[JsonSchema] = Absent
    )(using Frame): Completion.Reply < (LLM & Async & Abort[HttpException | AIGenException]) =
        fetch(config, Request(context, config, tools, resultSchema)).map(read)

    private def read(response: Response)(using Frame): Completion.Reply < (LLM & Sync & Abort[AIGenException]) =
        Maybe.fromOption(response.choices.headOption) match
            case Absent =>
                Abort.fail(AIDecodeException("LLM response has no choices: " + Json.encode(response)))
            case Present(v) =>
                val messages = Chunk(AssistantMessage(
                    v.message.content.getOrElse(""),
                    Chunk.from(v.message.tool_calls.getOrElse(Nil).map(c =>
                        Call(CallId(c.id), c.function.name, c.function.arguments)
                    ))
                ))
                val usage = response.usage.map(u =>
                    Completion.Usage(
                        inputTokens = u.prompt_tokens,
                        outputTokens = u.completion_tokens,
                        cachedInputTokens = u.prompt_tokens_details.flatMap(_.cached_tokens)
                    )
                )
                Completion.Reply(messages, usage)
        end match
    end read

    private def fetch(config: Config, req: Request)(using Frame): Response < (LLM & Async & Abort[HttpException | AIGenException]) =
        config.apiKey match
            case Absent =>
                Abort.fail(AIMissingApiKeyException(config.modelName))
            case Present(key) =>
                val headers =
                    Seq("content-type" -> "application/json", "Authorization" -> s"Bearer $key") ++
                        config.apiOrg.map("OpenAI-Organization" -> _).toList
                val url = s"${config.apiUrl}/chat/completions"
                HttpClient.withConfig(_.timeout(config.timeout)) {
                    HttpClient.postText(url, Json.encode(req), headers)
                        .map(body =>
                            Abort.get(Json.decode[Response](body).mapFailure(e => HttpJsonDecodeException(e.getMessage, "POST", url)))
                        )
                }

    /** Parses one SSE data line as an OpenAI streaming chunk and extracts the tool-call argument fragment.
      *
      * Returns `Result.Success(Present(fragment))` when the line is a valid OpenAI streaming chunk that
      * carries a non-empty arguments delta. Returns `Result.Success(Absent)` when the line carries no
      * argument fragment: a content-only or empty delta, or the `[DONE]` terminator. Returns
      * `Result.Failure` when the line is not a parseable OpenAI streaming chunk.
      */
    def streamFragments(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Stream[String, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        Completion.sseFragments(config, streamRequest(config, context, resultSchema, resultTool), parseDeltaArguments)
    end streamFragments

    private[kyo] def parseDeltaArguments(line: String)(using Frame): Result[String, Maybe[String]] =
        import internal.*
        // The chat-completions stream terminates with a `[DONE]` sentinel line; treat it (and any content-only
        // or empty delta) as carrying no argument fragment, so the generic projection ignores it.
        if line.trim == "[DONE]" then Result.Success(Absent)
        else
            Json.decode[StreamChunk](line) match
                case Result.Success(chunk) =>
                    Result.Success(
                        chunk.choices
                            .flatMap(cs => Maybe.fromOption(cs.headOption))
                            .flatMap(_.delta)
                            .flatMap(_.tool_calls)
                            .flatMap(tcs => Maybe.fromOption(tcs.headOption))
                            .flatMap(_.function)
                            .flatMap(_.arguments)
                            .filter(_.nonEmpty)
                    )
                case _ =>
                    Result.Failure(s"Not a parseable OpenAI streaming chunk: $line")
            end match
        end if
    end parseDeltaArguments

    private[kyo] def streamRequest(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Completion.StreamRequest < Abort[AIStreamException] =
        import internal.*
        val headers =
            Seq("content-type" -> "application/json") ++
                config.apiKey.map(k => "Authorization" -> s"Bearer $k").toList ++
                config.apiOrg.map("OpenAI-Organization" -> _).toList
        Completion.StreamRequest(
            s"${config.apiUrl}/chat/completions",
            headers,
            Json.encode(Request(context, config, resultTool, Present(resultSchema)).copy(stream = Present(true)))
        )
    end streamRequest

    private object internal:

        // A single content part of a vision message. One flat record carries both shapes: the unused field is
        // `Absent` and so omitted, giving {"type":"text","text":"..."} or {"type":"image_url","image_url":{...}}.
        case class ImageUrl(url: String) derives Schema
        case class ContentPart(`type`: String, text: Maybe[String] = Absent, image_url: Maybe[ImageUrl] = Absent) derives Schema

        // Field order mirrors the OpenAI request wire (id, type, function) and (name, arguments); the
        // derived Schema writes fields in declaration order, so the order here IS the wire order.
        case class FunctionCall(name: String, arguments: String) derives Schema
        case class ToolCall(id: String, `type`: String, function: FunctionCall) derives Schema
        case class FunctionDef(description: String, name: String, strict: Boolean, parameters: JsonSchema) derives Schema
        case class ToolDef(function: FunctionDef, `type`: String = "function") derives Schema

        // The message `content` is polymorphic on the wire: a bare string for text messages, a parts array for
        // vision messages. It is carried as a Structure.Value, whose identity Schema writes shape-aware (a string
        // stays a string, a sequence becomes an array), reproducing both shapes from one field with no union.
        case class MessageEntry(
            role: String,
            content: Structure.Value,
            tool_calls: Maybe[List[ToolCall]] = Absent,
            tool_call_id: Maybe[String] = Absent
        ) derives Schema

        case class Request(
            model: String,
            temperature: Maybe[Double],
            max_completion_tokens: Maybe[Int],
            seed: Maybe[Int],
            messages: List[MessageEntry],
            tools: Maybe[List[ToolDef]],
            tool_choice: String = "required",
            stream: Maybe[Boolean] = Absent
        ) derives Schema
        case class ResponseFunctionCall(arguments: String, name: String) derives Schema
        case class ResponseToolCall(id: String, function: ResponseFunctionCall) derives Schema
        case class ResponseMessage(content: Maybe[String], tool_calls: Maybe[List[ResponseToolCall]]) derives Schema
        case class Choice(message: ResponseMessage) derives Schema
        // Decodes the reply's usage object (prompt/completion/cached tokens).
        case class PromptTokensDetails(cached_tokens: Maybe[Int] = Absent) derives Schema
        case class Usage(prompt_tokens: Int, completion_tokens: Int, prompt_tokens_details: Maybe[PromptTokensDetails] = Absent)
            derives Schema
        case class Response(choices: List[Choice], usage: Maybe[Usage] = Absent) derives Schema

        // Streaming delta DTOs: the OpenAI SSE wire shape for streaming tool-call chunks.
        // Each data: line carries a StreamChunk whose choices[0].delta.tool_calls[0].function.arguments
        // is the incremental argument JSON fragment. Fields are Maybe to tolerate absent-in-delta members.
        case class StreamFunctionDelta(arguments: Maybe[String] = Absent) derives Schema
        case class StreamToolCallDelta(function: Maybe[StreamFunctionDelta] = Absent) derives Schema
        case class StreamDelta(tool_calls: Maybe[List[StreamToolCallDelta]] = Absent) derives Schema
        case class StreamChoice(delta: Maybe[StreamDelta] = Absent) derives Schema
        case class StreamChunk(choices: Maybe[List[StreamChoice]] = Absent) derives Schema

        private def toEntry(msg: Message)(using Frame): MessageEntry =
            def text(s: String): Structure.Value = Structure.Value.Str(s)
            msg match
                case UserMessage(content, Present(image), _, _, _) =>
                    val parts = List(
                        ContentPart("text", text = Present(content)),
                        ContentPart("image_url", image_url = Present(ImageUrl(s"data:image/jpeg;base64,${image.base64}")))
                    )
                    MessageEntry("user", Structure.encode(parts))
                case UserMessage(content, _, _, _, _) =>
                    MessageEntry("user", text(content))
                case AssistantMessage(content, calls, _, _, _) =>
                    MessageEntry(
                        "assistant",
                        text(content),
                        tool_calls = Maybe.when(calls.nonEmpty)(calls.map(c =>
                            ToolCall(c.id.id, "function", FunctionCall(c.function, c.arguments))
                        ).toList)
                    )
                case ToolMessage(callId, content, _, _, _) =>
                    MessageEntry("tool", text(content), tool_call_id = Present(callId.id))
                case SystemMessage(content, _, _, _) =>
                    MessageEntry("system", text(content))
            end match
        end toEntry

        object Request:
            def apply(
                ctx: Context,
                config: Config,
                tools: Chunk[Tool.internal.Info[?, ?, LLM]],
                resultSchema: Maybe[JsonSchema]
            )(using Frame): Request =
                val entries = ctx.compacted.map(toEntry).toList
                val toolDefs =
                    if tools.isEmpty then Absent
                    else
                        Present(tools.map { p =>
                            val params =
                                if p.name == Completion.resultToolName then resultSchema.getOrElse(Json.jsonSchema(using p.inputSchema))
                                else Json.jsonSchema(using p.inputSchema)
                            ToolDef(FunctionDef(p.description, p.name, false, params))
                        }.toList)
                Request(
                    config.modelName,
                    config.temperature,
                    config.maxTokens,
                    config.seed,
                    entries,
                    toolDefs
                )
            end apply
        end Request

    end internal
end OpenAICompletion
