package kyo.ai.completion

import kyo.*
import kyo.ai.Config
import kyo.ai.Context

class CodexCompletionTest extends kyo.test.Test[Any]:

    "the app-server command disables exactly the ten features and runs read-only" in {
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
                "hooks"
            ),
            s"command tooling must stay out of the provider session: ${CodexCompletion.disabledFeatures}"
        )
    }

    "a statusless close carries the app-server's stderr tail as the failure's only evidence" in {
        // A transport close reports no structured status, so the captured stderr is the sole explanation of
        // why the process died; discarding it makes every such death unattributable.
        val withTail = CodexCompletion.closedDetail("  Error: refresh token expired\n  ")
        assert(
            withTail == "Codex app-server closed before completing the turn\n" +
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

    "drainForUsage picks up the tokenUsage notification that lands after the interrupt" in {
        // Regression for the capture-path usage loss: the app-server emits `thread/tokenUsage/updated`
        // AFTER `turn/interrupt`, so a gen that ends by result capture reports no tokens at all unless the
        // trailing events are drained. Verified live before the fix (zero-token turns), which both blanks the
        // spend report and drops the compaction anchor back to the offline estimate.
        val noise = CodexWire.RpcEvent("rawResponseItem/completed", Structure.Value.Record(Chunk.empty))
        val usageEvent = CodexWire.RpcEvent(
            "thread/tokenUsage/updated",
            Structure.encode(CodexWire.TokenUsageNotification(
                "t1",
                "u1",
                CodexWire.ThreadTokenUsage(total =
                    Present(CodexWire.TokenCounts(
                        inputTokens = Present(1200L),
                        cachedInputTokens = Present(900L),
                        outputTokens = Present(340L)
                    ))
                )
            ))
        )
        for
            events <- Channel.init[CodexWire.RpcEvent](16)
            _      <- events.put(noise)
            _      <- events.put(usageEvent)
            found  <- CodexCompletion.drainForUsage(events, "t1", "u1", Absent)
        yield assert(
            found.exists(c => c.inputTokens == Present(1200L) && c.outputTokens == Present(340L)),
            s"the drain must recover the post-interrupt usage, got: $found"
        )
        end for
    }

    "drainForUsage keeps the pre-interrupt usage when the tail carries none" in {
        // The drain can only ADD information: a turn that already saw its usage before the kill, or one whose
        // notification never arrives, must come back with exactly what it had rather than losing it.
        val already = CodexWire.TokenCounts(inputTokens = Present(77L), outputTokens = Present(7L))
        for
            events <- Channel.init[CodexWire.RpcEvent](16)
            turnDone = CodexWire.RpcEvent(
                "turn/completed",
                Structure.Value.Record(Chunk(
                    "threadId" -> Structure.Value.Str("t1"),
                    "turn"     -> Structure.Value.Record(Chunk("id" -> Structure.Value.Str("u1")))
                ))
            )
            _     <- events.put(turnDone)
            found <- CodexCompletion.drainForUsage(events, "t1", "u1", Present(already))
        yield assert(
            found.exists(c => c.inputTokens == Present(77L) && c.outputTokens == Present(7L)),
            s"a usage-free tail must preserve what the turn already reported: $found"
        )
        end for
    }

    "every event method the turn loop reads is actually subscribed" in {
        // The routes decide which notifications reach the event channel; the loop's branches decide what to do
        // with them. A branch for a method that no route subscribes is dead code, and the failure is silent:
        // token usage simply never arrives and every turn reports none, which also blanks the usage-anchored
        // occupancy the compactor depends on. This pins the pairing rather than trusting the two lists to
        // stay in step.
        Channel.init[CodexWire.RpcEvent](1).map { events =>
            val subscribed = CodexCompletion.eventRoutes(events).map(_.name).toSet
            val required = Set(
                "item/started",
                "item/completed",
                "item/agentMessage/delta",
                "turn/completed",
                "thread/tokenUsage/updated",
                "thread/status/changed",
                "error"
            )
            assert(
                required.subsetOf(subscribed),
                s"unsubscribed methods the turn loop reads: ${required.diff(subscribed)}"
            )
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

end CodexCompletionTest
