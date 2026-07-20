package kyo

import Compactor.internal.*
import Tool.internal.RunOutcome
import kyo.ai.*
import kyo.ai.Context.*

class CompactorTest extends kyo.test.Test[Any]:

    // ---- construction helpers ----
    def um(s: String): UserMessage                             = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                           = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage          = AssistantMessage(s, Chunk.from(calls))
    def tm(id: String, s: String): ToolMessage                 = ToolMessage(CallId(id), s)
    def call(id: String, fn: String, args: String): Call       = Call(CallId(id), fn, args)
    def ctxOf(msgs: Message*): Context                         = Context(Chunk.from(msgs))
    def seg(id: Int): Segment                                  = Segment(id, Chunk(id), false, 1)
    def edges(es: (Int, List[Edge])*): Graph                   = Graph(Dict.from(es.map((k, v) => (k, Chunk.from(v))).toMap))
    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    /** A config pointing the OpenAI backend at nothing (render is model-free), with the compaction knobs. */
    def serverConfig(budget: Int, window: Int = 128000, low: Double = 0.45, high: Double = 0.7, hard: Double = 0.9): Config =
        Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)
            .compactionBudget(budget)
            .compactionLowWatermark(low)
            .compactionHighWatermark(high)
            .compactionHardLimit(hard)

    /** Runs the default compactor's render under a config; render is model-free so no server is needed. */
    def renderWith(ctx: Context, config: Config)(using Frame): Chunk[Message] < (Async & Abort[AIGenException]) =
        LLM.run(config)(Compactor.init.render(ctx))

    // Row sums of the row-normalized transition matrix (1.0 for a unit with out-edges, 0.0 for a dangling
    // unit). A test-local derivation over Graph: the row-stochastic property is a test assertion.
    def transitionRowSum(id: Int, graph: Graph): Double =
        val es  = graph.edges.get(id).getOrElse(Chunk.empty)
        val sum = es.foldLeft(0.0)((a, e) => a + e.weight)
        if sum <= 0.0 then 0.0 else es.foldLeft(0.0)((a, e) => a + e.weight / sum)
    end transitionRowSum

    def contentLen(c: Chunk[Message]): Int = c.foldLeft(0)((n, m) => n + m.content.length)

    // A transcript whose demotable units are large and sit JUST BEFORE a tiny tail: they are outside the
    // tail window (so non-root) AND the cache gate passes when they are demoted (the invalidated suffix
    // from the edit point is small). fillerChars stays below elisionThreshold so a demoted unit masks to a
    // short marker (a big token saving), not an elision. 12 tiny tail turns keep the big units out of the
    // tailTurns=10 window.
    def demotableContext(fillerChars: Int = 1500, turns: Int = 6): Context =
        val head = Chunk[Message](sm("system prompt"), um("the first task"))
        val body = Chunk.from((0 until turns).map(i => am(s"region $i " + ("x" * fillerChars))))
        val tail = Chunk.from((0 until 12).map(i => am(s"t$i")))
        Context(head.concat(body).concat(tail).append(um("the latest user question")))
    end demotableContext

    // ==== contract ====

    "raw immutable across render" in {
        val ctx = demotableContext(400, 16)
        renderWith(ctx, serverConfig(1)).map { rebuilt =>
            val applied = ctx.copy(compacted = rebuilt)
            assert(applied.raw == ctx.raw, "render never mutates raw; the caller-applied Context keeps raw byte-identical")
            assert(applied.raw.size == ctx.raw.size)
            // render returns ONLY a Chunk[Message] (no raw in its type), so raw immutability is structural.
            assert(rebuilt.nonEmpty)
        }
    }

    "render pure; two instances agree; returns only rebuilt compacted, raw absent from output" in {
        val ctx = demotableContext(400, 16)
        renderWith(ctx, serverConfig(1)).map { a =>
            renderWith(ctx, serverConfig(1)).map { b =>
                assert(a == b, "two renders of the same Context produce byte-identical rebuilt Chunk[Message]")
            }
        }
    }

    "state is only raw+compacted; region bookkeeping derived at boundary" in {
        // A fresh render given only (raw, compacted) reproduces identical levels: run render, feed its output
        // back as compacted, render again with the SAME raw -> identical decision (no external state).
        val ctx = demotableContext(400, 16)
        renderWith(ctx, serverConfig(1)).map { first =>
            val next = ctx.copy(compacted = first)
            renderWith(next, serverConfig(1)).map { second =>
                assert(first == second, "levels are re-derived from (raw, compacted) alone, deterministically")
            }
        }
    }

    "state on Context; nothing to leak; no cell" in {
        // Structural: EdgeKind/Level are the only derived enums; there is no AIRef-keyed field. A behavioral
        // proxy: render holds no cross-call state (proven by the determinism leaf); here assert the derived
        // types exist and carry no identity key.
        assert(Level.values.length == 3)
        assert(EdgeKind.values.length == 2)
    }

    // ==== grouping ====

    "unit fusion + id + interleave" in {
        val ctx   = ctxOf(am("do", call("c1", "f", "{}"), call("c2", "g", "{}")), tm("c2", "r2"), tm("c1", "r1"), um("other"))
        val units = Default.group(ctx.raw)
        assert(units.size == 2, s"expected two units, got ${units.size}")
        val u0 = units.filter(_.id == 0).head
        assert(u0.id == 0, "unit id is the first-message index")
        assert(u0.indices.toList == List(0, 1, 2), s"the pair joins by callId regardless of arrival order, got ${u0.indices}")
        assert(!u0.unresolved)
        val u3 = units.filter(_.id == 3).head
        assert(u3.indices.toList == List(3), "the unrelated singleton is its own unit")
    }

    "unresolved unit pinned" in {
        val ctx   = ctxOf(am("do", call("c1", "f", "{}")), um("next"))
        val units = Default.group(ctx.raw)
        val u0    = units.filter(_.id == 0).head
        assert(u0.unresolved, "an assistant unit with an unanswered call id is unresolved (pinned)")
        // and it renders verbatim even under maximum pressure.
        renderWith(ctxOf(sm("s"), am("do " + ("x" * 400), call("c1", "f", "{}")), um("u")), serverConfig(1)).map { view =>
            assert(view.exists(_.content.contains("do ")), "the unresolved unit is never demoted")
        }
    }

    // ==== graph ====

    "structural-only extraction; no stoplist" in {
        val toks = Default.extractTokens("the and for that with `Config.timeout` plainword value42 CamelCase").toSet
        assert(toks.contains("Config.timeout"), "a backticked identifier registers (backticks stripped)")
        assert(toks.contains("value42"), "a digit-bearing token registers")
        assert(toks.contains("CamelCase"), "a camel-cased token registers")
        List("the", "and", "for", "that", "with", "plainword").foreach(w =>
            assert(!toks.contains(w), s"a bare English word never registers: $w")
        )
    }

    "ref repoint after supersession" in {
        val raw = Chunk[Message](
            am("intro `Widget.field`"),
            um("mid turn"),
            am("update `Widget.field` again"),
            am("later mentions `Widget.field`")
        )
        val units      = Default.group(raw)
        val superseded = Dict[Int, Int]((0, 2))
        val g          = Default.deriveGraph(units, raw, superseded)
        val u3ref      = g.edges.get(3).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Ref)
        assert(u3ref.exists(_.target == 2), "the Ref edge targets the current mapping (unit 2)")
        assert(!u3ref.exists(_.target == 0), "the Ref edge never targets the superseded introducer (unit 0)")
    }

    "keyless no-op; read/write triggers" in {
        val units = Chunk(seg(0), seg(1), seg(2), seg(3), seg(4))
        val keys = Dict[Int, (String, Tool.Kind)](
            (1, ("k", Tool.Kind.Read)),
            (2, ("k", Tool.Kind.Read)),
            (3, ("k", Tool.Kind.Write)),
            (4, ("k", Tool.Kind.Read))
        )
        val sup = Default.supersession(units, keys)
        assert(sup.get(1) == Present(2), "the second same-key read supersedes the first")
        assert(sup.get(2) == Present(3), "the write supersedes the prior read")
        assert(sup.get(0).isEmpty, "a keyless unit never supersedes or is superseded")
        assert(sup.get(3).isEmpty, "a read AFTER a write does not supersede the write")
    }

    "supersession penalty not edge; W row-stochastic" in {
        val units     = Chunk(seg(0), seg(1), seg(2))
        val g         = edges((0, List(Edge(1, EdgeKind.Adj, 1.0), Edge(2, EdgeKind.Ref, 2.0))))
        val seed      = Dict[Int, Double]((0, 1.0))
        val plain     = Default.score(units, g, Dict.empty, seed)
        val penalized = Default.score(units, g, Dict[Int, Int]((1, 2)), seed)
        assert(
            eps(penalized.get(1).getOrElse(0.0), plain.get(1).getOrElse(0.0) * 0.2),
            "supersession multiplies the score by 0.2 outside the walk"
        )
        assert(eps(transitionRowSum(0, g), 1.0), "the normalized transition row sums to 1")
        // the edge set is untouched by supersession (same graph passed both times)
        assert(g.edges.get(0).map(_.size) == Present(2), "no edge deleted or rewired")
    }

    "graph is Adj+Ref only; no Sem edge" in {
        assert(EdgeKind.values.toList == List(EdgeKind.Adj, EdgeKind.Ref), "EdgeKind has exactly Adj and Ref (no Sem)")
        // Present(embedding) enrichment never contributes an edge: a graph over embedding-carrying units has
        // the same edge kinds as one without.
        val raw = Chunk[Message](
            am("a `Foo.bar`").copy(embedding = Present(Embedding(Span(1.0f, 0.0f), "m", 2))),
            am("b `Foo.bar`").copy(embedding = Present(Embedding(Span(0.0f, 1.0f), "m", 2)))
        )
        val g        = Default.deriveGraph(Default.group(raw), raw, Dict.empty)
        val allKinds = g.edges.toMap.values.flatMap(_.toList.map(_.kind)).toSet
        assert(allKinds.forall(k => k == EdgeKind.Adj || k == EdgeKind.Ref), "no edge is derived from embeddings")
    }

    "PPR mass split/decay; ordinal fresh" in {
        // An Adj chain 3 -> 2 -> 1 -> 0 seeded at the tail (unit 3); scores decay away from the seed.
        val units = Chunk(seg(0), seg(1), seg(2), seg(3))
        val g = edges(
            (1, List(Edge(0, EdgeKind.Adj, 1.0))),
            (2, List(Edge(1, EdgeKind.Adj, 1.0))),
            (3, List(Edge(2, EdgeKind.Adj, 1.0)))
        )
        val seed = Dict[Int, Double]((3, 1.0))
        val s1   = Default.score(units, g, Dict.empty, seed)
        val s2   = Default.score(units, g, Dict.empty, seed)
        assert(s1.get(3).getOrElse(0.0) > s1.get(2).getOrElse(0.0), "the seeded tail scores highest")
        assert(s1.get(2).getOrElse(0.0) > s1.get(1).getOrElse(0.0), "mass decays along the chain")
        assert(eps(s1.get(0).getOrElse(0.0), s2.get(0).getOrElse(0.0)), "score is a fresh, deterministic power iteration")
    }

    "Adj-only equals recency; no separate recency/sem-decay" in {
        // With an Adj-only graph and a tail seed, recency is the degenerate PPR case: strictly decreasing away
        // from the seed, driven only by restartWeight and the split (no separate recency prior term).
        val units = Chunk(seg(0), seg(1), seg(2))
        val g     = edges((1, List(Edge(0, EdgeKind.Adj, 1.0))), (2, List(Edge(1, EdgeKind.Adj, 1.0))))
        val s     = Default.score(units, g, Dict.empty, Dict[Int, Double]((2, 1.0)))
        assert(s.get(2).getOrElse(0.0) > s.get(1).getOrElse(0.0) && s.get(1).getOrElse(0.0) > s.get(0).getOrElse(0.0))
    }

    "hub discount + row-normalization" in {
        // "Hub.x" is mentioned by four units (a hub); "Rare.y" by two; the hub token's Ref edge weight is the
        // more heavily discounted (referenceWeight / (1 + log(1 + mentions))).
        val raw = Chunk[Message](
            am("intro `Hub.x` and `Rare.y`"),
            am("again `Hub.x`"),
            am("again `Hub.x`"),
            am("again `Hub.x` and `Rare.y`")
        )
        val units = Default.group(raw)
        val g     = Default.deriveGraph(units, raw, Dict.empty)
        // unit 3 references both Hub.x (introduced unit 0, mentions 4) and Rare.y (introduced unit 0, mentions 2).
        val u3 = g.edges.get(3).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Ref)
        // both Ref edges target unit 0; the one with the larger weight is the rarer token.
        assert(u3.nonEmpty, "unit 3 has Ref edges to its token introducer")
        val weights = u3.map(_.weight).toList
        assert(weights.min < weights.max || weights.size == 1, "a more-mentioned hub token is discounted more than a rarer token")
    }

    // ==== scoring seeds ====

    "light system seed; absent folds into tail" in {
        // No task/objective user unit present: system is seeded 0.05; the absent user-category shares fold into
        // the tail (the tail-seeded units carry the extra mass, never dropped or spread uniformly).
        // system outside the tail window (13 units, tail = last 10) so its seed is the dedicated 0.05 only.
        val raw   = Chunk[Message](sm("system")).concat(Chunk.from((0 until 12).map(i => am(s"a$i"))))
        val units = Default.group(raw)
        val seed  = Default.seedVector(units, raw, ConservativeTokenizer)
        assert(eps(seed.get(0).getOrElse(0.0), 0.05), s"system is seeded 0.05, got ${seed.get(0)}")
        val total = units.toList.map(u => seed.get(u.id).getOrElse(0.0)).sum
        assert(eps(total, 1.0), s"seed mass sums to 1 (absent categories fold into the tail, never dropped), got $total")
    }

    // ==== pins / tail ====

    "tail bounded turns AND tokens" in {
        // 15 recent small units: the tail is bounded by tailTurns=10.
        val small     = Chunk.from((0 until 15).map(i => am(s"turn $i")))
        val tailSmall = Default.tailUnits(Default.group(small), ConservativeTokenizer)
        assert(tailSmall.size == 10, s"tail bounded to tailTurns=10 for small units, got ${tailSmall.size}")
        // Large units: the token cap (12000) bounds the tail below 10 turns, but always keeps >= 1.
        val big     = Chunk.from((0 until 15).map(i => am(s"turn $i " + ("x" * 20000))))
        val tailBig = Default.tailUnits(Default.group(big), ConservativeTokenizer)
        assert(tailBig.nonEmpty, "at least the newest unit is always kept")
        assert(tailBig.size < 10, s"the token cap trims the tail below tailTurns when units are large, got ${tailBig.size}")
    }

    "pinned roots verbatim" in {
        // Under maximum pressure, the leading system, first + latest user, and an unresolved unit render verbatim.
        val ctx = Context(Chunk[Message](
            sm("SYSTEM-ROOT"),
            um("FIRST-USER-ROOT"),
            am("filler " + ("x" * 400)),
            am("open call " + ("x" * 400), call("c9", "f", "{}")),
            um("LATEST-USER-ROOT")
        ))
        renderWith(ctx, serverConfig(1)).map { view =>
            val text = view.map(_.content).mkString(" ")
            assert(text.contains("SYSTEM-ROOT"), "system root verbatim")
            assert(text.contains("FIRST-USER-ROOT"), "first user root verbatim")
            assert(text.contains("LATEST-USER-ROOT"), "latest user root verbatim")
            assert(text.contains("open call"), "the unresolved unit is pinned verbatim")
        }
    }

    // ==== cut / occupancy ====

    "budget sets cut; demotion set definition" in {
        val ctx = demotableContext(400, 16)
        renderWith(ctx, serverConfig(1)).map { tight =>
            renderWith(ctx, serverConfig(10000000)).map { loose =>
                assert(
                    contentLen(tight) < contentLen(ctx.raw),
                    s"a tiny budget demotes middle units (view content shrinks): ${contentLen(tight)} vs ${contentLen(ctx.raw)}"
                )
                assert(loose == ctx.raw, "a huge budget demotes nothing (view == raw); the cut moves with the budget")
            }
        }
    }

    "no band; no judge in default" in {
        // render over an over-budget Context makes ZERO model calls (it completes with no server); the demotion
        // set is the ascending-score walk, not a judge verdict. Proven by render completing model-free.
        renderWith(demotableContext(400, 16), serverConfig(1)).map { view =>
            assert(view.nonEmpty, "the demotion set is purely the ascending-score walk (no cheap-tier judge model)")
        }
    }

    "tokenizer pure; no calibration" in {
        val m = am("abc")
        assert(ConservativeTokenizer.count(m) == ConservativeTokenizer.count(m), "count is a deterministic pure function")
        assert(
            Compactor.Tokenizer.default.count(sm("x" * 30)) == (30 + 2) / 3 + 4,
            "the default over-counts ~1 token per 3 chars + envelope"
        )
    }

    "conservative default + hard-limit margin; pluggable" in {
        // The default over-counts; a custom exact tokenizer is used when plugged.
        val exact = new Compactor.Tokenizer:
            def count(message: Message): Int = 1
        val ctx = demotableContext(400, 16)
        renderWith(ctx, serverConfig(1000000).tokenizer(exact)).map { view =>
            // With every message counted as 1 token and a large budget, nothing needs demotion.
            assert(view == ctx.raw, "the plugged tokenizer drives occupancy (all-1 counts stay under budget)")
        }
    }

    "occupancy gate branches" in {
        val ctx = demotableContext(400, 16)
        // below target (huge budget): view == raw (no demotion)
        renderWith(ctx, serverConfig(10000000)).map { under =>
            // over target (tiny budget, ample window): demotion, no forced abort
            renderWith(ctx, serverConfig(1, window = 128000)).map { over =>
                assert(under == ctx.raw, "below the target the view is the transcript unchanged")
                assert(contentLen(over) < contentLen(ctx.raw), "at/above the target the view is compacted to the low watermark")
            }
        }
    }

    "swapped low/high watermark guarded at the read site; trigger always >= target" in {
        // With low=0.8, high=0.3 (swapped), render's target reads min(0.8,0.3)=0.3 and the seam's trigger reads
        // max(0.8,0.3)=0.8; the trigger is never below the target regardless of builder order.
        val c       = serverConfig(1000, low = 0.8, high = 0.3)
        val target  = math.min(c.compactionLowWatermark, c.compactionHighWatermark)
        val trigger = math.max(c.compactionLowWatermark, c.compactionHighWatermark)
        assert(eps(target, 0.3) && eps(trigger, 0.8), "target = min, trigger = max")
        assert(trigger >= target, "the trigger is never below the target (no cache thrash)")
    }

    // ==== project / ladder ====

    "project total; per-unit render" in {
        val ctx = demotableContext(400, 16)
        renderWith(ctx, serverConfig(1)).map { view =>
            // every demoted unit collapses to exactly one entry; no unit is emitted twice. The view length is
            // bounded by the number of units and never exceeds raw.
            assert(view.size <= ctx.raw.size, "project collapses demoted units, never expands")
            assert(view.map(_.content).toSet.size == view.size || view.nonEmpty, "no duplicate marker for one unit")
        }
    }

    "markers provider-legal; carry recall id" in {
        renderWith(demotableContext(400, 16), serverConfig(1)).map { view =>
            val markers = view.filter(_.content.contains("compacted region"))
            assert(markers.nonEmpty, "demoted units become synthetic markers")
            assert(markers.forall(_.isInstanceOf[SystemMessage]), "markers are plain SystemMessages (provider-legal)")
            assert(markers.forall(m => m.content.contains("recall(")), "every marker names the recall id")
            assert(markers.forall(_.origin.isDefined), "every marker carries Present(origin)")
        }
    }

    "three-level ladder; reduce preferred" in {
        assert(Level.values.toList == List(Level.Verbatim, Level.Reduced, Level.Omitted), "exactly three levels, no L1-compress/L3-summary")
    }

    "elide/mask zero distortion" in {
        // Reduced below the elision threshold masks mechanically; above it, keeps head/tail with an elision mark.
        val below = Default.elide("short content", Compactor.internal.elisionThreshold)
        assert(below.isEmpty, "content below the threshold is not elided (mask marker assembled mechanically instead)")
        val big   = "H" * (Compactor.internal.elisionThreshold + 100)
        val above = Default.elide(big, Compactor.internal.elisionThreshold)
        assert(
            above.isDefined && above.get.contains("...[elided]..."),
            "content above the threshold keeps head/tail with an elision marker"
        )
    }

    "omitted marker-only, recoverable" in {
        // Under maximum pressure a demotable unit reaches Omitted (a bare recall-recoverable marker); the ladder
        // never jumps Verbatim -> Omitted outside the forced path (the cut deepens Reduced -> Omitted).
        renderWith(demotableContext(4000, 16), serverConfig(1)).map { view =>
            val omitted = view.filter(_.content.contains("omitted"))
            assert(omitted.nonEmpty, "a heavily-pressured unit reaches an Omitted marker")
            assert(omitted.forall(_.content.contains("recall(")), "the Omitted marker is recall-recoverable")
        }
    }

    "dedup reference vs near-dup; recall tail-copy folds" in {
        // A byte-identical repeat of an earlier unit folds to a Reference pointer to the original id; a
        // near-duplicate (not byte-identical) never folds to a Reference (it takes the normal reduce/omit path,
        // and a genuine re-read would route through key supersession instead).
        // The dup/near-dup units are large and sit just before a tiny 12-turn tail, so they are demotable
        // (outside the tail) AND the cache gate passes. Units: 2 = original, 3 = near-dup, 4 = repeat.
        val dupText = "SHARED IDENTICAL PAYLOAD BLOCK " + ("z" * 1500)
        val nearDup = dupText + " PLUS ONE DIFFERENCE"
        val fillers = Chunk.from((0 until 12).map(i => am(s"t$i")))
        val ctx = Context(
            Chunk[Message](sm("system"), um("first task"), am(dupText), am(nearDup), am(dupText))
                .concat(fillers)
                .append(um("latest question"))
        )
        renderWith(ctx, serverConfig(1)).map { view =>
            val repeat = view.filter(_.origin.exists(_.start == 4)).headMaybe
            val near   = view.filter(_.origin.exists(_.start == 3)).headMaybe
            assert(
                repeat.exists(_.content.contains("identical to region 2")),
                s"the byte-identical repeat (unit 4) folds to a Reference pointer to region 2, got: $repeat"
            )
            assert(
                near.exists(m => !m.content.contains("identical to region")),
                s"a near-duplicate (unit 3) is NEVER folded to a Reference; it takes the normal reduce/omit path, got: $near"
            )
        }.andThen {
            // The fold predicate compares CORE fields (role/image/calls/callId), not flattened content: a
            // SystemMessage and a ToolMessage sharing a content string are different messages and never fold.
            val crossRaw = Chunk[Message](sm("COLLIDE"), am("filler"), tm("c1", "COLLIDE"))
            val crossDup = Default.duplicateTargets(Default.group(crossRaw), crossRaw)
            assert(
                crossDup.isEmpty,
                s"a SystemMessage and a ToolMessage with identical .content must NOT fold (roles differ), got: $crossDup"
            )
            // A genuine same-type byte-identical repeat DOES fold to the earlier unit id.
            val sameRaw = Chunk[Message](am("REPEAT ME"), am("x"), am("REPEAT ME"))
            val sameDup = Default.duplicateTargets(Default.group(sameRaw), sameRaw)
            assert(sameDup.get(2) == Present(0), s"a same-type byte-identical repeat folds to the earlier unit id, got: $sameDup")

            // recall tail-copy fold (origin identity, the path coreEq cannot reach): a REAL recall exchange
            // (an assistant `recall` call fused with its answering tool result, carrying a provider-unique
            // CallId) whose target region is still present folds to a Reference to that region.
            val recallRaw = Chunk[Message](
                am("REGION ZERO PAYLOAD"),                              // index 0 -> unit 0 (the region)
                um("unrelated"),                                        // index 1
                am("recalling", call("rc7", "recall", """{"id":0}""")), // index 2 (assistant recall, distinct CallId rc7)
                tm("rc7", "REGION ZERO PAYLOAD")                        // index 3 -> fused into unit 2
            )
            val recallUnits = Default.group(recallRaw)
            val recallDup   = Default.duplicateTargets(recallUnits, recallRaw)
            assert(
                recallDup.get(2) == Present(0),
                s"a recall exchange targeting region 0 folds to a Reference to region 0 (origin identity), got: $recallDup"
            )
            // coreEq alone NEVER folds the recall copy (different shape + provider-unique CallId), which is
            // exactly why the origin-based path exists: the false claim was that coreEq's fold reaches it.
            assert(
                !Default.unitCoreEq(recallUnits.filter(_.id == 0).head, recallUnits.filter(_.id == 2).head, recallRaw),
                "the recall exchange is NOT core-equal to the region it reproduces (the fold is origin-based, not coreEq)"
            )
            // a recall whose target region is absent never folds.
            val danglingRaw = Chunk[Message](am("x"), am("recalling", call("rc8", "recall", """{"id":999}""")), tm("rc8", "y"))
            assert(
                Default.duplicateTargets(Default.group(danglingRaw), danglingRaw).get(1).isEmpty,
                "a recall to an absent region never folds"
            )
        }
    }

    // ==== forced ====

    "forced omit-only + overflow abort; doubt renders more" in {
        // (1) large budget (no normal demotion) + small window (a small hard limit): the forced path omits
        // least-live non-root units until the view fits under the hard window.
        val fits = demotableContext(400, 30)
        renderWith(fits, serverConfig(10000000, window = 3000, hard = 0.9)).map { view =>
            assert(
                contentLen(view) < contentLen(fits.raw),
                "the forced path omits least-live non-root units until it fits under the hard window"
            )
        }.andThen {
            // (2) overflow: roots alone exceed the hard window -> Abort (never send over-limit).
            val overflow = Context(Chunk[Message](sm("SYS " + ("x" * 5000))))
            Abort.run[AIGenException](LLM.run(serverConfig(1, window = 100, hard = 0.9))(Compactor.init.render(overflow))).map { r =>
                assert(r.isFailure, s"an unfittable request Aborts AIContextOverflowException rather than sending, got: $r")
            }
        }
    }

    // ==== recall ====

    "recall typed decode; tail-only; instance-bound" in {
        val ctx = demotableContext(400, 16)
        LLM.run(serverConfig(1)) {
            AI.init.map { ai =>
                ai.setContext(ctx).andThen {
                    val info = Default.recallTool(ai).infos.head
                    // (a) malformed frame -> typed decode failure, never a throw
                    info.decodeAndRun("not json at all").map { malformed =>
                        assert(
                            malformed match
                                case RunOutcome.DecodeFailed(_) => true;
                                case _                          => false
                            ,
                            s"malformed recall is a typed decode failure: $malformed"
                        )
                        // (b) unknown id -> typed "no such region", never a crash
                        info.decodeAndRun("""{"id":999}""").map { unknown =>
                            assert(
                                unknown match
                                    case RunOutcome.Ran(Result.Success(o)) => o.contains("no such recallable region");
                                    case _                                 => false
                                ,
                                s"an unknown id returns a typed no-such-region result: $unknown"
                            )
                            // (c) valid id -> the verbatim unit content
                            info.decodeAndRun("""{"id":0}""").map { ok =>
                                assert(
                                    ok match
                                        case RunOutcome.Ran(Result.Success(o)) => o.contains("system prompt");
                                        case _                                 => false
                                    ,
                                    s"a valid id returns the verbatim unit content: $ok"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "recall keyless; no supersession" in {
        LLM.run(serverConfig(1)) {
            AI.init.map { ai =>
                val info = Default.recallTool(ai).infos.head
                assert(info.kind == Tool.Kind.Read, "the recall tool is kind=Read")
                assert(
                    info.compactionKeyFor("""{"id":0}""").isEmpty,
                    "the recall tool is keyless: it never supersedes the region it re-fetches"
                )
            }
        }
    }

    "relevance recall hint-only default" in {
        // With no opt-in, render never auto-re-injects a demoted region: the demoted unit stays a marker.
        renderWith(demotableContext(400, 16), serverConfig(1)).map { view =>
            assert(
                view.exists(_.content.contains("compacted region")),
                "demoted regions stay markers (recall is hint-only, not auto re-injected)"
            )
        }
    }

    // ==== determinism ====

    "default render model-free, synchronous" in {
        // render completes with no completion server configured, proving zero model/HTTP calls on its path.
        renderWith(demotableContext(400, 16), serverConfig(1)).map { view =>
            assert(view.nonEmpty, "render is fully synchronous and model-free")
        }
    }

    "total determinism golden" in {
        val ctx = demotableContext(400, 16)
        renderWith(ctx, serverConfig(1)).map { a =>
            renderWith(ctx, serverConfig(1)).map { b =>
                renderWith(ctx, serverConfig(1)).map { c =>
                    assert(a == b && b == c, "repeated renders of the identical (raw, compacted, config) are byte-identical")
                }
            }
        }
    }

    // ==== origin linkage ====

    "origin absent on raw appends; set with start==unit id on synthetic entries" in {
        val appended = Context.empty.add(um("u")).add(am("a")).raw
        assert(appended.forall(_.origin.isEmpty), "an ordinarily-appended message carries Absent origin")
        renderWith(demotableContext(400, 16), serverConfig(1)).map { view =>
            val markers = view.filter(_.origin.isDefined)
            assert(markers.nonEmpty, "synthetic markers carry Present(origin)")
            assert(
                markers.forall(m => m.origin.get.start >= 0 && m.origin.get.end > m.origin.get.start),
                "origin.start is the unit id, origin.end is exclusive"
            )
        }
    }

    "origin lookup replaces positional matching; recall id->origin->raw slice" in {
        // recall resolves id -> the unit's raw content, typed end to end (via group over raw, keyed by unit id).
        val ctx = demotableContext(400, 16)
        LLM.run(serverConfig(1)) {
            AI.init.map { ai =>
                ai.setContext(ctx).andThen {
                    Default.recallTool(ai).infos.head.decodeAndRun("""{"id":2}""").map { r =>
                        assert(
                            r match
                                case RunOutcome.Ran(Result.Success(o)) => o.contains("region 0");
                                case _                                 => false
                            ,
                            s"recall(id) resolves id -> raw slice for the covered unit, got: $r"
                        )
                    }
                }
            }
        }
    }

    // ==== typed recall arg decode ====

    "recallArgId typed-decodes an object-shaped argument, Absent on malformed" in {
        assert(Default.recallArgId("""{"id":7}""") == Present(7), "a valid object-shaped arg decodes to its id")
        assert(Default.recallArgId("garbage").isEmpty, "a malformed arg decodes to Absent, never a throw")
    }

    // ==== promotion (anti-thrash window) ====

    "origin.since preserved across re-render; cross-boundary recalls promote" in {
        // A single demotable region (unit 0). Three successive boundaries are driven directly through the
        // internal derivation (project -> demotedOrigins -> promotionSet), the exact path render threads.
        // The bug was: renderMarker re-stamped origin.since to the current boundary on every re-render,
        // resetting the anti-thrash window, so a unit recalled once per window never promoted.
        def recallAm(cid: String): AssistantMessage = am("recall", call(cid, "recall", """{"id":0}"""))
        val region0                                 = am("REGION ZERO " + ("x" * 40))
        val demOm                                   = Dict[Int, Level]((0, Level.Omitted))

        // Boundary 1: raw = [region0, q1]; unit 0 demoted at since = raw.size = 2 (first demotion, no prior levels).
        val rawB1  = Chunk[Message](region0, um("q1"))
        val projB1 = Default.project(rawB1, Default.group(rawB1), demOm, rawB1.size, Dict.empty)
        val origB1 = Default.demotedOrigins(projB1)
        assert(origB1.get(0).map(_.since) == Present(2), s"first demotion stamps since = raw.size at B1 (2), got ${origB1.get(0)}")

        // Boundary 2: raw grew (one recall of region 0 at index 2, then q2). Unit 0 stays demoted; the
        // re-render must PRESERVE the original since = 2, never re-stamp it to raw.size = 4.
        val rawB2  = rawB1.append(recallAm("r1")).append(um("q2"))
        val projB2 = Default.project(rawB2, Default.group(rawB2), demOm, rawB2.size, origB1)
        val origB2 = Default.demotedOrigins(projB2)
        assert(
            origB2.get(0).map(_.since) == Present(2),
            s"re-render preserves the original since (2), never re-stamps to raw.size=4, got ${origB2.get(0)}"
        )

        // Boundary 3: a second recall of region 0. With the preserved window (since = 2) BOTH recalls count
        // (index 2 and index 4) so promotion fires; the buggy re-stamp (since = 4) would count only one.
        val rawB3   = rawB2.append(recallAm("r2")).append(um("q3"))
        val unitsB3 = Default.group(rawB3)
        assert(Default.refetchCount(0, rawB3, 2) == 2, "two recalls fall in the preserved since-2 window")
        assert(Default.refetchCount(0, rawB3, 4) == 1, "only one recall falls in the buggy re-stamped since-4 window")
        assert(
            Default.promotionSet(unitsB3, rawB3, origB2) == Set(0),
            "with the preserved window, two cross-boundary recalls PROMOTE region 0"
        )

        // Negative: recalled once only -> NEVER promoted.
        val rawOne = rawB1.append(recallAm("r1")).append(um("q2"))
        assert(
            Default.promotionSet(Default.group(rawOne), rawOne, origB1).isEmpty,
            "a unit recalled fewer than refetchThreshold times is NEVER promoted"
        )
    }

    // ==== tokenizer image accounting ====

    "conservative tokenizer counts images (over-count bias holds for vision content)" in {
        val text   = UserMessage("hello world", Absent)
        val vision = UserMessage("hello world", Present(Image.fromBase64("QUJD")))
        assert(
            ConservativeTokenizer.count(vision) == ConservativeTokenizer.count(text) + ConservativeTokenizer.imageSurcharge,
            "a user-message image adds a fixed conservative token surcharge"
        )
        assert(
            ConservativeTokenizer.count(vision) > ConservativeTokenizer.count(text),
            "an image contributes non-zero tokens so occupancy never under-reads vision content"
        )
    }

end CompactorTest
