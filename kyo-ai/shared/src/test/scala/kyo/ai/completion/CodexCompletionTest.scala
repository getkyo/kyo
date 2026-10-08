package kyo.ai.completion

import kyo.*
import kyo.ai.Config
import kyo.ai.Context

class CodexCompletionTest extends kyo.test.Test[Any]:

    "the app-server command disables exactly the fourteen features and runs read-only" in {
        assert(
            CodexCompletion.disabledFeatures == Chunk(
                "plugins",
                "apps",
                "shell_tool",
                "browser_use",
                "computer_use",
                "unified_exec",
                "workspace_dependencies",
                "tool_suggest",
                "multi_agent",
                "hooks",
                "sleep_tool",
                "goals",
                "view_image",
                "image_generation"
            ),
            s"command tooling must stay out of the provider session: ${CodexCompletion.disabledFeatures}"
        )
    }

    "a statusless close carries the app-server's stderr tail as the failure's only evidence" in {
        // A transport close reports no structured status, so the captured stderr is the sole explanation of
        // why the process died; discarding it makes every such death unattributable.
        val withTail = CodexCompletion.closedDetail("  Error: refresh token expired\n  ")
        assert(
            withTail ==
                "Codex app-server closed before completing the turn\n" +
                "Codex app-server stderr tail:\nError: refresh token expired",
            s"the stderr tail must reach the failure detail: $withTail"
        )
        val noTail = CodexCompletion.closedDetail("   \n  ")
        assert(
            noTail == "Codex app-server closed before completing the turn",
            s"a blank tail must not append an empty evidence section: $noTail"
        )
    }

    "both statusless-close arms read the LIVE stderr tail, not a snapshot taken before the death" in {
        // The tail is filled by captureStderr WHILE the session runs, so the arms must report through the
        // ref rather than a detail string fixed earlier. This pins the ref-to-typed-leaf path for both the
        // streaming and the completion arm; that the call sites pass the session's own ref is one line each
        // at the failure arms, and the composite is exercised by the next real statusless death.
        for
            tail      <- AtomicRef.init("")
            _         <- tail.set("Error: app-server exited")
            streamed  <- Abort.run[AIStreamException](CodexCompletion.closedStreamFailure(tail))
            completed <- Abort.run[AIGenException](CodexCompletion.closedCommandFailure(tail))
        yield
            assert(
                streamed.failure.exists(_.getMessage.contains("Error: app-server exited")),
                s"the streaming arm must carry the live tail: $streamed"
            )
            assert(
                completed.failure.exists(_.getMessage.contains("Error: app-server exited")),
                s"the completion arm must carry the live tail: $completed"
            )
        end for
    }

    "the one-round bound arms on a follow-up item after an answered call and fail-safes on a malformed item/started" in {
        val reasoning = CodexWire.RpcEvent(
            "item/started",
            Structure.encode(CodexWire.ItemNotification("t1", "u1", CodexWire.ThreadItem("reasoning")))
        )
        val malformed = CodexWire.RpcEvent("item/started", Structure.Value.Str("junk"))
        for
            armed         <- CodexCompletion.initBridge
            _             <- armed.answered.set(true)
            _             <- CodexCompletion.trackFollowUp(armed, reasoning, "t1", "u1")
            armedFollowUp <- armed.followUpStarted.get

            failSafe         <- CodexCompletion.initBridge
            _                <- failSafe.answered.set(true)
            _                <- CodexCompletion.trackFollowUp(failSafe, malformed, "t1", "u1")
            failSafeFollowUp <- failSafe.followUpStarted.get

            unanswered         <- CodexCompletion.initBridge
            _                  <- CodexCompletion.trackFollowUp(unanswered, reasoning, "t1", "u1")
            unansweredFollowUp <- unanswered.followUpStarted.get
        yield
            assert(armedFollowUp, "a reasoning item after an answered call arms the bound")
            assert(failSafeFollowUp, "a decode miss must arm the bound, never silently disarm it")
            assert(!unansweredFollowUp, "before any answered call there is no round to bound")
        end for
    }

    "a built-in tool the model starts ends the turn instead of leaving it waiting on that tool" in {
        // Recorded shape from `codex app-server` 0.156.1: after an answered round the model reasoned, then started the CLI's own
        // sleep tool for 12 hours. A built-in never reaches kyo as item/tool/call, so only the item it starts can end the turn.
        def item(itemType: String)(using Frame) =
            CodexWire.RpcEvent("item/started", Structure.encode(CodexWire.ItemNotification("t1", "u1", CodexWire.ThreadItem(itemType))))
        val sleep = recorded(
            "item/started",
            """{"item":{"type":"sleep","id":"call_31b5","durationMs":43200000},"threadId":"t1","turnId":"u1","startedAtMs":1791413368}"""
        )
        Scope.run {
            for
                transports <- JsonRpcTransport.inMemory
                (ours, peers) = transports
                interrupted <- AtomicInt.init
                events      <- Channel.init[CodexWire.RpcEvent](8)
                handler     <- JsonRpcHandler.init(ours)
                _           <- JsonRpcHandler.init(
                    peers,
                    JsonRpcRoute.request[CodexWire.TurnInterruptParams, Structure.Value]("turn/interrupt") { (_, _) =>
                        interrupted.incrementAndGet.andThen(events.put(interruptedTurn)).andThen(Structure.Value.Record(Chunk.empty))
                    }
                )
                bridge <- CodexCompletion.initBridge
                _      <- bridge.answered.set(true)
                _      <- events.put(item("dynamicToolCall"))
                _      <- events.put(item("reasoning"))
                _      <- events.put(sleep)
                stderr <- AtomicRef.init("")
                // Bounded, so the turn left waiting on the sleep reports as a timeout instead of hanging the suite.
                turn <- Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[AIGenException | Closed](
                    CodexCompletion.collectTurn(handler, events, "t1", "u1", stderr, bridge)
                )))
                count <- interrupted.get
            yield
                assert(turn.isSuccess, s"the turn must end when the model starts a built-in tool, got: $turn")
                assert(count == 1, s"the turn must be interrupted exactly once, got $count")
            end for
        }
    }

    "a turn interrupted on a captured result reports the usage the app-server sends after the interrupt" in {
        // Recorded from `codex app-server` 0.156.1: the request that called result_tool reports its usage only after kyo has
        // answered the call and interrupted, and the interrupted turn/completed follows it.
        val usage = recorded(
            "thread/tokenUsage/updated",
            """{"threadId":"t1","turnId":"u1","tokenUsage":{"total":{"totalTokens":6603,"inputTokens":6583,"cachedInputTokens":0,"outputTokens":20,"reasoningOutputTokens":0}}}"""
        )
        val toolCallDone =
            CodexWire.RpcEvent(
                "item/completed",
                Structure.encode(CodexWire.ItemNotification("t1", "u1", CodexWire.ThreadItem("dynamicToolCall")))
            )
        Scope.run {
            for
                transports <- JsonRpcTransport.inMemory
                (ours, peers) = transports
                events  <- Channel.init[CodexWire.RpcEvent](8)
                handler <- JsonRpcHandler.init(ours)
                _       <- JsonRpcHandler.init(
                    peers,
                    JsonRpcRoute.request[CodexWire.TurnInterruptParams, Structure.Value]("turn/interrupt") { (_, _) =>
                        events.put(usage).andThen(events.put(interruptedTurn)).andThen(Structure.Value.Record(Chunk.empty))
                    }
                )
                bridge <- CodexCompletion.initBridge
                _      <- bridge.resultCapture.set(Present(("call_1", """{"resultValue":"ok"}""")))
                _      <- events.put(toolCallDone)
                stderr <- AtomicRef.init("")
                turn   <- Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[AIGenException | Closed](
                    CodexCompletion.collectTurn(handler, events, "t1", "u1", stderr, bridge)
                )))
            yield
                val stats = turn.getOrThrow.getOrThrow._2
                assert(stats.inputTokens == 6583L && stats.outputTokens == 20L, s"the interrupted turn must keep its usage: $stats")
                assert(stats.turns == 1, s"one provider request ran: $stats")
            end for
        }
    }

    "a turn that completes on its own right after a captured result ends without an interrupt" in {
        val completedTurn = recorded("turn/completed", """{"threadId":"t1","turn":{"id":"u1","status":"completed","error":null}}""")
        Scope.run {
            for
                transports <- JsonRpcTransport.inMemory
                (ours, peers) = transports
                interrupted <- AtomicInt.init
                events      <- Channel.init[CodexWire.RpcEvent](8)
                handler     <- JsonRpcHandler.init(ours)
                _           <- JsonRpcHandler.init(
                    peers,
                    JsonRpcRoute.request[CodexWire.TurnInterruptParams, Structure.Value]("turn/interrupt") { (_, _) =>
                        interrupted.incrementAndGet.andThen(Structure.Value.Record(Chunk.empty))
                    }
                )
                bridge <- CodexCompletion.initBridge
                _      <- bridge.resultCapture.set(Present(("call_1", """{"resultValue":"ok"}""")))
                _      <- events.put(completedTurn)
                stderr <- AtomicRef.init("")
                turn   <- Abort.run[Timeout](Async.timeout(5.seconds)(Abort.run[AIGenException | Closed](
                    CodexCompletion.collectTurn(handler, events, "t1", "u1", stderr, bridge)
                )))
                count <- interrupted.get
            yield
                assert(turn.isSuccess && turn.getOrThrow.isSuccess, s"a completed turn has nothing left to wait for: $turn")
                assert(count == 0, s"a completed turn must not be interrupted, got $count")
            end for
        }
    }

    "threadStartParams runs the session read-only with approvals off" in {
        val params = CodexWire.threadStartParams(
            Config.Codex.default,
            Context.empty,
            Path("/tmp/kyo-ai-codex-test"),
            Chunk.empty
        )
        assert(params.sandbox == "read-only", s"the app-server session must run read-only: ${params.sandbox}")
        assert(params.approvalPolicy == "never", s"the session must never prompt for approvals: ${params.approvalPolicy}")
    }

    // Recorded from `codex app-server` 0.156.1 on a spent usage allowance, in arrival order: the thread's status turns systemError
    // first, and the reason arrives after it, in an `error` notification and again in the failed `turn/completed`.
    private val usageLimitMessage =
        "You’ve hit your usage limit. Visit https://chatgpt.com/codex/settings/usage to purchase more credits or try again at Oct 3rd, 2026 4:43 PM."
    private val recordedSystemError =
        """{"threadId":"thread-1","status":{"type":"systemError"}}"""
    private val recordedUsageLimitError =
        s"""{"error":{"message":"$usageLimitMessage","codexErrorInfo":"usageLimitExceeded","additionalDetails":null,"misalignment":null},"willRetry":false,"threadId":"thread-1","turnId":"turn-1"}"""

    private def recorded(method: String, json: String)(using Frame): CodexWire.RpcEvent =
        CodexWire.RpcEvent(method, Json.decode[Structure.Value](json).getOrThrow)

    // What `codex app-server` 0.156.1 sends once a turn/interrupt lands.
    private def interruptedTurn(using Frame): CodexWire.RpcEvent =
        recorded("turn/completed", """{"threadId":"t1","turn":{"id":"u1","status":"interrupted","error":null}}""")

    "a systemError status is not the turn's failure: the reason arrives after it" in {
        AtomicRef.init("").map { stderrTail =>
            Abort.run[AIGenException](
                CodexCompletion.eventText(recorded("thread/status/changed", recordedSystemError), "thread-1", "turn-1", stderrTail)
            ).map { outcome =>
                assert(
                    outcome.isSuccess && outcome.getOrThrow.isEmpty,
                    s"a status change must neither fail the turn nor emit text: $outcome"
                )
            }
        }
    }

    "a spent usage allowance fails as a rate limit carrying when to retry, not as a harness malfunction" in {
        AtomicRef.init("").map { stderrTail =>
            Abort.run[AIGenException](
                CodexCompletion.eventText(recorded("error", recordedUsageLimitError), "thread-1", "turn-1", stderrTail)
            ).map {
                case Result.Failure(limit: AIRateLimitException) =>
                    assert(limit.provider == "Codex")
                    assert(limit.detail == usageLimitMessage)
                    assert(limit.retryAfter.isDefined, "the CLI names when to retry, so the failure carries it")
                case other =>
                    fail(s"expected AIRateLimitException, got $other")
            }
        }
    }

    "a thread/tokenUsage/updated notification reaches the event channel" in {
        // The turn's token counts ride this one notification, and the consumer that reads them
        // (`collectTurn`) sits behind the event channel. An unrouted method never reaches the channel,
        // because the handler's default unknown-notification policy is Drop, so the consumer is dead
        // code and every Codex turn reports zero tokens while looking like it simply had none.
        val counts =
            CodexWire.TokenCounts(
                inputTokens = Present(1200L),
                cachedInputTokens = Present(400L),
                outputTokens = Present(85L),
                reasoningOutputTokens = Present(64L)
            )
        val notification =
            CodexWire.TokenUsageNotification("thread-1", "turn-1", CodexWire.ThreadTokenUsage(total = Present(counts)))
        Scope.run {
            for
                transports <- JsonRpcTransport.inMemory
                (ours, peers) = transports
                events <- Channel.init[CodexWire.RpcEvent](8)
                _      <- JsonRpcHandler.init(ours, CodexCompletion.eventRoutes(events)*)
                peer   <- JsonRpcHandler.init(peers)
                _      <- peer.notify("thread/tokenUsage/updated", Structure.encode(notification))
                // Bounded, so a dropped notification reports as a timeout instead of hanging the suite.
                event <- Abort.run[Timeout](Async.timeout(5.seconds)(events.take))
            yield
                assert(event.isSuccess, s"the notification must reach the event channel, got: $event")
                val received = event.getOrThrow
                assert(received.method == "thread/tokenUsage/updated", s"method mismatch: ${received.method}")
                val decoded = Structure.decode[CodexWire.TokenUsageNotification](received.params).getOrThrow
                val total   = decoded.tokenUsage.total.getOrElse(CodexWire.TokenCounts())
                assert(total.inputTokens.getOrElse(0L) == 1200L, s"input tokens must survive the hop: $total")
                assert(total.cachedInputTokens.getOrElse(0L) == 400L, s"cached input tokens must survive the hop: $total")
                assert(total.outputTokens.getOrElse(0L) == 85L, s"output tokens must survive the hop: $total")
                assert(total.reasoningOutputTokens.getOrElse(0L) == 64L, s"reasoning tokens must survive the hop: $total")
                // The mapping the turn then applies, so the route and the arithmetic are pinned together.
                val stats = CodexWire.usageStats(counts)
                assert(stats.inputTokens == 1200L && stats.outputTokens == 85L, s"usage mapping: $stats")
                assert(stats.totalTokens == 1285L, s"totalTokens: ${stats.totalTokens}")
            end for
        }
    }

end CodexCompletionTest
