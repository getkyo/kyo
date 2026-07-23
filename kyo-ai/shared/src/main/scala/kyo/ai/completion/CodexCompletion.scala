package kyo.ai.completion

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*
import kyo.ai.completion.CodexWire.*

/** Codex app-server completion adapter.
  *
  * Unlike the other backends this one sets no output ceiling: the app-server protocol exposes no such
  * parameter, so a reply cannot stop at a ceiling this module chose and the ceiling failure the other three
  * raise has no counterpart here, by absence of the knob. Catalog entries still declare the model's own
  * maximum, which the shared sizing and validation use.
  *
  * Codex is driven through the app-server protocol so the CLI account transport is used while Kyo supplies
  * model-visible history with native Responses API items. Kyo tools, the result tool included, are REAL
  * registered tools (`thread/start.dynamicTools`), so the model receives the same tool contract the OpenAI
  * backend sends; the app-server calls kyo back (`item/tool/call`) and each user tool executes in-process,
  * threading `LLM.State` as the Claude Code MCP bridge does. The result call ends the turn: its handler
  * records the first call's arguments verbatim, answers, and the runner interrupts the turn
  * (`turn/interrupt`), the analog of the Claude Code kill-on-call. The captured arguments ride a result_tool
  * call for the eval loop to decode; this adapter never parses a tool payload. The adapter starts an
  * ephemeral thread in an isolated temp cwd, injects prior non-leading messages with `thread/inject_items`
  * (see [[CodexWire.historyItems]]), starts a turn with `turn/start` (see [[CodexWire.turnInput]]), then
  * consumes turn events until the capture or `turn/completed`. One user-tool round runs per turn: a call
  * arriving after the round's follow-up began is answered as deferred and the turn interrupted, so the eval
  * loop owns iteration and its forced turn stays reachable (the app-server has no inner-loop cap of its own).
  */
private[completion] object CodexCompletion extends HarnessCompletion("Codex"):

    private[completion] val disabledFeatures =
        Chunk(
            "plugins",
            "apps",
            "shell_tool",
            "browser_use",
            "computer_use",
            "unified_exec",
            "workspace_dependencies",
            "tool_suggest",
            "multi_agent",
            "hooks"
        )

    /** The per-turn tool-execution state the `item/tool/call` route and the event loop share: the
      * executed user-tool calls in order, the set-once captured result arguments (call id + raw
      * JSON), whether any user tool was answered, and whether the round's follow-up began (a
      * reasoning or assistant-message item started after an answered call), which is what bounds the
      * turn to one user-tool round.
      */
    private[completion] case class ToolBridge(
        executed: AtomicRef[Chunk[ExecutedCall]],
        resultCapture: AtomicRef[Maybe[(String, String)]],
        deferred: AtomicRef[Boolean],
        answered: AtomicRef[Boolean],
        followUpStarted: AtomicRef[Boolean]
    )

    private[completion] def initBridge(using Frame): ToolBridge < Sync =
        for
            executed      <- AtomicRef.init(Chunk.empty[ExecutedCall])
            resultCapture <- AtomicRef.init(Maybe.empty[(String, String)])
            deferred      <- AtomicRef.init(false)
            answered      <- AtomicRef.init(false)
            followUp      <- AtomicRef.init(false)
        yield ToolBridge(executed, resultCapture, deferred, answered, followUp)

    override def streamFragments(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]],
        // The RPC wire carries no token counts, so the sink is never written; usage stays Absent by design.
        usageSink: AtomicRef[Maybe[Completion.Usage]]
    )(using Frame): Stream[String, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException]) =
        Kyo.lift(Stream[String, Async & Scope & Abort[AIStreamException]] {
            AtomicRef.init("").map { stderrTail =>
                Abort.run[
                    CommandException | FileFsException | FileReadException | FileWriteException | JsonRpcError | AIGenException |
                        Closed
                ] {
                    for
                        bridge <- initBridge
                        meter  <- Meter.initMutex
                        _ <- withSession(Seq(resultOnlyRoute(bridge, meter)), stderrTail) { (workDir, handler, events, stderrTail) =>
                            for
                                (threadId, turnId) <- startTurn(
                                    handler,
                                    config,
                                    context,
                                    workDir,
                                    dynamicToolSpecs(resultTool, resultSchema)
                                )
                                _ <- Async.timeoutWithError[AIGenException | Closed, Maybe[String], Any](
                                    config.timeout,
                                    Result.Failure(AICompletionTimeoutException("Codex", config.timeout))
                                )(collectTurn(handler, events, threadId, turnId, stderrTail, bridge))
                                captured <- bridge.resultCapture.get
                                _ <- captured match
                                    case Present((_, arguments)) => Emit.value(Chunk(arguments))
                                    case Absent                  => Abort.fail(AIStreamIncompleteException(""))
                            yield ()
                        }
                    yield ()
                }.map {
                    // A classified provider leaf is already an AIStreamException: re-raise it type-preserving
                    // (the CC path's split); only a genuinely-unclassified failure wraps as a harness malfunction.
                    case Result.Success(_)                     => Kyo.unit
                    case Result.Failure(ex: AIStreamException) => Abort.fail(ex)
                    case Result.Failure(ex: AIGenException)    => Abort.fail(streamFailure(Absent, ex.getMessage))
                    case Result.Failure(_: Closed) =>
                        closedStreamFailure(stderrTail)
                    case Result.Failure(ex: JsonRpcError) => Abort.fail(streamFailure(Absent, s"${ex.code}: ${ex.message}"))
                    case Result.Failure(ex)               => Abort.fail(streamFailure(Absent, ex.getMessage))
                    case Result.Panic(ex)                 => Abort.panic(ex)
                }
            }
        })
    end streamFragments

    protected def run(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: JsonSchema
    )(using
        Frame
    ): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
        LLM.state.map { state =>
            Scope.run {
                for
                    stateRef   <- AtomicRef.init(state)
                    bridge     <- initBridge
                    meter      <- Meter.initMutex
                    stderrTail <- AtomicRef.init("")
                    result <- Abort.run[
                        CommandException | FileFsException | FileReadException | FileWriteException | JsonRpcError | AIGenException |
                            Closed
                    ] {
                        withSession(Seq(toolCallRoute(tools, stateRef, meter, bridge)), stderrTail) {
                            (workDir, handler, events, stderrTail) =>
                                for
                                    (threadId, turnId) <- startTurn(
                                        handler,
                                        config,
                                        context,
                                        workDir,
                                        dynamicToolSpecs(tools, resultSchema)
                                    )
                                    finalText <- Async.timeoutWithError[AIGenException | Closed, Maybe[String], Any](
                                        config.timeout,
                                        Result.Failure(AICompletionTimeoutException("Codex", config.timeout))
                                    )(collectTurn(handler, events, threadId, turnId, stderrTail, bridge))
                                    executed <- bridge.executed.get
                                    captured <- bridge.resultCapture.get
                                yield resultMessages(executed, captured, finalText)
                        }
                    }
                    next <- stateRef.get
                    _    <- LLM.setState(next)
                    out <- result match
                        case Result.Success(messages)           => Kyo.lift(messages)
                        case Result.Failure(ex: AIGenException) => Abort.fail(ex)
                        case Result.Failure(_: Closed)          =>
                            // A mid-turn transport close is a harness malfunction (no structured
                            // status), the same leaf the Claude Code path raises for a hard crash.
                            closedCommandFailure(stderrTail)
                        case Result.Failure(ex: JsonRpcError) => Abort.fail(commandFailure(Absent, s"${ex.code}: ${ex.message}"))
                        case Result.Failure(ex)               => Abort.fail(commandFailure(Absent, ex.getMessage))
                        case Result.Panic(ex)                 => Abort.panic(ex)
                yield out
            }
        }
    end run

    private def withSession[A, S](toolRoutes: Seq[JsonRpcRoute[?, ?, ?]], stderrTail: AtomicRef[String])(
        f: (Path, JsonRpcHandler, Channel[RpcEvent], AtomicRef[String]) => A < S
    )(using
        Frame
    ): A < (S & Async & Scope & Abort[
        CommandException | FileFsException | FileReadException | FileWriteException | JsonRpcError | Closed
    ]) =
        for
            rawWorkDir <- Path.tempDir("kyo-ai-codex-cwd")
            workDir    <- Scope.acquireRelease(rawWorkDir)(path => Abort.run[FileFsException](path.removeAll).unit)
            codexHome  <- isolatedCodexHome
            proc       <- appServerCommand(workDir, codexHome).spawn
            _          <- Fiber.init(Scope.run(captureStderr(proc, stderrTail)))
            events     <- Channel.init[RpcEvent](1024)
            transport  <- JsonRpcTransport.fromWire(processWire(proc), JsonRpcFramer.lineDelimited)
            handler    <- JsonRpcHandler.init(transport, (eventRoutes(events) ++ toolRoutes)*)
            _ <- requestAs[Structure.Value, InitializeParams](
                handler,
                "initialize",
                InitializeParams(ClientInfo("kyo-ai", "0"), ClientCapabilities(experimentalApi = true))
            )
            _      <- handler.notify("initialized", Structure.Value.Record(Chunk.empty))
            result <- f(workDir, handler, events, stderrTail)
        yield result
    end withSession

    private def captureStderr(proc: Process, tail: AtomicRef[String])(using Frame): Unit < (Sync & Scope) =
        proc.stderr.foreachChunk { bytes =>
            val text = new String(bytes.toArray, StandardCharsets.UTF_8)
            tail.updateAndGet(previous => truncateStderr(previous + text)).unit
        }
    end captureStderr

    private def appServerCommand(workDir: Path, codexHome: Path): Command =
        Command((Chunk("codex", "app-server", "--stdio", "--strict-config") ++ disabledFeatures.flatMap(f => Chunk("--disable", f)))*)
            .cwd(workDir)
            .envAppend(Map("CODEX_HOME" -> codexHome.toString))
            .pipeStdin
    end appServerCommand

    private def isolatedCodexHome(using
        Frame
    ): Path < (Sync & Scope & Abort[FileFsException | FileReadException | FileWriteException]) =
        for
            rawIsolated <- Path.tempDir("kyo-ai-codex-home")
            isolated    <- Scope.acquireRelease(rawIsolated)(path => Abort.run[FileFsException](path.removeAll).unit)
            real        <- realCodexHome
            auth   = real / "auth.json"
            config = real / "config.toml"
            hasAuth   <- auth.exists
            hasConfig <- config.exists
            _         <- if hasAuth then auth.copy(isolated / "auth.json") else Kyo.unit
            _         <- if hasConfig then config.copy(isolated / "config.toml") else Kyo.unit
        yield isolated
    end isolatedCodexHome

    private def realCodexHome(using Frame): Path < Sync =
        for
            env         <- System.env[String]("CODEX_HOME")
            property    <- System.property[String]("user.home")
            home        <- System.env[String]("HOME")
            userProfile <- System.env[String]("USERPROFILE")
        yield env match
            case Present(path) => Path(path)
            case Absent =>
                val path = property.orElse(home).orElse(userProfile).getOrElse("")
                if path.isEmpty then Path(".codex")
                else Path(path) / ".codex"
    end realCodexHome

    private def startTurn(
        handler: JsonRpcHandler,
        config: Config,
        context: Context,
        workDir: Path,
        dynamicTools: Chunk[DynamicTool]
    )(using Frame): (String, String) < (Async & Abort[JsonRpcError | Closed | AIGenException]) =
        for
            thread <- requestAs[ThreadStartResponse, ThreadStartParams](
                handler,
                "thread/start",
                threadStartParams(config, context, workDir, dynamicTools)
            )
            items <- historyItems(historyMessages(context))
            _ <-
                if items.isEmpty then Kyo.unit
                else
                    requestAs[Structure.Value, ThreadInjectItemsParams](
                        handler,
                        "thread/inject_items",
                        ThreadInjectItemsParams(thread.thread.id, items.toList)
                    ).unit
            turn <- requestAs[TurnStartResponse, TurnStartParams](
                handler,
                "turn/start",
                turnStartParams(config, thread.thread.id, workDir, turnInput(context))
            )
        yield (thread.thread.id, turn.turn.id)
    end startTurn

    /** The result-tool arm shared by both routes: records the FIRST call's arguments verbatim (call
      * id + raw JSON) and answers with the same acknowledgement texts the Claude Code MCP handler
      * sends, so the model-visible result contract matches. Takes the same mutex the user tools run
      * under first, so a user tool that is mid-run finishes and commits its state before the turn
      * is interrupted (the Claude Code result-handler ordering). The event loop sees the capture
      * and interrupts the turn (this backend's kill-on-call).
      */
    private def captureResult(bridge: ToolBridge, meter: Meter, params: ToolCallRequestParams)(using
        Frame
    ): ToolCallResponse < Async =
        Abort.run[Closed] {
            meter.run {
                bridge.resultCapture.getAndUpdate { prev =>
                    if prev.isEmpty then Present((params.callId, Json.encode(params.arguments))) else prev
                }.map { prev =>
                    if prev.isEmpty then ToolCallResponse(List(ToolCallContent("inputText", "Result received.")), success = true)
                    else
                        ToolCallResponse(
                            List(ToolCallContent(
                                "inputText",
                                "A result was already recorded; only the first invocation is considered."
                            )),
                            success = true
                        )
                }
            }
        }.map {
            case Result.Success(response) => response
            case _ =>
                ToolCallResponse(
                    List(ToolCallContent("inputText", toolError(Completion.resultToolName, "bridge closed"))),
                    success = false
                )
        }
    end captureResult

    /** The streaming route: only the result tool is registered, so every call is a result call. */
    private def resultOnlyRoute(bridge: ToolBridge, meter: Meter)(using
        Frame
    ): JsonRpcRoute[ToolCallRequestParams, ToolCallResponse, Nothing] =
        JsonRpcRoute.request[ToolCallRequestParams, ToolCallResponse]("item/tool/call") { (params, _) =>
            captureResult(bridge, meter, params)
        }

    /** The completion route: executes user tools in-process, threading `LLM.State` through the
      * mutex exactly as the Claude Code MCP bridge does, and bounds the turn to ONE user-tool round:
      * a user-tool call arriving after the round's follow-up began is answered as deferred and the
      * event loop interrupts, so the eval loop replays the executed round natively and iterates.
      */
    private def toolCallRoute(
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        stateRef: AtomicRef[LLM.State],
        meter: Meter,
        bridge: ToolBridge
    )(using Frame): JsonRpcRoute[ToolCallRequestParams, ToolCallResponse, Nothing] =
        JsonRpcRoute.request[ToolCallRequestParams, ToolCallResponse]("item/tool/call") { (params, _) =>
            if params.tool == Completion.resultToolName then captureResult(bridge, meter, params)
            else
                bridge.followUpStarted.get.map { followUp =>
                    if followUp then
                        bridge.deferred.set(true).andThen(
                            ToolCallResponse(
                                List(ToolCallContent(
                                    "inputText",
                                    "The turn's tool round is complete; the recorded results are replayed next turn."
                                )),
                                success = false
                            )
                        )
                    else runUserTool(tools, stateRef, meter, bridge, params)
                }
        }
    end toolCallRoute

    private def runUserTool(
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        stateRef: AtomicRef[LLM.State],
        meter: Meter,
        bridge: ToolBridge,
        params: ToolCallRequestParams
    )(using Frame): ToolCallResponse < Async =
        tools.filter(_.name == params.tool).headMaybe match
            case Absent =>
                Kyo.lift(ToolCallResponse(List(ToolCallContent("inputText", toolError(params.tool, "unknown tool"))), success = false))
            case Present(tool) =>
                Abort.run[Closed] {
                    meter.run {
                        stateRef.get.map { state =>
                            Abort.run[Throwable] {
                                val decoded = Structure.decode[Any](params.arguments)(using tool.inputSchema.asInstanceOf[Schema[Any]])
                                decoded match
                                    case Result.Success(input) =>
                                        val run = tool.run.asInstanceOf[Any => Any < LLM](input)
                                        LLM.runWith(state)(run)((next, out) => (next, Present(out)))
                                    case Result.Failure(err) =>
                                        Abort.fail(AIDecodeException(s"Codex tool call arguments are invalid: $err"))
                                    case Result.Panic(ex) => Abort.panic(ex)
                                end match
                            }.map {
                                case Result.Success((next, Present(out))) =>
                                    val text = Json.encode[Any](out)(using tool.outputSchema.asInstanceOf[Schema[Any]], summon[Frame])
                                    stateRef.set(next)
                                        .andThen(record(bridge, params, text))
                                        .andThen(ToolCallResponse(List(ToolCallContent("inputText", text)), success = true))
                                case Result.Success((_, Absent)) =>
                                    val text = toolError(params.tool, "tool produced no output")
                                    record(bridge, params, text)
                                        .andThen(ToolCallResponse(List(ToolCallContent("inputText", text)), success = false))
                                case Result.Failure(ex) =>
                                    val text = toolError(params.tool, ex.toString)
                                    record(bridge, params, text)
                                        .andThen(ToolCallResponse(List(ToolCallContent("inputText", text)), success = false))
                                case Result.Panic(ex) =>
                                    val text = toolError(params.tool, ex.toString)
                                    record(bridge, params, text)
                                        .andThen(ToolCallResponse(List(ToolCallContent("inputText", text)), success = false))
                            }
                        }
                    }
                }.map {
                    case Result.Success(response) => response
                    case _ => ToolCallResponse(List(ToolCallContent("inputText", toolError(params.tool, "bridge closed"))), success = false)
                }
    end runUserTool

    private def record(bridge: ToolBridge, params: ToolCallRequestParams, output: String)(using Frame): Unit < Sync =
        bridge.answered.set(true).andThen(
            bridge.executed.getAndUpdate(_.append(ExecutedCall(params.callId, params.tool, params.arguments, output))).unit
        )

    private def toolError(name: String, detail: String): String =
        s"Tool '$name' failed:\n$detail"

    private def requestAs[A: Schema, P: Schema](handler: JsonRpcHandler, method: String, params: P)(using
        Frame
    ): A < (Async & Abort[JsonRpcError | Closed]) =
        handler.call[P, A](method, params)
    end requestAs

    private def eventRoutes(events: Channel[RpcEvent])(using Frame): Seq[JsonRpcRoute[?, ?, ?]] =
        Seq(
            eventRoute(events, "item/started"),
            eventRoute(events, "item/completed"),
            eventRoute(events, "raw_response_item/completed"),
            eventRoute(events, "rawResponseItem/completed"),
            eventRoute(events, "item/agentMessage/delta"),
            eventRoute(events, "turn/completed"),
            eventRoute(events, "thread/status/changed"),
            eventRoute(events, "error")
        )
    end eventRoutes

    private def eventRoute(events: Channel[RpcEvent], method: String)(using
        Frame
    ): JsonRpcRoute[Structure.Value, Unit, Nothing] =
        JsonRpcRoute.notification[Structure.Value](method) { (params, _) =>
            Abort.run[Closed](events.put(RpcEvent(method, params))).unit
        }
    end eventRoute

    private def processWire(proc: Process)(using Frame): JsonRpcWireTransport =
        new JsonRpcWireTransport:
            def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
                Abort.run[Throwable] {
                    Sync.Unsafe.defer {
                        // Unsafe: JsonRpcWireTransport needs direct access to the child process stdin.
                        proc.unsafe.stdinJava.write(bytes.toArray)
                        proc.unsafe.stdinJava.flush()
                    }
                }.map {
                    case Result.Success(_)  => ()
                    case Result.Failure(ex) => Abort.fail(Closed("Codex app-server", summon[Frame], ex.getMessage))
                    case Result.Panic(ex)   => Abort.panic(ex)
                }
            end send

            def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
                proc.stdout.map(Chunk(_)).handle(Scope.run)

            def close(using Frame): Unit < Async =
                Sync.Unsafe.defer {
                    // Unsafe: closing the process stdin is the transport shutdown boundary.
                    proc.unsafe.stdinJava.close()
                }.unit
            end close
        end new
    end processWire

    /** Consumes turn events until the result capture, a deferred round-cap, or the turn's natural
      * end, returning the turn's final assistant text (a completed agentMessage, falling back to the
      * accumulated deltas). The capture and the deferral are observed on the event that follows the
      * answered tool-call request (its item/completed always arrives), at which point the turn is
      * interrupted: this backend's kill-on-call.
      */
    private def collectTurn(
        handler: JsonRpcHandler,
        events: Channel[RpcEvent],
        threadId: String,
        turnId: String,
        stderrTail: AtomicRef[String],
        bridge: ToolBridge
    )(using Frame): Maybe[String] < (Async & Abort[AIGenException | Closed]) =
        Loop((Absent: Maybe[String], "")) { (completed, delta) =>
            events.take.map { event =>
                trackFollowUp(bridge, event, threadId, turnId).andThen {
                    bridge.resultCapture.get.map { captured =>
                        bridge.deferred.get.map { deferred =>
                            if captured.isDefined || deferred then
                                interruptTurn(handler, threadId, turnId).andThen(Loop.done(finalText(completed, delta)))
                            else if isTurnCompleted(event, threadId, turnId) then
                                Loop.done(finalText(completed, delta))
                            else
                                eventText(event, threadId, turnId, stderrTail).map {
                                    case Present(OutputText.Completed(text)) => Loop.continue((Present(text), delta))
                                    case Present(OutputText.Delta(text))     => Loop.continue((completed, delta + text))
                                    case _                                   => Loop.continue((completed, delta))
                                }
                        }
                    }
                }
            }
        }
    end collectTurn

    private def finalText(completed: Maybe[String], delta: String): Maybe[String] =
        completed match
            case Present(text) => Present(text)
            case Absent        => Maybe.when(delta.nonEmpty)(delta)

    /** Marks the round's follow-up: an item starting AFTER an answered user-tool call that
      * [[CodexWire.startsFollowUp]] classifies as the model resuming (or cannot decode at all: the
      * fail-safe arms the bound rather than silently disarming it) means the round's results are
      * back with the model, so a later user-tool call would open a second round, which the route
      * defers (one round per turn, the HTTP contract).
      */
    private[completion] def trackFollowUp(bridge: ToolBridge, event: RpcEvent, threadId: String, turnId: String)(using
        Frame
    ): Unit < Sync =
        if !startsFollowUp(event, threadId, turnId) then Kyo.unit
        else
            bridge.answered.get.map {
                case true  => bridge.followUpStarted.set(true)
                case false => Kyo.unit
            }
    end trackFollowUp

    private def interruptTurn(handler: JsonRpcHandler, threadId: String, turnId: String)(using Frame): Unit < Async =
        Abort.run[JsonRpcError | Closed](
            handler.call[TurnInterruptParams, Structure.Value]("turn/interrupt", TurnInterruptParams(threadId, turnId))
        ).unit

    private def eventText(
        event: RpcEvent,
        threadId: String,
        turnId: String,
        stderrTail: AtomicRef[String]
    )(using Frame): Maybe[OutputText] < (Sync & Abort[AIGenException]) =
        event.method match
            case "item/completed" =>
                decodeEvent[ItemNotification](event).map { notification =>
                    if notification.threadId == threadId && notification.turnId == turnId && notification.item.`type` == "agentMessage" then
                        notification.item.text.map(OutputText.Completed(_))
                    else Absent
                }
            case "raw_response_item/completed" | "rawResponseItem/completed" =>
                decodeEvent[RawResponseItemCompletedNotification](event).map { notification =>
                    if notification.threadId == threadId && notification.turnId == turnId then
                        val text = notification.item.content
                            .getOrElse(Nil)
                            .collect { case ContentItem("output_text", Present(text), _) => text }
                            .mkString
                        if text.nonEmpty then Present(OutputText.Completed(text)) else Absent
                    else Absent
                }
            case "item/agentMessage/delta" =>
                decodeEvent[AgentMessageDeltaNotification](event).map { notification =>
                    if notification.threadId == threadId && notification.turnId == turnId && notification.delta.nonEmpty then
                        Present(OutputText.Delta(notification.delta))
                    else Absent
                }
            case "error" =>
                if isRetryingError(event.params) then Absent
                else failWithStderr(Absent, Json.encode(event.params), stderrTail)
            case "thread/status/changed" =>
                decodeEvent[ThreadStatusChangedNotification](event).map { notification =>
                    if notification.threadId == threadId && notification.status.`type` == "systemError" then
                        failWithStderr(Absent, Json.encode(event.params), stderrTail)
                    else Absent
                }
            case _ =>
                Absent
    end eventText

    // Both statusless-close arms report through these, so the evidence path (read the live tail, format
    // it, raise the typed leaf) is one place a test can drive with a populated ref.
    private[completion] def closedStreamFailure(stderrTail: AtomicRef[String])(using
        Frame
    ): Nothing < (Sync & Abort[AIStreamException]) =
        stderrTail.get.map(tail => Abort.fail(streamFailure(Absent, closedDetail(tail))))

    private[completion] def closedCommandFailure(stderrTail: AtomicRef[String])(using
        Frame
    ): Nothing < (Sync & Abort[AIGenException]) =
        stderrTail.get.map(tail => Abort.fail(commandFailure(Absent, closedDetail(tail))))

    // A transport close is a statusless death: the process exited or closed its pipes, so there is no
    // structured error to report and the captured stderr tail is the only evidence of why.
    private[completion] def closedDetail(stderr: String): String =
        val base = "Codex app-server closed before completing the turn"
        if stderr.trim.isEmpty then base
        else s"$base\nCodex app-server stderr tail:\n${stderr.trim}"
    end closedDetail

    private def failWithStderr(status: Maybe[Int], detail: String, stderrTail: AtomicRef[String])(using
        Frame
    ): Nothing < (Sync & Abort[AIGenException]) =
        stderrTail.get.map { stderr =>
            val suffix =
                if stderr.trim.isEmpty then ""
                else s"\nCodex app-server stderr tail:\n${stderr.trim}"
            Abort.fail(commandFailure(status, detail + suffix))
        }
    end failWithStderr

end CodexCompletion
