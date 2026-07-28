package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.ai.*
import kyo.ai.Context.*

/** The Claude Code wire codecs: the stream-json DTOs and every pure transform between kyo's
  * `Context` and the CLI's stdin, argv, and stdout. Everything here is a pure function of its
  * inputs (`Abort` carries typed decode failures); nothing touches a process, socket, or ref.
  * [[ClaudeCodeCompletion]] holds the orchestration and calls in here, the same split the HTTP
  * backends keep with their `internal` codec objects.
  */
private[completion] object ClaudeCodeWire:

    private case class InputSource(`type`: String, media_type: String, data: String) derives Schema
    // Only text and image blocks ride stdin: the CLI accepts user-role events alone, so tool_use and
    // tool_result never appear on the input side (prior turns render as transcript text instead).
    private case class InputContent(
        `type`: String,
        text: Maybe[String] = Absent,
        source: Maybe[InputSource] = Absent
    ) derives Schema
    private case class InputMessage(role: String, content: List[InputContent]) derives Schema
    private case class InputEvent(`type`: String, message: InputMessage) derives Schema

    private case class MessageContent(
        `type`: String,
        id: Maybe[String] = Absent,
        text: Maybe[String] = Absent,
        name: Maybe[String] = Absent,
        input: Maybe[Structure.Value] = Absent,
        tool_use_id: Maybe[String] = Absent,
        content: Maybe[String] = Absent,
        is_error: Maybe[Boolean] = Absent
    ) derives Schema
    // The API usage shape the CLI embeds on assistant events and the terminal result event
    // (Anthropic-style fields; cache traffic reported beside input_tokens). Every field is Maybe:
    // this decodes possibly-truncated output.
    private[completion] case class Usage(
        input_tokens: Maybe[Long] = Absent,
        output_tokens: Maybe[Long] = Absent,
        cache_read_input_tokens: Maybe[Long] = Absent,
        cache_creation_input_tokens: Maybe[Long] = Absent
    ) derives Schema
    private case class OutputMessage(
        role: String,
        content: List[MessageContent],
        id: Maybe[String] = Absent,
        usage: Maybe[Usage] = Absent
    ) derives Schema
    private case class OutputEvent(
        `type`: String,
        message: Maybe[OutputMessage] = Absent,
        result: Maybe[String] = Absent,
        // The terminal result event's structured error signal: `subtype` names the outcome kind
        // ("success", "error_max_turns", ...), `is_error` flags a failed turn, `api_error_status` carries the
        // upstream provider HTTP status. The failure path classifies from these fields (failureStatus +
        // HarnessCompletion.classify), never from the flattened stdout, so no status-code text heuristic is
        // needed. Example event: {"type":"result","subtype":"success","is_error":false,"api_error_status":null,...}.
        subtype: Maybe[String] = Absent,
        is_error: Maybe[Boolean] = Absent,
        api_error_status: Maybe[Int] = Absent,
        // Set when the CLI ends a turn for a structural reason of its own rather than an upstream
        // status, which is how stopping at the output ceiling is reported.
        error: Maybe[String] = Absent,
        // The terminal result event's aggregate usage across the invocation's internal iterations.
        usage: Maybe[Usage] = Absent
    ) derives Schema

    /** True when the flattened stdout carries the CLI's own report that a turn stopped at the output ceiling.
      * Structural: it reads the decoded event field, never the prose the CLI prints. Decoding is lenient
      * because this runs on a failed invocation, whose output may be partial.
      *
      * Covered offline; the branch that consults it needs a real process reaching a ceiling, exercised by the
      * live harness tests through a single call site (so the untested part is one call, not a second impl).
      */
    private[completion] def stoppedAtOutputLimit(out: String)(using Frame): Boolean < Abort[AIGenException] =
        readEvents(out, lenient = true).map(_.exists(_.error.contains("max_output_tokens")))

    case class ExecutedTool(name: String, arguments: Structure.Value, output: String)

    val mcpServerName = "kyo"
    val mcpToolPrefix = s"mcp__${mcpServerName}__"

    /** The CLI flags shared by the completion and streaming paths. Both ride the MCP result tool, so both
      * pass a real `--mcp-config` and an `--allowedTools` tail; they differ only in which tools the bridge
      * advertises (user tools + result tool vs the result tool alone). No caller passes `--json-schema`.
      *
      * `--max-turns 2` bounds one invocation at one tool round plus its follow-up: the model calls user tools,
      * sees their results natively in-session, and finalizes, the HTTP path's call-then-use-result flow in one
      * invocation. In-session delivery matters: a capped-out round resuming in a fresh invocation re-reads its
      * own calls as replayed transcript, and a model told it "must call tool X before answering" sometimes
      * re-derives that and calls X again (measured live), so the common single-round flow must complete without
      * a continuation. Without any cap the CLI's agentic loop runs unbounded and the forced-turn directive
      * never reaches it. A capped resultless invocation ends with `error_max_turns` (see [[endedAtTurnCap]]),
      * the runner's normal turn boundary; the eval loop replays and iterates.
      */
    private def baseCliArgs(config: Config, mcpConfig: String): Chunk[String] =
        Chunk(
            "claude",
            "-p",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
            "--verbose",
            "--no-session-persistence",
            "--max-turns",
            "2",
            "--tools",
            "",
            "--disable-slash-commands",
            "--strict-mcp-config",
            "--mcp-config",
            mcpConfig,
            "--setting-sources",
            "",
            "--permission-mode",
            "dontAsk",
            "--model",
            config.modelName
        )
    end baseCliArgs

    def commandArgs(
        config: Config,
        context: Context,
        mcpConfig: String,
        allowedTools: Chunk[String]
    )(using Frame): Chunk[String] =
        val withAllowed = baseCliArgs(config, mcpConfig).append("--allowedTools").append(allowedTools.mkString(","))
        leadingSystem(context) match
            case Some(system) if system.nonEmpty => withAllowed.append("--system-prompt").append(system)
            case _                               => withAllowed
    end commandArgs

    /** One CLI turn as exactly ONE stdin user event.
      *
      * The CLI's stream-json stdin accepts only user-role events (assistant/tool events are rejected with
      * "Expected message role 'user'"), and several user events do not reconstruct a transcript: they queue
      * as sequential live requests the model re-answers one by one, so a replayed conversation makes the model
      * answer the FIRST replayed turn again and kill-on-call captures that. Both verified against the real CLI,
      * so native multi-event replay is a hard limit and the turn is one user event instead:
      *   - completed prior turns (up to the LAST assistant or tool message) render as a replayed-transcript
      *     block wrapped in `<system-reminder>` (the idiom Claude honors; an invented marker reads as a prompt
      *     injection and is refused),
      *   - messages after the last completed turn render IN PLACE as the Anthropic wire renders them (a system
      *     message as its own `<system-reminder>`, a user message as plain text), so a context with no
      *     assistant or tool turns produces no transcript and the two wires carry byte-identical content,
      *   - the last user message stays the current request in plain text,
      *   - trailing system messages (floating reminders, the forced-turn repair directive) follow as individual
      *     `<system-reminder>` blocks, keeping their end-of-context position,
      *   - history images attach as native image blocks after the transcript block.
      * The leading system message still rides `--system-prompt` (see [[commandArgs]]); a system message inside
      * the replayed turns renders in place as a `[system instruction]` transcript line.
      */
    def turnInput(context: Context)(using Frame): String < Abort[AIGenException] =
        val rest       = if leadingSystem(context).isDefined then context.messages.drop(1) else context.messages
        val directives = HarnessCompletion.trailingSystemCount(rest)
        val body       = rest.dropRight(directives)
        // The CLI acts only on a user turn: a conversation ending on an assistant or tool message replays
        // fully as history under the synthetic continuation both command wires share, which names recorded
        // tool results explicitly when the body ends on them (see HarnessCompletion.continuationRequest).
        val (history, request) = body.lastMaybe match
            case Present(user: UserMessage) => (body.dropRight(1), user)
            case _                          => (body, UserMessage(HarnessCompletion.continuationRequest(body), Absent))
        // Only completed turns need the transcript carrier; what follows the last assistant or tool
        // message is the current request's preamble and renders in place, mirroring the Anthropic
        // wire's rendering of the same messages.
        val turns       = lastTurnIndex(history) + 1
        val turnHistory = history.take(turns)
        // The preamble holds only system and user messages by construction (lastTurnIndex), so
        // collect is total here.
        val preambleBlocks = history.drop(turns).collect {
            case SystemMessage(content) => Chunk(reminderBlock(content))
            case user: UserMessage      => requestBlocks(user)
        }.flattenChunk
        // The suffix is all SystemMessages by construction (HarnessCompletion.trailingSystemCount),
        // so collect is total here.
        val directiveBlocks = rest.takeRight(directives).collect {
            case SystemMessage(content) => reminderBlock(content)
        }
        val blocks = historyBlocks(turnHistory).concat(preambleBlocks).concat(requestBlocks(request)).concat(directiveBlocks)
        Kyo.lift(Json.encode(InputEvent("user", InputMessage(Role.User.name, blocks.toList))))
    end turnInput

    /** The index of the last assistant or tool message: the end of the completed turn history. The
      * system and user messages after it belong to the current request and render in place; -1 when
      * the context holds no completed turns at all.
      */
    private def lastTurnIndex(messages: Chunk[Message]): Int =
        @scala.annotation.tailrec
        def loop(index: Int): Int =
            if index < 0 then index
            else
                messages(index) match
                    case _: AssistantMessage | _: ToolMessage => index
                    case _                                    => loop(index - 1)
        loop(messages.size - 1)
    end lastTurnIndex

    private def reminderBlock(content: String): InputContent =
        InputContent("text", text = Present(s"<system-reminder>\n$content\n</system-reminder>"))

    private def imageBlock(image: Image): InputContent =
        InputContent("image", source = Present(InputSource("base64", "image/jpeg", image.base64)))

    /** The replayed-transcript block for the prior turns, plus their images as native blocks.
      *
      * Each message renders as one or more `[role]`-tagged lines in conversation order; tool calls
      * carry their raw argument JSON and each tool result follows its call, so no wire ids are needed.
      * The header states the replay contract: the turns are the model's own prior history, earlier
      * tools may be absent from the current toolset, and only the request that follows is to be
      * answered. Without it the model re-answers replayed turns or distrusts their values.
      */
    private def historyBlocks(history: Chunk[Message]): Chunk[InputContent] =
        if history.isEmpty then Chunk.empty
        else
            val (lines, images) = history.foldLeft((Chunk.empty[String], Chunk.empty[Image])) {
                case ((lines, images), message) =>
                    message match
                        case UserMessage(content, Present(image)) =>
                            (lines.append(s"[user] (image attached below): $content"), images.append(image))
                        case UserMessage(content, _) =>
                            (lines.append(s"[user]: $content"), images)
                        case AssistantMessage(content, calls) =>
                            val withText = if content.nonEmpty then lines.append(s"[assistant]: $content") else lines
                            val withCalls = calls.foldLeft(withText) { (acc, call) =>
                                acc.append(s"[assistant, tool call ${call.function}]: ${call.arguments}")
                            }
                            (withCalls, images)
                        case ToolMessage(_, content) =>
                            (lines.append(s"[tool result]: $content"), images)
                        case SystemMessage(content) =>
                            (lines.append(s"[system instruction]: $content"), images)
            }
            val header =
                "Conversation history for this session, replayed verbatim by the Kyo framework. These turns " +
                    "already happened: the tool calls listed ran to completion with the shown results, and the " +
                    "result_tool entries are structured results you already returned. Tools enabled in earlier " +
                    "turns may be absent from the current toolset; their recorded results stand. Do not repeat " +
                    "a tool call whose result is already recorded here; reuse the recorded result. Do not " +
                    "re-answer earlier turns; answer only the new request that follows."
            Chunk(InputContent(
                "text",
                text = Present(s"<system-reminder>\n$header\n\n${lines.mkString("\n")}\n</system-reminder>")
            )).concat(images.map(imageBlock))
    end historyBlocks

    private def requestBlocks(request: UserMessage): Chunk[InputContent] =
        val text  = if request.content.nonEmpty then Chunk(InputContent("text", text = Present(request.content))) else Chunk.empty
        val image = request.image.map(img => Chunk(imageBlock(img))).getOrElse(Chunk.empty)
        text.concat(image)
    end requestBlocks

    /** The ambient prompt: the context's leading system message, the one Anthropic carries in
      * `system`. On both the completion and the streaming paths it is the WHOLE system prompt: the
      * result contract rides the MCP result tool's description and input_schema exactly as it rides the
      * Anthropic request's tool definition, so no envelope instruction or schema restatement is
      * appended and the model-visible system content matches the Anthropic backend's.
      */
    private def leadingSystem(context: Context): Option[String] =
        context.messages.headOption.collect { case SystemMessage(content) => content }

    def readMessages(output: String, captured: Maybe[String], callIdSeed: String)(using
        Frame
    ): Chunk[Message] < Abort[AIGenException] =
        readMessages(output, Chunk.empty, captured, callIdSeed)

    /** `callIdSeed` is a per-generation unique token: the ids this wire MINTS (the captured result's
      * call and a lost-event executed tool's synthetic pair, which have no CLI wire id) carry it, so
      * a multi-generation conversation never holds two calls with the same id and a CC-built context
      * replays natively through the HTTP wires without duplicate tool_use ids.
      */
    def readMessages(
        output: String,
        executedTools: Chunk[ExecutedTool],
        captured: Maybe[String],
        callIdSeed: String
    )(using Frame): Chunk[Message] < Abort[AIGenException] =
        // When a result was captured the process was killed mid-stream, so the last stdout line can be
        // truncated: parse leniently, dropping undecodable lines. The stdout transcript is best-effort context;
        // the result itself is ONLY the captured MCP input, never selected from stdout. Without a capture the
        // transcript carries no result_tool call, so the eval loop sees no result and repairs.
        readEvents(output, lenient = captured.isDefined).map { parsed =>
            val (transcript, resultText) = transcriptMessages(parsed, executedTools, callIdSeed)
            captured match
                case Present(arguments) =>
                    // The FIRST result call's arguments, verbatim: the eval loop decodes them against the typed
                    // envelope and owns rejection and repair. The model's answer prose from the same best-effort
                    // stdout event rides this message, matching the API; if the event did not flush before the
                    // kill, resultText is Absent and the text degrades to empty.
                    transcript.append(AssistantMessage(
                        resultText.getOrElse(""),
                        Chunk(Call(CallId(s"harness-result-$callIdSeed"), Completion.resultToolName, arguments))
                    ))
                case Absent =>
                    transcript
            end match
        }
    end readMessages

    private def transcriptMessages(
        events: Chunk[OutputEvent],
        executedTools: Chunk[ExecutedTool],
        callIdSeed: String
    )(using Frame): (Chunk[Message], Maybe[String]) =
        // Returns the native transcript plus the best-effort text of the result-carrying assistant event:
        // that event's result tool_use is filtered from native mapping (its arguments ride the MCP
        // capture), so without this its prose (the model's answer) would be dropped where the API keeps
        // it. readMessages attaches the text to the synthetic result message.
        val (unmatched, messages, resultText) =
            events.foldLeft((executedTools, Chunk.empty[Message], Maybe.empty[String])) { case ((executed, out, prose), event) =>
                event.message match
                    case Present(message) if message.role == Role.Assistant.name =>
                        val text = message.content.collect { case MessageContent("text", _, Present(text), _, _, _, _, _) => text }.mkString
                        val hasResultCall = message.content.exists {
                            case MessageContent("tool_use", _, _, Present(name), _, _, _, _) =>
                                kyoToolName(name) == Completion.resultToolName
                            case _ => false
                        }
                        val (remaining, calls, toolMessages) =
                            message.content.foldLeft((executed, Chunk.empty[Call], Chunk.empty[ToolMessage])) {
                                case (
                                        (remaining, calls, tools),
                                        MessageContent("tool_use", Present(id), _, Present(name), Present(input), _, _, _)
                                    )
                                    if kyoToolName(name) != Completion.resultToolName =>
                                    // A native (MCP) tool call, mapped verbatim: name plus the raw input as the
                                    // Call arguments, with its recorded execution output as the matching tool
                                    // message. No payload decoding or reshaping. The result tool's own call is
                                    // excluded: its arguments arrive through the MCP capture instead.
                                    val toolName = kyoToolName(name)
                                    val call     = Call(CallId(id), toolName, Json.encode(input))
                                    matchExecutedTool(toolName, input, remaining) match
                                        case Present((executedTool, nextRemaining)) =>
                                            (nextRemaining, calls.append(call), tools.append(ToolMessage(call.id, executedTool.output)))
                                        case Absent =>
                                            (remaining, calls.append(call), tools)
                                    end match
                                case (state, _) =>
                                    state
                            }
                        if calls.nonEmpty then
                            // Non-result calls carry the text already; a result call in the same event still
                            // rides the MCP capture, so prose stays as-is (no double attach).
                            (remaining, out.append(AssistantMessage(text, calls)).concat(toolMessages), prose)
                        else if hasResultCall then
                            // The pure result-tool event: keep its prose for the synthetic result message.
                            (remaining, out, Present(text))
                        else
                            (remaining, out, prose)
                        end if
                    case Present(message) if message.role == Role.User.name =>
                        val toolMessages = Chunk.from(message.content.collect {
                            case MessageContent("tool_result", _, _, _, _, Present(callId), Present(content), _) =>
                                ToolMessage(CallId(callId), content)
                        })
                        (executed, out.concat(toolMessages), prose)
                    case _ =>
                        (executed, out, prose)
            }
        (messages.concat(syntheticToolMessages(unmatched, callIdSeed)), resultText)
    end transcriptMessages

    private def syntheticToolMessages(executedTools: Chunk[ExecutedTool], callIdSeed: String)(using Frame): Chunk[Message] =
        executedTools.zipWithIndex.flatMap { case (tool, index) =>
            val call = Call(CallId(s"mcp-$callIdSeed-${tool.name}-$index"), tool.name, Json.encode(tool.arguments))
            Chunk(
                AssistantMessage("", Chunk(call)),
                ToolMessage(call.id, tool.output)
            )
        }
    end syntheticToolMessages

    private def matchExecutedTool(
        name: String,
        arguments: Structure.Value,
        executedTools: Chunk[ExecutedTool]
    ): Maybe[(ExecutedTool, Chunk[ExecutedTool])] =
        Maybe.fromOption(executedTools.zipWithIndex.collectFirst {
            case (tool, index) if tool.name == name && tool.arguments == arguments =>
                tool -> executedTools.take(index).concat(executedTools.drop(index + 1))
        })
    end matchExecutedTool

    private def kyoToolName(name: String): String =
        if name.startsWith(mcpToolPrefix) then name.drop(mcpToolPrefix.length) else name

    private def readEvents(output: String, lenient: Boolean)(using Frame): Chunk[OutputEvent] < Abort[AIGenException] =
        val lines = Chunk.from(output.linesIterator.filter(_.trim.nonEmpty).toList)
        Kyo.foreach(lines) { line =>
            Json.decode[OutputEvent](line) match
                case Result.Success(event)        => Chunk(event)
                case Result.Failure(_) if lenient => Chunk.empty[OutputEvent]
                case Result.Failure(err) => Abort.fail(AIDecodeException(s"Claude Code emitted malformed stream-json: $err\n$line"))
                case Result.Panic(ex)    => Abort.panic(ex)
        }.map(_.flattenChunk)
    end readEvents

    /** True when the CLI's terminal result event reports the `--max-turns` cap (subtype
      * `error_max_turns`). The cap fires when the single allowed turn ends without a result call: a
      * resultless tool turn, this backend's normal turn boundary (the eval loop replays and iterates),
      * not a failure. The CLI still exits non-zero and flags `is_error` on that event, so the runner
      * checks this BEFORE the failure classification. Parses leniently like [[failureStatus]].
      */
    def endedAtTurnCap(output: String)(using Frame): Boolean < Abort[AIGenException] =
        readEvents(output, lenient = true).map(_.exists(_.subtype.contains("error_max_turns")))

    /** The upstream provider HTTP status the CLI attached to its terminal result event, if the turn failed at
      * the provider. Reads the typed fields, not the flattened stdout: an event with `is_error` true (or a
      * `subtype` other than `"success"`) carries the provider status on `api_error_status`, passed as
      * `Maybe[Int]` to `HarnessCompletion.classify`. Absent when no error event carries a status (a hard crash
      * or missing binary before the turn reached the provider), which `classify` treats as a broken harness.
      * Parses leniently (the kill can truncate the last line); reads the last error event's status.
      */
    def failureStatus(output: String)(using Frame): Maybe[Int] < Abort[AIGenException] =
        readEvents(output, lenient = true).map { events =>
            events.foldLeft(Maybe.empty[Int]) { (last, event) =>
                val failedSubtype = event.subtype match
                    case Present(kind) => kind != "success"
                    case Absent        => false
                val failed = event.is_error.contains(true) || failedSubtype
                if failed then event.api_error_status.orElse(last) else last
            }
        }
    end failureStatus

    /** The turn's token usage from the flattened stdout.
      *
      * The terminal `result` event's aggregate is authoritative and preferred: it sums the
      * invocation's internal iterations (verified live). On the kill-on-capture path that event never
      * arrives, so the fallback sums the assistant events' embedded usage, deduplicated by message id
      * (the CLI re-emits an event per message): the input-side numbers are accurate there, while
      * output tokens are the message-start count, a stated lower bound (verified live: 4 reported at
      * message start against 38 on the aggregate). Parses leniently, since the kill can truncate the
      * last line. Always reports `turns = 1`: the invocation is one kyo turn whether or not the CLI
      * flushed its numbers.
      */
    def turnUsage(output: String)(using Frame): AIStats < Abort[AIGenException] =
        readEvents(output, lenient = true).map { events =>
            val resultUsage = events.foldLeft(Maybe.empty[Usage]) { (last, event) =>
                if event.`type` == "result" then event.usage.orElse(last) else last
            }
            val base = resultUsage match
                case Present(usage) => usageStats(usage)
                case Absent         =>
                    // Last event per message id wins (the CLI re-emits an event per message); an event
                    // whose id was lost to truncation still counts once.
                    val (byId, anonymous) =
                        events.foldLeft((Dict.empty[String, Usage], Chunk.empty[Usage])) { case ((byId, anonymous), event) =>
                            event.message match
                                case Present(message) if message.role == Role.Assistant.name && message.usage.isDefined =>
                                    val usage = message.usage.getOrElse(Usage())
                                    message.id match
                                        case Present(id) => (byId.update(id, usage), anonymous)
                                        case Absent      => (byId, anonymous.append(usage))
                                case _ => (byId, anonymous)
                        }
                    (Chunk.from(byId.toMap.values) ++ anonymous).foldLeft(AIStats.empty)((acc, u) => acc.add(usageStats(u)))
            base.copy(turns = 1)
        }
    end turnUsage

    // The Anthropic-wire mapping: cache traffic is reported beside input_tokens, so the input total
    // sums all three; creation counts as read, not cached; reasoning is never broken out.
    private def usageStats(usage: Usage): AIStats =
        AIStats(
            inputTokens = usage.input_tokens.getOrElse(0L) +
                usage.cache_read_input_tokens.getOrElse(0L) +
                usage.cache_creation_input_tokens.getOrElse(0L),
            cachedInputTokens = usage.cache_read_input_tokens,
            outputTokens = usage.output_tokens.getOrElse(0L),
            reasoningOutputTokens = Absent,
            turns = 0
        )

end ClaudeCodeWire
