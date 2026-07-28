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
