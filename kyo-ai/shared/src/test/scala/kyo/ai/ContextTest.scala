package kyo.ai

import kyo.*
import kyo.ai.Context.*

class ContextTest extends kyo.test.Test[Any]:

    "systemMessage skips blank content" in {
        val ctx = Context.empty.systemMessage("   ")
        assert(ctx.messages.isEmpty)
    }

    "userMessage skips blank content and absent image" in {
        val ctx1 = Context.empty.userMessage("", Absent)
        assert(ctx1.messages.isEmpty)
        val ctx2 = Context.empty.userMessage("hi")
        assert(ctx2.messages.size == 1)
    }

    "assistantMessage skips blank+empty-calls, keeps with calls" in {
        val ctx1 = Context.empty.assistantMessage("", Chunk.empty)
        assert(ctx1.messages.isEmpty)
        val ctx2 = Context.empty.assistantMessage("", Chunk(Call(CallId("c"), "f", "{}")))
        assert(ctx2.messages.size == 1)
    }

    "Role.name carries exact lowercase wire-strings" in {
        assert(Role.System.name == "system")
        assert(Role.User.name == "user")
        assert(Role.Assistant.name == "assistant")
        assert(Role.Tool.name == "tool")
    }

    "merge with a common prefix does not duplicate" in {
        val a      = Context.empty.systemMessage("s").userMessage("u1")
        val b      = Context.empty.systemMessage("s").userMessage("u1").userMessage("u2")
        val merged = a.merge(b)
        assert(merged.messages.size == 3)
    }

    "merge of disjoint contexts appends fully" in {
        val a      = Context.empty.userMessage("x")
        val b      = Context.empty.userMessage("y")
        val merged = a.merge(b)
        assert(merged.messages.size == 2)
    }

    "Context/Role/CallId/Message derive CanEqual" in {
        val ctx1 = Context.empty.systemMessage("hello")
        val ctx2 = Context.empty.systemMessage("hello")
        assert(ctx1 == ctx2)
        val id1 = CallId("abc")
        val id2 = CallId("abc")
        assert(id1 == id2)
        val msg1: Message = SystemMessage("test")
        val msg2: Message = SystemMessage("test")
        assert(msg1 == msg2)
    }

    "add appends unconditionally, even blank content the skip-builders would drop" in {
        val ctx = Context.empty.add(SystemMessage(""))
        assert(ctx.messages == Chunk(SystemMessage("")))
    }

    "toolMessage appends a tool-result message" in {
        val ctx = Context.empty.toolMessage(CallId("c1"), "result")
        assert(ctx.messages == Chunk(ToolMessage(CallId("c1"), "result")))
    }

    "Context Schema round-trips every message type, including an image and tool calls" in {
        // The serializable slice behind ai.snapshot/AI.recover: a Schema regression here silently corrupts
        // cross-run persistence, so exercise one of every message variant including a vision image and a call.
        val ctx = Context.empty
            .add(SystemMessage("sys"))
            .add(UserMessage("look", Present(Image.fromBase64("SGVsbG8="))))
            .add(AssistantMessage("thinking", Chunk(Call(CallId("c1"), "fn", """{"x":1}"""))))
            .add(ToolMessage(CallId("c1"), "tool-out"))
        Json.decode[Context](Json.encode(ctx)) match
            case Result.Success(decoded) => assert(decoded == ctx, s"round-trip mismatch: $decoded")
            case other                   => assert(false, s"Context decode failed: $other")
    }

    "merge appends the fork's suffix in order and self-merge does not duplicate" in {
        // merge appends the fork's non-prefix suffix in append order.
        // pCtx has [a, b]; fCtx (derived from pCtx) appends c. merge(pCtx, fCtx) = [a, b, c].
        val pCtx   = Context.empty.add(UserMessage("a", Absent)).add(UserMessage("b", Absent))
        val fCtx   = pCtx.add(UserMessage("c", Absent))
        val merged = pCtx.merge(fCtx)
        assert(
            merged.messages.map(_.content) == Chunk("a", "b", "c"),
            s"merge should append in order, got: ${merged.messages.map(_.content)}"
        )
        // Verify prefix-awareness: re-merging pCtx with itself = pCtx (no duplication).
        val selfMerged = pCtx.merge(pCtx)
        assert(selfMerged.messages == pCtx.messages, s"merge of identical contexts should not duplicate, got: ${selfMerged.messages}")
    }

end ContextTest
