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
  * emits directly: the first context message is lifted into the top-level `system` field, the rest map to
  * user/assistant `Message`s, a tool result becomes a USER-role message with a `tool_result` block, and a
  * user image serializes as a multi-part text-then-image content list. A reply's `tool_use.input` is an
  * arbitrary object, carried as a `Structure.Value` and re-encoded to the raw argument JSON the `Call`
  * contract expects. Transport failures surface as `Abort[HttpException]`. The transport deadline for one
  * attempt is installed here; the eval loop adds the call's own deadline, which covers its retries.
  */
private[completion] object AnthropicCompletion extends Completion:

    import internal.*

    def apply(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: Maybe[JsonSchema] = Absent
    )(using Frame): Completion.Reply < (LLM & Async & Abort[HttpException | AIGenException]) =
        // One request, the same shape the Claude Code backend sends: thinking stays on and the result tool is
        // OFFERED rather than forced (a forced tool_choice is incompatible with thinking), so the model reasons
        // and serializes the result in one turn. The interleaved-thinking beta `fetch` installs lets that turn
        // spend its full thinking budget instead of self-limiting to a shallow first guess.
        fetch(config, Request(context, config, tools, resultSchema)).map(read(config, _))
    end apply

    // The reply's own termination vocabulary, decoded here and nowhere else.
    private def stopReason(raw: Maybe[String]): Completion.StopReason =
        raw match
            case Present("max_tokens") => Completion.StopReason.MaxOutputTokens
            // The wire's ordinary ways of finishing. Naming them matters: an unrecognized value is logged
            // to surface new vocabulary, so leaving the everyday values out would fire that signal on every
            // successful reply and bury the one case it exists to surface.
            case Present("end_turn") | Present("tool_use") | Present("stop_sequence") =>
                Completion.StopReason.Completed
            case Present(other) => Completion.StopReason.Other(other)
            case Absent         => Completion.StopReason.Completed

    private def read(config: Config, response: Response)(using Frame): Completion.Reply < (LLM & Sync & Abort[AIGenException]) =
        val calls = callsOf(response)
        val stop  = stopReason(response.stop_reason)
        // What a ceiling stop MEANS is decided by the eval loop, not here: telling a usable reply apart needs
        // the tool payload, which belongs to the tool loop. The reason rides along so the policy is decided once.
        val surfaced =
            stop match
                // An unfamiliar stop value must never fail a usable reply, but it is worth surfacing: the
                // first sign a provider added vocabulary this decode does not yet understand.
                case Completion.StopReason.Other(raw) =>
                    Log.debug(s"kyo-ai ${config.provider.name} unrecognized stop reason: $raw")
                case _ => Kyo.unit
        val usage = response.usage.fold(AIStats(0L, Absent, 0L, Absent, 1))(usageStats)
        surfaced.andThen(Completion.Reply(Chunk(AssistantMessage(textOf(response), calls)), stop, usage))
    end read

    /** This wire reports cache traffic BESIDE input_tokens, so the input total sums all three;
      * creation counts as read (a fresh read that populates the cache), not as cached. Reasoning is
      * never broken out of output_tokens on this wire, so the subset stays Absent.
      */
    private def usageStats(u: internal.Usage): AIStats =
        AIStats(
            inputTokens = u.input_tokens + u.cache_read_input_tokens.getOrElse(0L) + u.cache_creation_input_tokens.getOrElse(0L),
            cachedInputTokens = u.cache_read_input_tokens,
            outputTokens = u.output_tokens,
            reasoningOutputTokens = Absent,
            turns = 1
        )

    private def textOf(response: Response): String =
        response.content.collect { case c if c.`type` == "text" => c.text.getOrElse("") }.mkString("")

    private def callsOf(response: Response)(using Frame): Chunk[Call] =
        response.content.collect {
            case c if c.`type` == "tool_use" =>
                Call(CallId(c.id.getOrElse("")), c.name.getOrElse(""), Json.encode(c.input.getOrElse(Structure.Value.Record(Chunk.empty))))
        }.to(Chunk)

    private def fetch(config: Config, req: Request)(using Frame): Response < (LLM & Async & Abort[HttpException | AIGenException]) =
        config.apiKey match
            case Absent =>
                Abort.fail(AIMissingApiKeyException(config.modelName))
            case Present(key) =>
                // Interleaved thinking whenever the request enables thinking, the same beta the Claude Code
                // backend sends. Without it the model self-limits below its budget (measured: 7407 of a 12000
                // budget on a real puzzle); with it the single turn spends the full budget before the result.
                val headers = Seq(
                    "content-type"      -> "application/json",
                    "x-api-key"         -> key,
                    "anthropic-version" -> "2023-06-01"
                ) ++ (if req.thinking.isDefined then Seq("anthropic-beta" -> "interleaved-thinking-2025-05-14") else Seq.empty)
                val url = s"${config.apiUrl.stripSuffix("/")}/messages"
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
    )(using Frame): Stream[Completion.StreamElement, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        Completion.sseFragments(
            config,
            streamRequest(config, context, resultSchema, resultTool),
            parseDeltaArguments,
            Present(config.effectiveMaxOutputTokens)
        )
    end streamFragments

    private[kyo] def streamRequest(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Completion.StreamRequest < Abort[AIStreamException] =
        config.apiKey match
            case Absent =>
                // Fail typed like `fetch`: a missing key sends no HTTP request and surfaces as the typed
                // missing-key failure.
                Abort.fail(AIMissingApiKeyException(config.modelName))
            case Present(key) =>
                val req = Request(context, config, resultTool, Present(resultSchema)).copy(stream = Present(true))
                // The same interleaved-thinking beta `fetch` sends, so the streaming path spends the
                // configured budget instead of self-limiting below it.
                val headers =
                    Seq(
                        "content-type"      -> "application/json",
                        "x-api-key"         -> key,
                        "anthropic-version" -> "2023-06-01"
                    ) ++ (if req.thinking.isDefined then Seq("anthropic-beta" -> "interleaved-thinking-2025-05-14") else Seq.empty)
                Completion.StreamRequest(
                    s"${config.apiUrl.stripSuffix("/")}/messages",
                    headers,
                    Json.encode(req)
                )
    end streamRequest

    /** Parses one `/messages` SSE data line. Tool-call arguments arrive as `content_block_delta` events whose
      * `delta` is an `input_json_delta` carrying a `partial_json` fragment; every other event type
      * (`message_start`, `content_block_start`, `text_delta`, `message_delta`, `message_stop`, `ping`) carries
      * no tool-argument fragment. There is no `[DONE]` terminator; the stream ends at `message_stop`.
      */
    private[kyo] def parseDeltaArguments(line: String)(using Frame): Result[String, Completion.Delta] =
        import internal.*
        Json.decode[StreamEvent](line) match
            case Result.Success(event) =>
                Result.Success(event.delta match
                    case Present(d) if d.`type`.contains("input_json_delta") && d.partial_json.exists(_.nonEmpty) =>
                        Completion.Delta.Fragment(d.partial_json.getOrElse(""))
                    case Present(d) if stopReason(d.stop_reason) == Completion.StopReason.MaxOutputTokens =>
                        Completion.Delta.OutputLimit
                    case _ =>
                        // Usage arrives split: message_start embeds the input side, message_delta the final
                        // output count. Each side is emitted as a DISJOINT partial (the input partial zeroes
                        // output and carries the turn count; the output partial carries output alone), so the
                        // consumer's sum is exact regardless of what extra fields either event carries.
                        event.message.flatMap(_.usage).fold(
                            event.usage.flatMap(_.output_tokens).fold(Completion.Delta.Skip)(outputTokens =>
                                Completion.Delta.Usage(AIStats(0L, Absent, outputTokens, Absent, 0))
                            )
                        )(u =>
                            Completion.Delta.Usage(AIStats(
                                inputTokens = u.input_tokens.getOrElse(0L) +
                                    u.cache_read_input_tokens.getOrElse(0L) +
                                    u.cache_creation_input_tokens.getOrElse(0L),
                                cachedInputTokens = u.cache_read_input_tokens,
                                outputTokens = 0L,
                                reasoningOutputTokens = Absent,
                                turns = 1
                            ))
                        ))
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
            source: Maybe[Source] = Absent,
            // Extended-thinking reply blocks ({"type":"thinking","thinking":...,"signature":...}); decoded so
            // the reply parses, then ignored by `read` (the reasoning is not surfaced as a transcript message).
            thinking: Maybe[String] = Absent,
            signature: Maybe[String] = Absent
        ) derives Schema
        case class Source(`type`: String, media_type: String, data: String) derives Schema
        case class Message(role: String, content: List[Content]) derives Schema
        // strict = true enables Anthropic structured outputs: token-level constrained decoding against the
        // input_schema. Strict mode requires the schema in all-required / additionalProperties:false form, so
        // input_schema is carried as a raw Structure.Value (the strict-transformed schema for the forced
        // result tool, or an advisory schema otherwise).
        case class ToolDefinition(
            name: String,
            description: Maybe[String],
            input_schema: Structure.Value,
            strict: Maybe[Boolean] = Absent
        ) derives Schema
        case class ToolChoice(`type`: String, name: Maybe[String] = Absent) derives Schema
        case class Thinking(`type`: String, budget_tokens: Maybe[Int] = Absent) derives Schema
        case class Request(
            model: String,
            messages: List[Message],
            system: Maybe[String],
            max_tokens: Int,
            temperature: Maybe[Double],
            tools: Maybe[List[ToolDefinition]] = Absent,
            tool_choice: Maybe[ToolChoice] = Absent,
            thinking: Maybe[Thinking] = Absent,
            stream: Maybe[Boolean] = Absent
        ) derives Schema
        case class Usage(
            input_tokens: Long,
            output_tokens: Long,
            cache_read_input_tokens: Maybe[Long] = Absent,
            cache_creation_input_tokens: Maybe[Long] = Absent
        ) derives Schema

        // Streaming SSE event DTOs. Fields are Maybe to tolerate the non-tool event types (message_start,
        // content_block_start, text_delta, message_delta, message_stop, ping).
        case class StreamDelta(
            `type`: Maybe[String] = Absent,
            partial_json: Maybe[String] = Absent,
            stop_reason: Maybe[String] = Absent
        ) derives Schema
        // message_start embeds the message object whose usage carries the input side; message_delta
        // carries an event-level usage with the final output count. Both are decoded tolerantly.
        case class StreamMessage(usage: Maybe[StreamUsage] = Absent) derives Schema
        case class StreamUsage(
            input_tokens: Maybe[Long] = Absent,
            output_tokens: Maybe[Long] = Absent,
            cache_read_input_tokens: Maybe[Long] = Absent,
            cache_creation_input_tokens: Maybe[Long] = Absent
        ) derives Schema
        case class StreamEvent(
            `type`: Maybe[String] = Absent,
            delta: Maybe[StreamDelta] = Absent,
            message: Maybe[StreamMessage] = Absent,
            usage: Maybe[StreamUsage] = Absent
        ) derives Schema
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
                // The <system-reminder> wrapper, matching ClaudeCodeWire.turnInput: the harness-instruction
                // idiom Claude models are trained to honor. An invented marker reads as a prompt injection and
                // the model refuses it, which is why this wire keeps its own idiom over the OpenAI prefix.
                def systemReminder(content: String): String = s"<system-reminder>\n$content\n</system-reminder>"
                // The shared transform merges the leading system run into one message and converts later
                // system messages to reminder-wrapped user turns; the impl lifts that merged leading message
                // into `system` and maps the rest. Pull the system prompt off ONLY when the conversation
                // starts with it: dropping the first message unconditionally discarded an opening user turn.
                val fitted =
                    Completion.fitSystemMessages(
                        config,
                        ctx.messages,
                        content => UserMessage(systemReminder(content), Absent)
                    )
                val (system, body) =
                    fitted.headMaybe match
                        case Present(SystemMessage(c)) => (Present(c), fitted.drop(1))
                        case _                         => (Absent, fitted)
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
                            // Residual only: the transform converts every non-leading system message, so this
                            // fires just for a system message not at the start (no leading system prompt to lift).
                            Message(Role.User.name, List(Content("text", text = Present(systemReminder(content)))))
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
                            val desc = Maybe.when(t.description.nonEmpty)(t.description)
                            if t.name == Completion.resultToolName then
                                val raw = resultSchema.getOrElse(Json.jsonSchema(using t.inputSchema))
                                if config.reasoningEnabled then
                                    // Extended thinking is incompatible with strict grammar-constrained decoding:
                                    // the combination slows a call from ~3s to ~30s+ (the constrained decoder
                                    // fights the thinking pass). So the schema is advisory here with every property
                                    // required, the same require-all shape the Claude Code MCP result tool
                                    // advertises; the eval loop's validate-and-repair covers a non-conforming reply.
                                    ToolDefinition(t.name, desc, Structure.encode(StrictSchema.requireAll(raw)))
                                else
                                    // No thinking: enforce the forced result tool with structured outputs (strict = true).
                                    // If the schema cannot be made strict-compatible, fall back to the advisory schema.
                                    StrictSchema.result(raw, allowMaps = false) match
                                        case Result.Success(strictValue) => ToolDefinition(t.name, desc, strictValue, Present(true))
                                        case _                           => ToolDefinition(t.name, desc, Structure.encode(raw))
                                end if
                            else
                                ToolDefinition(t.name, desc, Structure.encode(Json.jsonSchema(using t.inputSchema)))
                            end if
                        }.toList)
                // Reasoning happens before the answer, the native-API analog of the Claude Code full
                // reasoning turn. The ACTIVATION field below makes the request incompatible with a forced
                // tool, so no tool_choice is sent (the API defaults to auto, the same absent-auto the Claude
                // Code CLI carries) and the model calls the result tool itself; the eval loop's force-result
                // repair covers a reply that skips it. Temperature must also be unset while reasoning.
                //
                // The shape follows the entry's declared encoding: a token budget rides the bounded form, a
                // self-sized entry the adaptive form. The encodings this wire has no field for send no
                // activation, so their turns stay forced.
                val thinking =
                    if !config.reasoningEnabled then Maybe.empty[Thinking]
                    else
                        config.modelReasoning match
                            case Config.ReasoningEncoding.TokenBudget =>
                                config.resolvedAmount match
                                    case Present(Config.Amount.Budget(tokens)) => Present(Thinking("enabled", Present(tokens)))
                                    // Unreachable while resolution guarantees a budget on this encoding, written
                                    // to stay harmless if that changes: the adaptive shape is the one a
                                    // budget-taking wire refuses, so it defaults to a budget rather than adaptive.
                                    case _ => Present(Thinking("enabled", Present(Config.defaultReasoningBudget)))
                            case Config.ReasoningEncoding.Adaptive => Present(Thinking("adaptive"))
                            case _                                 =>
                                // This wire has no field for the remaining encodings. Off is expressed by
                                // omission here, so sending nothing is also how "cannot state it" reads.
                                Maybe.empty[Thinking]
                // Same rule as the OpenAI family: unforced iff the request is reasoning-active AND this entry
                // declares its wire refuses the pair. "Active" is derived from the request, not the thinking
                // field: reading activity directly closes the hole a Managed-style entry would open (thinking
                // empty yet reasoning on) rather than forcing a request it reasons on.
                val reasoningActive = config.reasoningEnabled && config.modelReasoning != Config.ReasoningEncoding.Unavailable
                val toolChoice =
                    if reasoningActive && config.forcedToolChoice == Config.ForcedToolChoice.RefusedWhileReasoning then
                        Maybe.empty[ToolChoice]
                    else
                        tools.headMaybe match
                            case Present(tool) if tools.size == 1 && tool.name == Completion.resultToolName =>
                                Present(ToolChoice("tool", Present(Completion.resultToolName)))
                            case Present(_) => Present(ToolChoice("any"))
                            case Absent     => Absent
                // The system field carries only the conversation's leading system message. The result contract
                // (envelope schema and description) rides the result tool definition: grammar-enforced on the
                // non-thinking branch (strict above), advisory require-all on the thinking branch. No envelope
                // restatement is appended: the tool definition already carries the full contract, and an
                // appended "answer only the final user message" makes the model discount earlier tool results
                // it must copy from on a forced result turn.
                // A configured temperature reaches the wire only with thinking off (Anthropic requires
                // temperature 1 with thinking) and only when the entry declares the model accepts the parameter.
                val temperature =
                    if thinking.isDefined || !config.modelAcceptsTemperature then Absent
                    else config.temperature
                // Thinking tokens count toward max_tokens, so max_tokens MUST exceed the thinking budget (with
                // output room) or the request is refused. Config.effectiveMaxOutputTokens sizes it from the
                // entry's declared reasoning kind and output maximum.
                Request(config.modelName, messages, system, config.effectiveMaxOutputTokens, temperature, toolDefs, toolChoice, thinking)
            end apply
        end Request

    end internal
end AnthropicCompletion
