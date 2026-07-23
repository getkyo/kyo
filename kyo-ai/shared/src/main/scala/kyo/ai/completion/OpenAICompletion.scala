package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

/** The completion backend for every provider whose endpoint speaks the OpenAI-compatible request shape.
  *
  * Serializes the conversation to the `/chat/completions` request shape via the request DTO's `Json.encode`.
  * A user message with an image serializes as a content-parts array, one without as a plain string `content`;
  * the polymorphic `content` is carried as a `Structure.Value`, whose identity Schema writes shape-aware (a
  * string stays a string, a sequence becomes an array), reproducing both wire shapes from one field. The
  * ceiling's FIELD NAME is the endpoint's declared fact rather than a constant: these endpoints do not agree
  * on it, and one handed the wrong name drops it silently. The reply decode is null-tolerant (absent content
  * -> "", absent tool_calls -> Nil); empty `choices` aborts with `AIDecodeException`. The content-type header
  * is set explicitly (kyo-http defaults to text/plain). The transport deadline for one attempt is installed
  * here; the eval loop adds the call's own deadline, which covers its retries.
  */
private[completion] object OpenAICompletion extends Completion:

    import internal.*

    def apply(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: Maybe[JsonSchema] = Absent
    )(using Frame): Completion.Reply < (LLM & Async & Abort[HttpException | AIGenException]) =
        fetch(config, Request(context, config, tools, resultSchema)).map(read(config, _))

    // The deactivation type this wire's activation object carries. A byte constant, never compared against,
    // only written.
    private val offThinking = "disabled"

    // The reply's own termination vocabulary, decoded here and nowhere else.
    private def stopReason(raw: Maybe[String]): Completion.StopReason =
        raw match
            case Present("length") => Completion.StopReason.MaxOutputTokens
            // The wire's ordinary ways of finishing. Naming them matters: an unrecognized value is logged
            // to surface new vocabulary, so leaving the everyday values out would fire that signal on every
            // successful reply and bury the one case it exists to surface.
            case Present("stop") | Present("tool_calls") => Completion.StopReason.Completed
            case Present(other)                          => Completion.StopReason.Other(other)
            case Absent                                  => Completion.StopReason.Completed

    /** Warns when the reply's own numbers contradict the ceiling the request carried.
      *
      * A request's ceiling can go missing without any error: an endpoint that ignores the field drops it and
      * applies its own default, and a reply that stops there looks like an ordinary long answer. A stop AT the
      * ceiling that spent materially fewer tokens than asked means the limit that stopped it was not this
      * request's (the field never applied, or a stricter limit did); spending MORE means the ceiling did not
      * bind at all. Both are stated as the disjunction they are. Reads only sent-vs-reported, so it names no
      * provider and no model.
      */
    private def warnCeilingMismatch(config: Config, response: Response, stop: Completion.StopReason)(using Frame): Unit < Sync =
        val spent = response.usage.flatMap(_.completion_tokens)
        // Compared against what was SENT, not what was asked for. The request clamps the ask to the model's
        // declared maximum, so comparing against the raw ask would report kyo's own clamp as an endpoint
        // anomaly: an above-maximum ask stops at the clamp, spends less, and would trip a false warning.
        (config.maxTokens.map(_.min(config.modelMaxOutputTokens)), spent) match
            case (Present(asked), Present(used)) if stop == Completion.StopReason.MaxOutputTokens && used < asked =>
                Log.warn(
                    s"kyo-ai ${config.provider.name} stopped at an output ceiling of $used tokens while the request " +
                        s"asked for $asked: the ceiling this request set did not apply, either because the endpoint " +
                        s"did not read the field it was sent or because a stricter limit bound first"
                )
            case (Present(asked), Present(used)) if used > asked =>
                Log.warn(
                    s"kyo-ai ${config.provider.name} produced $used output tokens against a requested ceiling of " +
                        s"$asked: the ceiling this request set did not bind"
                )
            case _ => Kyo.unit
        end match
    end warnCeilingMismatch

    /** Warns when a reply carries reasoning an entry declared it would not produce.
      *
      * A wrong "does not reason" declaration is otherwise invisible: the request never states an amount and
      * the reply looks ordinary. The reply carries the contradiction (reasoning text or usage detail), so the
      * wrong declaration announces itself on the first generation instead of on the day someone investigates
      * an unexplained token count. Reads only what the reply reports, so it names no provider and no model.
      */
    private def warnUndeclaredReasoning(config: Config, response: Response)(using Frame): Unit < Sync =
        val reasoned =
            response.choices.headOption.exists(_.message.reasoning_content.exists(_.nonEmpty)) ||
                response.usage.flatMap(_.completion_tokens_details).flatMap(_.reasoning_tokens).exists(_ > 0)
        if reasoned && config.modelReasoning == Config.ReasoningEncoding.Unavailable then
            Log.warn(
                s"kyo-ai ${config.provider.name} model ${config.modelName} returned reasoning while its catalog " +
                    s"entry declares that it does not reason: the declaration is wrong, and any ceiling sized from " +
                    s"it is sized for a reply that reasons less than this one did"
            )
        else Kyo.unit
        end if
    end warnUndeclaredReasoning

    private def read(config: Config, response: Response)(using Frame): Completion.Reply < (LLM & Sync & Abort[AIGenException]) =
        Maybe.fromOption(response.choices.headOption) match
            case Absent =>
                Abort.fail(AIDecodeException("LLM response has no choices: " + Json.encode(response)))
            case Present(v) =>
                val calls = Chunk.from(v.message.tool_calls.getOrElse(Nil).map(c =>
                    Call(CallId(c.id), c.function.name, c.function.arguments, c.extra_content)
                ))
                val stop = stopReason(v.finish_reason)
                // What a ceiling stop MEANS is not decided here: telling a usable reply apart needs the tool
                // payload, which belongs to the tool loop. The reason rides along and the loop decides once.
                warnCeilingMismatch(config, response, stop).andThen(warnUndeclaredReasoning(config, response)).andThen {
                    val surfaced =
                        stop match
                            // An unfamiliar stop value must never fail a usable reply, but it is worth
                            // surfacing: the first sign a provider added vocabulary this decode does not yet understand.
                            case Completion.StopReason.Other(raw) =>
                                Log.debug(s"kyo-ai ${config.provider.name} unrecognized finish reason: $raw")
                            case _ => Kyo.unit
                    surfaced.andThen(
                        Completion.Reply(
                            Chunk(AssistantMessage(v.message.content.getOrElse(""), calls)),
                            stop,
                            response.usage.flatMap(_.completion_tokens_details).flatMap(_.reasoning_tokens),
                            toUsage(response.usage)
                        )
                    )
                }

    // Converts the wire usage object to the public Completion.Usage, only when the input and output
    // totals both arrived. cached input tokens ride when the endpoint reports them.
    private def toUsage(usage: Maybe[internal.Usage]): Maybe[Completion.Usage] =
        usage.flatMap { u =>
            (u.prompt_tokens, u.completion_tokens) match
                case (Present(p), Present(c)) =>
                    Present(Completion.Usage(p, c, u.prompt_tokens_details.flatMap(_.cached_tokens)))
                case _ => Absent
        }

    private def fetch(config: Config, req: Request)(using Frame): Response < (LLM & Async & Abort[HttpException | AIGenException]) =
        config.apiKey match
            case Absent =>
                Abort.fail(AIMissingApiKeyException(config.modelName))
            case Present(key) =>
                val headers =
                    Seq("content-type" -> "application/json", "Authorization" -> s"Bearer $key") ++
                        config.apiOrg.map("OpenAI-Organization" -> _).toList
                // stripSuffix rather than raw concat: one endpoint publishes its base URL with a trailing
                // slash, and the resulting double slash was answered 404, with the reply naming neither URL nor model.
                val url  = s"${config.apiUrl.stripSuffix("/")}/chat/completions"
                val body = Json.encode(req)
                // Trace, not debug: the body carries the conversation.
                Log.trace(s"kyo-ai request ${config.provider.name} $url ${Completion.elideBody(body)}").andThen {
                    HttpClient.withConfig(_.timeout(config.timeout)) {
                        HttpClient.postText(url, body, headers)
                            .map(replyBody =>
                                // Trace, not debug: the reply pairs with its request.
                                Log.trace(s"kyo-ai reply ${config.provider.name} $url ${Completion.elideBody(replyBody)}").andThen {
                                    Abort.get(Json.decode[Response](replyBody).mapFailure(e =>
                                        HttpJsonDecodeException(e.getMessage, "POST", url)
                                    ))
                                }
                            )
                    }
                }

    /** Streams the result-envelope fragments by posting the native SSE request and projecting each line's
      * tool-call argument delta through [[parseDeltaArguments]].
      */
    def streamFragments(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]],
        usageSink: AtomicRef[Maybe[Completion.Usage]]
    )(using Frame): Stream[String, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        Completion.sseFragments(
            config,
            streamRequest(config, context, resultSchema, resultTool),
            parseDeltaArguments,
            config.maxTokens,
            parseStreamUsage,
            usageSink
        )
    end streamFragments

    // The include_usage final chunk carries an empty choices array and a top-level usage object
    // (prompt/completion/cached tokens) before the [DONE] sentinel. Decode it and convert the wire
    // usage to the public Completion.Usage, the same conversion the gen read performs; any non-usage
    // chunk or the sentinel yields Absent.
    private[kyo] def parseStreamUsage(line: String)(using Frame): Maybe[Completion.Usage] =
        import internal.*
        if line.trim == "[DONE]" then Absent
        else toUsage(Json.decode[StreamChunk](line).toMaybe.flatMap(_.usage))
    end parseStreamUsage

    private[kyo] def parseDeltaArguments(line: String)(using Frame): Result[String, Completion.Delta] =
        import internal.*
        // The chat-completions stream terminates with a `[DONE]` sentinel; treat it (and any content-only
        // or empty delta) as carrying no argument fragment, so the generic projection skips it.
        if line.trim == "[DONE]" then Result.Success(Completion.Delta.Skip)
        else
            Json.decode[StreamChunk](line) match
                case Result.Success(chunk) =>
                    val choice = chunk.choices.flatMap(cs => Maybe.fromOption(cs.headOption))
                    val fragment = choice
                        .flatMap(_.delta)
                        .flatMap(_.tool_calls)
                        .flatMap(tcs => Maybe.fromOption(tcs.headOption))
                        .flatMap(_.function)
                        .flatMap(_.arguments)
                        .filter(_.nonEmpty)
                    Result.Success(
                        fragment match
                            case Present(f) => Completion.Delta.Fragment(f)
                            case Absent =>
                                if choice.exists(c => stopReason(c.finish_reason) == Completion.StopReason.MaxOutputTokens)
                                then Completion.Delta.OutputLimit
                                else Completion.Delta.Skip
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
        config.apiKey match
            case Absent =>
                // Fail typed like `fetch`: a missing key sends no HTTP request and surfaces as the same
                // typed missing-key failure the Anthropic streaming path raises.
                Abort.fail(AIMissingApiKeyException(config.modelName))
            case Present(key) =>
                val headers =
                    Seq("content-type" -> "application/json", "Authorization" -> s"Bearer $key") ++
                        config.apiOrg.map("OpenAI-Organization" -> _).toList
                Completion.StreamRequest(
                    s"${config.apiUrl.stripSuffix("/")}/chat/completions",
                    headers,
                    Json.encode(Request(context, config, resultTool, Present(resultSchema))
                        .copy(stream = Present(true), stream_options = Present(StreamOptions(true))))
                )
        end match
    end streamRequest

    private object internal:

        // A single content part of a vision message. One flat record carries both shapes: the unused field is
        // `Absent` and so omitted, giving {"type":"text","text":"..."} or {"type":"image_url","image_url":{...}}.
        case class ImageUrl(url: String) derives Schema
        case class ContentPart(`type`: String, text: Maybe[String] = Absent, image_url: Maybe[ImageUrl] = Absent) derives Schema

        // Field order mirrors the OpenAI request wire (id, type, function) and (name, arguments); the
        // derived Schema writes fields in declaration order, so the order here IS the wire order.
        case class FunctionCall(name: String, arguments: String) derives Schema
        // extra_content round-trips the endpoint's own per-call token. It is written back exactly as it
        // arrived, and omitted when a call carried none, so a wire that never sends one sees no new field.
        case class ToolCall(
            id: String,
            `type`: String,
            function: FunctionCall,
            extra_content: Maybe[Structure.Value] = Absent
        ) derives Schema
        case class FunctionDef(description: String, name: String, strict: Boolean, parameters: JsonSchema) derives Schema
        case class ToolDef(function: FunctionDef, `type`: String = "function") derives Schema

        // The wire `content` is polymorphic: a bare string for text, a parts array for vision. Carried as a
        // Structure.Value, whose identity Schema writes shape-aware, reproducing both shapes with no union.
        case class MessageEntry(
            role: String,
            content: Structure.Value,
            tool_calls: Maybe[List[ToolCall]] = Absent,
            tool_call_id: Maybe[String] = Absent
        ) derives Schema

        case class ThinkingToggle(`type`: String) derives Schema
        // Opt-in to usage on the streaming response: the final SSE chunk then carries a top-level usage
        // object with empty choices before [DONE].
        case class StreamOptions(include_usage: Boolean) derives Schema
        case class Request(
            model: String,
            temperature: Maybe[Double],
            max_completion_tokens: Maybe[Int],
            seed: Maybe[Int],
            messages: List[MessageEntry],
            tools: Maybe[List[ToolDef]],
            stream_options: Maybe[StreamOptions] = Absent,
            // Forced unless the request activates reasoning: two endpoints behind this implementation
            // refuse a forced choice while an activation field rides, and the eval loop already carries
            // the unforced turn shape for the harnesses that cannot force at all.
            tool_choice: String = "required",
            stream: Maybe[Boolean] = Absent,
            thinking: Maybe[ThinkingToggle] = Absent,
            // The graded reasoning encoding. Carries a level word from the entry's own levels, or the
            // wire's off word where that is how this endpoint says "do not reason".
            reasoning_effort: Maybe[String] = Absent,
            // Exactly one of this and max_completion_tokens ever carries a value; which one is the
            // endpoint's declared fact. The unset one is omitted from the body entirely.
            max_tokens: Maybe[Int] = Absent
        ) derives Schema
        case class ResponseFunctionCall(arguments: String, name: String) derives Schema
        case class ResponseToolCall(
            id: String,
            function: ResponseFunctionCall,
            extra_content: Maybe[Structure.Value] = Absent
        ) derives Schema
        // reasoning_content is decoded solely so a mis-declared entry announces itself: an entry
        // declared not to reason, whose replies carry reasoning, is stating something false about its
        // wire. Nothing downstream consumes the text.
        case class ResponseMessage(
            content: Maybe[String],
            tool_calls: Maybe[List[ResponseToolCall]],
            reasoning_content: Maybe[String] = Absent
        ) derives Schema
        case class Choice(message: ResponseMessage, finish_reason: Maybe[String] = Absent) derives Schema
        case class CompletionTokensDetails(reasoning_tokens: Maybe[Int] = Absent) derives Schema
        // Decodes the reply's cached-prompt token count where the endpoint reports it.
        case class PromptTokensDetails(cached_tokens: Maybe[Int] = Absent) derives Schema
        case class Usage(
            prompt_tokens: Maybe[Int] = Absent,
            completion_tokens: Maybe[Int] = Absent,
            completion_tokens_details: Maybe[CompletionTokensDetails] = Absent,
            prompt_tokens_details: Maybe[PromptTokensDetails] = Absent
        ) derives Schema
        case class Response(choices: List[Choice], usage: Maybe[Usage] = Absent) derives Schema

        // Streaming delta DTOs: the OpenAI SSE wire shape for streaming tool-call chunks.
        // Each data: line carries a StreamChunk whose choices[0].delta.tool_calls[0].function.arguments
        // is the incremental argument JSON fragment. Fields are Maybe to tolerate absent-in-delta members.
        case class StreamFunctionDelta(arguments: Maybe[String] = Absent) derives Schema
        case class StreamToolCallDelta(function: Maybe[StreamFunctionDelta] = Absent) derives Schema
        case class StreamDelta(tool_calls: Maybe[List[StreamToolCallDelta]] = Absent) derives Schema
        case class StreamChoice(delta: Maybe[StreamDelta] = Absent, finish_reason: Maybe[String] = Absent) derives Schema
        case class StreamChunk(choices: Maybe[List[StreamChoice]] = Absent, usage: Maybe[Usage] = Absent) derives Schema

        private def toEntry(msg: Message)(using Frame): MessageEntry =
            def text(s: String): Structure.Value = Structure.Value.Str(s)
            msg match
                case UserMessage(content, Present(image), _, _) =>
                    val parts = List(
                        ContentPart("text", text = Present(content)),
                        ContentPart("image_url", image_url = Present(ImageUrl(s"data:image/jpeg;base64,${image.base64}")))
                    )
                    MessageEntry("user", Structure.encode(parts))
                case UserMessage(content, _, _, _) =>
                    MessageEntry("user", text(content))
                case AssistantMessage(content, calls, _, _) =>
                    MessageEntry(
                        "assistant",
                        text(content),
                        tool_calls = Maybe.when(calls.nonEmpty)(calls.map(c =>
                            ToolCall(c.id.id, "function", FunctionCall(c.function, c.arguments), c.providerExtra)
                        ).toList)
                    )
                case ToolMessage(callId, content, _, _) =>
                    MessageEntry("tool", text(content), tool_call_id = Present(callId.id))
                case SystemMessage(content, _, _) =>
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
                // Fitted before mapping, so the wire sees the shape its entry declares. On a single-system
                // wire, later system messages arrive as user turns behind a prelude naming what they are;
                // the prefix is this family's serialization idiom.
                val convert = (content: String) => UserMessage(s"${Completion.systemInstructionPrefix} $content", Absent)
                val entries = Completion.fitSystemMessages(config, ctx.compacted, convert).map(toEntry).toList
                val toolDefs =
                    if tools.isEmpty then Absent
                    else
                        Present(tools.map { p =>
                            val params =
                                // The result tool advertises the require-all envelope (StrictSchema.requireAll),
                                // the ADVISORY result shape every backend shares, so the default result contract
                                // is identical across the four; Anthropic's strict form under disableReasoning is
                                // the one platform-forced superset. Advisory here (strict:false; the CALL is
                                // compelled by tool_choice:"required"), so a required optional field is schema
                                // pressure, not grammar enforcement.
                                if p.name == Completion.resultToolName then
                                    StrictSchema.requireAll(resultSchema.getOrElse(Json.jsonSchema(using p.inputSchema)))
                                else Json.jsonSchema(using p.inputSchema)
                            ToolDef(FunctionDef(p.description, p.name, false, params))
                        }.toList)
                // A configured temperature reaches the wire only when the entry declares the model accepts it.
                val temperature = if config.modelAcceptsTemperature then config.temperature else Absent
                // Reasoning reaches this family through two different fields; which one an endpoint reads is
                // the entry's declared encoding, not inferred here. The distinction that matters below is
                // ACTIVATION versus DEACTIVATION: a level word activates and this wire then refuses a forced
                // tool choice, while an off encoding deactivates and still forces. Whether an activation rides
                // is decided HERE and carried out in `activated`, not recovered afterwards by comparing bytes:
                // string equality would misread this wire's own off word as a level (or a differing off word as
                // an activation) and flip the tool choice on a request that must not have it flipped.
                // The loop's last-resort turn carries ONLY the result tool and exists to compel the call.
                // Since this wire refuses a forced choice while reasoning is activated, the forced turn states
                // reasoning OFF to stay forceable: the reasoning already happened on prior turns, and this turn
                // only emits the result. Omitting the field would not do, since a wire that reasons by default
                // stays activated and still refuses the forced choice.
                val forcedResultTurn =
                    tools.size == 1 && tools.headMaybe.exists(_.name == Completion.resultToolName)
                val reasoningRequested = config.reasoningEnabled && !forcedResultTurn
                val (thinking, reasoningEffort, activated) =
                    if !reasoningRequested then
                        // Off, in whichever encoding this endpoint reads. A non-reasoning entry sends nothing:
                        // the state already holds, and the field that says "off" on a reasoning entry is refused
                        // outright by a non-reasoning one. No arm here activates, so the flag is a literal.
                        config.modelReasoning match
                            case Config.ReasoningEncoding.Unavailable => (Absent, Absent, false)
                            case _ =>
                                config.reasoningOff match
                                    case Config.ReasoningOff.ThinkingDisabled => (Present(ThinkingToggle(offThinking)), Absent, false)
                                    case Config.ReasoningOff.Level(word)      => (Absent, Present(word), false)
                                    // An endpoint that cannot be switched off gets the least it accepts, from the
                                    // declaration, not a level list's order. It still reasons, so this ACTIVATES:
                                    // a level word is an activation, and reporting it deactivated would put both a
                                    // level and a forced choice on one request, which the refusing endpoints reject.
                                    case Config.ReasoningOff.CannotDisable(lowest) => (Absent, Present(lowest), true)
                                    case _                                         => (Absent, Absent, false)
                    else
                        config.resolvedAmount match
                            case Present(Config.Amount.Level(value)) => (Absent, Present(value), true)
                            case _                                   =>
                                // No activation field exists for the remaining encodings, so the request states
                                // nothing. Whether reasoning is ACTIVE is separate from whether a field rides,
                                // and the forced choice turns on the former: an entry whose wire reasons on its
                                // own is reasoning now with nothing saying so, and the refusing wire refuses on
                                // that basis. Reading the absent field as "not reasoning" would force a request
                                // that is reasoning, and it is rejected.
                                val reasoningWithoutAField =
                                    config.modelReasoning == Config.ReasoningEncoding.Managed
                                (Absent, Absent, reasoningWithoutAField)
                // The endpoints behind this implementation do not read the ceiling from the same field, and
                // one handed the wrong name drops it silently and applies its own default, so the ask
                // disappears without a trace; sending both is no way out (one family refuses the older name
                // even alongside the current one). The declared fact picks the single name that endpoint honors.
                //
                // Clamped to what the model can produce. An above-maximum ask has two downsides: one endpoint
                // refuses the request over it, another quietly applies its own smaller limit, which reads as
                // the model stopping early for no reason.
                //
                // An unset ceiling sends the model's OWN maximum, not nothing: every entry now declares its
                // limit, so sending it caps nothing, whereas withholding left the endpoint's undeclared default
                // in force (measured six times smaller than the declared maximum on one wire, stopping a
                // generation with no answer while this module reported the larger number as the ceiling).
                //
                // Only a VERIFIED maximum rides or clamps. Where the entry's number is a stand-in, nothing is
                // sent and an over-large ask is left alone: the endpoint then refuses it and names the real
                // bound, the probe that settles the entry, better than a limit invented here.
                val asked =
                    config.maxTokens.map(t => config.sendableMaximum.fold(t)(t.min(_)))
                        .orElse(config.sendableMaximum)
                val (maxCompletionTokens, maxTokens) =
                    config.outputTokensParam match
                        case Config.OutputTokensParam.MaxCompletionTokens => (asked, Absent)
                        case Config.OutputTokensParam.MaxTokens           => (Absent, asked)
                Request(
                    config.modelName,
                    temperature,
                    maxCompletionTokens,
                    config.seed,
                    entries,
                    toolDefs,
                    // Forced unless BOTH hold: reasoning is active AND this entry declares its wire refuses
                    // the pair. Activation alone would give up compulsion on the wires that honor it (most of
                    // them); the wire alone would force a request the refusing wires reject. Neither implies
                    // the other, so both are asked.
                    tool_choice =
                        if activated && config.forcedToolChoice == Config.ForcedToolChoice.RefusedWhileReasoning
                        then "auto"
                        else "required",
                    thinking = thinking,
                    reasoning_effort = reasoningEffort,
                    max_tokens = maxTokens
                )
            end apply
        end Request

    end internal
end OpenAICompletion
