package kyo.ai

import kyo.*
import kyo.ai.Context.*

class ContextTest extends kyo.test.Test[Any]:

    def um(s: String): UserMessage             = UserMessage(s, Absent)
    def tm(id: String, s: String): ToolMessage = ToolMessage(CallId(id), s)

    "systemMessage skips blank content" in {
        val ctx = Context.empty.systemMessage("   ")
        assert(ctx.raw.isEmpty && ctx.compacted.isEmpty)
    }

    "userMessage skips blank content and absent image" in {
        val ctx1 = Context.empty.userMessage("", Absent)
        assert(ctx1.raw.isEmpty)
        val ctx2 = Context.empty.userMessage("hi")
        assert(ctx2.raw.size == 1)
    }

    "assistantMessage skips blank+empty-calls, keeps with calls" in {
        val ctx1 = Context.empty.assistantMessage("", Chunk.empty)
        assert(ctx1.raw.isEmpty)
        val ctx2 = Context.empty.assistantMessage("", Chunk(Call(CallId("c"), "f", "{}")))
        assert(ctx2.raw.size == 1)
    }

    "Role.name carries exact lowercase wire-strings" in {
        assert(Role.System.name == "system")
        assert(Role.User.name == "user")
        assert(Role.Assistant.name == "assistant")
        assert(Role.Tool.name == "tool")
    }

    "two fields raw+compacted; no Maybe; compacted starts == raw; single-arg factory" in {
        val chunk = Chunk[Message](SystemMessage("s"), um("u"))
        val ctx   = Context(chunk)
        assert(ctx.raw == chunk, s"raw should equal the given chunk, got: ${ctx.raw}")
        assert(ctx.compacted == chunk, s"compacted should equal the given chunk, got: ${ctx.compacted}")
        assert(ctx.raw == ctx.compacted, "the single-arg factory sets raw == compacted")
        // Origin is a nested type, not a third Context field.
        val o = Context.Origin(1, 3, 7)
        assert(o.start == 1 && o.end == 3 && o.since == 7)
    }

    "add appends to both lists; builders delegate; Context carries no compaction logic" in {
        val ctx = Context.empty.add(SystemMessage("m1")).userMessage("u").toolMessage(CallId("c"), "t")
        assert(ctx.raw.size == 3, s"raw should hold three messages, got: ${ctx.raw}")
        assert(ctx.raw == ctx.compacted, "add appends to BOTH lists, never one")
        assert(ctx.raw.map(_.content) == Chunk("m1", "u", "t"))
    }

    "add appends unconditionally, even blank content the skip-builders would drop (both lists)" in {
        val ctx = Context.empty.add(SystemMessage(""))
        assert(ctx.raw == Chunk[Message](SystemMessage("")))
        assert(ctx.compacted == Chunk[Message](SystemMessage("")))
    }

    "toolMessage appends a tool-result message to both lists" in {
        val ctx = Context.empty.toolMessage(CallId("c1"), "result")
        assert(ctx.raw == Chunk[Message](tm("c1", "result")))
        assert(ctx.compacted == Chunk[Message](tm("c1", "result")))
    }

    "isEmpty tracks raw" in {
        assert(Context.empty.isEmpty)
        assert(!Context.empty.add(um("x")).isEmpty)
    }

    "merge appends fork suffix to both; receiver compacted prefix kept; uncompacted stays compacted==raw" in {
        val base = Context.empty.add(um("a")).add(um("b"))
        // A receiver whose compacted was rewritten to a shorter frozen view.
        val receiver = base.copy(compacted = Chunk[Message](SystemMessage("[frozen view]")))
        val fork     = base.add(um("c")).add(um("d"))
        val merged   = receiver.merge(fork)
        assert(
            merged.raw.map(_.content) == Chunk("a", "b", "c", "d"),
            s"merge keeps the common raw prefix and appends the fork suffix, got: ${merged.raw.map(_.content)}"
        )
        assert(
            merged.compacted.map(_.content) == Chunk("[frozen view]", "c", "d"),
            s"the receiver's frozen compacted prefix is preserved and the fork suffix appended, got: ${merged.compacted.map(_.content)}"
        )
        // An uncompacted receiver keeps compacted == raw after merge.
        val u2 = base.merge(fork)
        assert(u2.raw == u2.compacted, "an uncompacted receiver stays compacted == raw")
    }

    "merge of disjoint contexts appends the whole fork to both" in {
        val a      = Context.empty.add(um("x"))
        val b      = Context.empty.add(um("y"))
        val merged = a.merge(b)
        assert(merged.raw.map(_.content) == Chunk("x", "y"))
        assert(merged.compacted.map(_.content) == Chunk("x", "y"))
    }

    "self-merge does not duplicate" in {
        val p          = Context.empty.add(um("a")).add(um("b"))
        val selfMerged = p.merge(p)
        assert(selfMerged.raw == p.raw, s"merge of identical contexts should not duplicate, got: ${selfMerged.raw}")
    }

    "core-field comparison ignores enrichment fields" in {
        // Two messages identical on content/role but differing solely on enrichment compare as the same
        // under coreEq (merge/dedup/cache-gate path), while full-record == treats them as different.
        val emb  = Embedding(Span(1.0f, 0.0f), "m", 2)
        val bare = um("same")
        val rich = UserMessage("same", Absent, embedding = Present(emb), tokens = Present(7), origin = Present(Origin(0, 1, 0)))
        assert(Context.coreEq(bare, rich), "coreEq ignores embedding/tokens/origin")
        assert(bare != rich, "full-record == is NOT the comparison coreEq uses (enrichment differs)")
        // A genuine core difference is not equal under coreEq.
        assert(!Context.coreEq(bare, um("other")))
        // merge's prefix walk uses coreEq: an enrichment-only difference in the receiver's raw prefix
        // still counts as the common prefix (no spurious fork).
        val recv   = Context(Chunk[Message](rich))
        val fork   = Context(Chunk[Message](bare, um("next")))
        val merged = recv.merge(fork)
        assert(merged.raw.map(_.content) == Chunk("same", "next"), s"enrichment-only diff is common prefix, got: ${merged.raw}")
    }

    "embedding/tokens/origin default Absent on every ordinarily-added message" in {
        val ctx = Context.empty.add(SystemMessage("s")).userMessage("u").assistantMessage("a").toolMessage(CallId("c"), "t")
        assert(
            ctx.raw.forall(m => m.embedding.isEmpty && m.tokens.isEmpty && m.origin.isEmpty),
            "every appended message defaults to Absent enrichment"
        )
    }

    "Context Schema round-trips every message type, including image, tool calls, and enrichment fields" in {
        // The serializable slice behind ai.snapshot/AI.recover: a Schema regression here silently corrupts
        // cross-run persistence. Exercise one of every variant PLUS Present embedding/tokens/origin, and a
        // divergent compacted (a synthetic marker) so BOTH lists are checked.
        val emb    = Embedding(Span(1.0f, 2.0f), "m", 2)
        val marker = SystemMessage("[compacted region 0: 12 bytes omitted]", origin = Present(Origin(0, 2, 4)))
        val ctx = Context(Chunk[Message](
            SystemMessage("sys"),
            UserMessage("look", Present(Image.fromBase64("SGVsbG8=")), embedding = Present(emb)),
            AssistantMessage("thinking", Chunk(Call(CallId("c1"), "fn", """{"x":1}""")), tokens = Present(42)),
            ToolMessage(CallId("c1"), "tool-out")
        )).copy(compacted = Chunk[Message](marker))
        Json.decode[Context](Json.encode(ctx)) match
            case Result.Success(decoded) =>
                // Span[Float] equality is by array identity (the existing embed test compares via toArray.toSeq),
                // so compare the embedding element-wise and the rest structurally.
                assert(decoded.compacted == Chunk[Message](marker), "the compacted list (incl. origin) round-trips byte-for-byte")
                assert(decoded.raw.size == ctx.raw.size)
                assert(decoded.raw(0) == ctx.raw(0) && decoded.raw(3) == ctx.raw(3), "content-only messages round-trip structurally")
                decoded.raw(1).embedding match
                    case Present(e) =>
                        assert(
                            e.modelName == "m" && e.dim == 2 && e.vector.toArray.toSeq == Seq(1.0f, 2.0f),
                            s"embedding round-trips element-wise: $e"
                        )
                    case Absent => assert(false, "embedding should be Present after the round-trip")
                end match
                assert(decoded.raw(2).tokens == Present(42), "tokens round-trips")
            case other => assert(false, s"Context decode failed: $other")
        end match
    }

    "Context/Role/CallId/Message derive CanEqual" in {
        val ctx1 = Context.empty.systemMessage("hello")
        val ctx2 = Context.empty.systemMessage("hello")
        assert(ctx1 == ctx2)
        assert(CallId("abc") == CallId("abc"))
        val msg1: Message = SystemMessage("test")
        val msg2: Message = SystemMessage("test")
        assert(msg1 == msg2)
    }

    "every Message leaf defaults tokens Absent and no longer has summary" in {
        val sm = SystemMessage("s")
        val um = UserMessage("u", Absent)
        val am = AssistantMessage("a")
        val tm = ToolMessage(CallId("c"), "t")
        assert(
            sm.tokens == Absent && um.tokens == Absent && am.tokens == Absent && tm.tokens == Absent,
            "every leaf defaults tokens to Absent when only its required fields are given"
        )
    }

    "the sealed trait exposes def tokens: Maybe[Int]" in {
        val msg: Message       = SystemMessage("s")
        val tokens: Maybe[Int] = msg.tokens
        assert(tokens == Absent, "the trait accessor type-checks as Maybe[Int] and returns Absent by default")
    }

    "coreEq ignores the tokens field" in {
        val a = SystemMessage("x")
        val b = SystemMessage("x", tokens = Present(7))
        assert(Context.coreEq(a, b), "coreEq compares content/role only, so a tokens-only difference compares equal")
    }

    "tokens survives a copy and Schema round-trip" in {
        val msg: AssistantMessage = AssistantMessage("thinking", tokens = Present(42))
        val copied                = msg.copy()
        assert(copied.tokens == Present(42), "copy preserves the stamped tokens field")
        Json.decode[Message](Json.encode(copied: Message)) match
            case Result.Success(decoded) => assert(decoded.tokens == Present(42), "tokens round-trips through Schema encode/decode")
            case other                   => assert(false, s"Message decode failed: $other")
        end match
    }

    "Context still has exactly two fields (raw, compacted)" in {
        val ctx = Context.empty
        ctx match
            case Context(raw, compacted) =>
                assert(raw == ctx.raw && compacted == ctx.compacted, "Context has exactly the raw and compacted fields, no third")
        end match
    }

end ContextTest
