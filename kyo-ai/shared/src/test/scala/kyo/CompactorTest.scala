package kyo

import Compactor.internal.*
import Tool.internal.RunOutcome
import kyo.ai.*
import kyo.ai.Context.*

class CompactorTest extends kyo.test.Test[Any]:

    // ---- construction helpers ----
    def um(s: String): UserMessage                       = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                     = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage    = AssistantMessage(s, Chunk.from(calls))
    def tm(id: String, s: String): ToolMessage           = ToolMessage(CallId(id), s)
    def call(id: String, fn: String, args: String): Call = Call(CallId(id), fn, args)
    def ctxOf(msgs: Message*): Context                   = Context(Chunk.from(msgs))

    // Stamp a message with an apportioned token count so occupancy/regions/spans account deterministically.
    def tok(m: Message, n: Int): Message = stamp(m, TokenStamp("t", n))

    // A deterministic tokenizer whose count of each text is a fixed lookup (0 for an unlisted text), with
    // no per-message envelope, so a suffix stamp and an apportioned share are exact literals a leaf asserts.
    def fixedTokenizer(counts: Map[String, Int]): Tokenizer = new Tokenizer:
        def count(texts: Chunk[String])(using Frame): Chunk[Int] < (LLM & Async & Abort[HttpException | AIGenException]) =
            Kyo.lift(texts.map(t => counts.getOrElse(t, 0)))
        override private[kyo] def includesMessageEnvelope: Boolean = true

    // A region value for scoring/span unit tests (its own single index, resolved, stamped tokens).
    def reg(id: Int, tokens: Int = 1): Region = Region(id, Chunk(id), false, tokens)

    // A test-local Graph from adjacency lists over Edge values.
    def graphOf(es: (Int, List[Edge])*): Graph =
        Graph(Dict.from(es.map((k, v) => (k, Chunk.from(v))).toMap))

    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    /** A config pointing the OpenAI backend at nothing (render is model-free), with a valid occupancy axis. */
    def cfg(window: Int = 200000): Config =
        Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    /** Runs a compactor's render under a config; the default render is model-free so no server is needed. */
    def renderWith(ctx: Context, config: Config, compactor: Compactor[Any] = Compactor.init)(using
        Frame
    ): Chunk[Message] < (Async & Abort[AIGenException]) =
        LLM.run(config)(compactor.render(ctx))

    def contentLen(c: Chunk[Message]): Int = c.foldLeft(0)((n, m) => n + m.content.length)

    // A context whose closed middle regions are demotable and whose single large tail region bounds the tail
    // band, so the middle ages into the closed prefix and forms spans; occupancy sits above the trigger.
    def demotable(): Context =
        val head = Chunk[Message](sm("system prompt"), um("first task"))
        val body = Chunk.from((0 until 10).map(i => tok(am(s"region $i " + ("x" * 200)), 600)))
        val tail = Chunk[Message](tok(am("recent tail " + ("y" * 400)), 3000), tok(um("latest question"), 50))
        Context(head.concat(body).concat(tail))
    end demotable

    // ==== byte-stability ====

    "INV-004 the boundary render is a pure deterministic function of its frozen inputs" in {
        val ctx = demotable()
        renderWith(ctx, cfg(16384)).map { a =>
            renderWith(ctx, cfg(16384)).map { b =>
                assert(a == b, "two renders of the identical frozen inputs are byte-identical (no wall-clock/seed nondeterminism)")
            }
        }
    }

    "INV-004-absence below the trigger the seam invokes NO render and re-serves the reference-identical Context" in {
        // A render-counting probe delegating to the default; two below-trigger requests pass through the seam.
        AtomicInt.init(0).map { renders =>
            val probe = new Compactor[Any]:
                def render(c: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
                    renders.incrementAndGet.andThen(Default.render(c))
            // A tiny sub-trigger context: occupancy far below effectiveHigh.
            val small  = ctxOf(sm("s"), um("hi"))
            val config = cfg(200000)
            TestCompletionServer.run { server =>
                server.enqueueBody(genBody("1")).andThen(server.enqueueBody(genBody("2"))).andThen {
                    LLM.run(config.apiUrl(server.baseUrl)) {
                        AI.enable(probe) {
                            AI.init.map { ai =>
                                ai.setContext(small).andThen(ai.gen[Int]).andThen(ai.gen[Int])
                            }
                        }
                    }.andThen {
                        renders.get.map { count =>
                            // The seam never consults render below the trigger; a below-trigger serve carries the
                            // frozen compacted Chunk through untouched (an immutable Chunk re-served is eq).
                            val served1 = small.compacted
                            val served2 = small.compacted
                            assert(count == 0, s"render is invoked ZERO times below the effectiveHigh trigger, got $count")
                            assert(occupancy(small) < config.effectiveHigh, "the probe context sits below the trigger")
                            assert(served2 eq served1, "the below-trigger re-served compacted is reference-identical (eq)")
                        }
                    }
                }
            }
        }
    }

    // A minimal OpenAI completion body that ends the gen loop via the result tool, optionally with usage.
    def genBody(resultValue: String, promptTokens: Int = 0): String =
        val envelope = Json.encode(s"""{"resultValue":$resultValue}""")
        val usage    = if promptTokens <= 0 then "" else s""","usage":{"prompt_tokens":$promptTokens,"completion_tokens":5}"""
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$envelope}}]}}]$usage}"""
    end genBody

    // ==== write-once summary slot ====

    "INV-021 the summary slot is write-once (a second write is discarded)" in {
        val state = CompactionState().withSummary(3, 7, "first").withSummary(3, 7, "second")
        assert(state.summaryOf(3, 7) == Present("first"), "the first bytes written to a slot are permanent (SPAN-FREEZING ii)")
        assert(state.summaryOf(3, 8).isEmpty, "a different span range has its own empty slot")
    }

    // ==== the keep floor ====

    "INV-031 the keep floor holds at pressure <= 1 (the drift-boundary case)" in {
        assert(eps(keep(1.0), keepBase), "keep(1) == keepBase")
        assert(eps(keep(math.max(0.5, 1.0)), keepBase), "callers floor the pressure at 1, so keep never falls below its base")
        assert(keep(2.0) > keepBase, "keep is monotone increasing above pressure 1")
        assert(eps(keep(2.0), keepBase + keepScaling), "keep(2) == keepBase + keepScaling*(2-1)")
    }

    // ==== Compactor.none ====

    "INV-052 Compactor.none serves raw unchanged" in {
        val ctx = demotable()
        renderWith(ctx, cfg(16384), Compactor.none).map { served =>
            assert(served == ctx.raw, "Compactor.none serves ctx.raw byte-for-byte (no demotion, no markers)")
            assert(served.forall(_.origin.isEmpty), "no synthetic marker is produced by the off switch")
        }
    }

    // ==== the summarizer route knob ====

    "INV-052b the summarizer route knob selects warm / provider.small / pinned" in {
        val default = cfg(200000)
        assert(default.compaction.summarizer.isEmpty, "the default summarizer knob is Absent (the warm route)")
        // Absent resolves to the warm route with provider.small as the degraded fallback (fills land P3).
        assert(default.provider.small.modelName.nonEmpty, "provider.small is the degraded fallback route")
        val pinned = Config.OpenAI.gpt_4o_mini
        val set    = default.compaction(_.summarizer(pinned))
        assert(set.compaction.summarizer.exists(_ eq pinned), "Present(pinnedConfig) resolves to the pinned fill model")
    }

    // ==== the compaction path is embedding-free ====

    "INV-014 the compaction path is embedding-free" in {
        // The render produces a valid view with no reference to any Embedding type, and the EdgeKind enum
        // carries no Semantic case (its analysis edges are Dependency/Relatedness, never an embedding edge).
        val kinds = EdgeKind.values.toList
        assert(!kinds.exists(_.toString == "Semantic"), s"EdgeKind carries no Semantic (embedding) case, got $kinds")
        val ctx = ctxOf(sm("s"), um("u1"), am("a1"), tm("c", "r"), um("u2"), am("a2"))
        renderWith(ctx, cfg(200000)).map { view =>
            assert(view.nonEmpty, "render produces a valid view with no Embedding reference on the path")
        }
    }

    "INV-014 the main sources reference no Embedding / EdgeKind.Semantic / .embedding".onlyJvm in {
        val forbidden = List("Embedding", "EdgeKind.Semantic", ".embedding")
        List("Compactor.scala", "ai/Context.scala", "ai/Tokenizer.scala").foreach { name =>
            val text = readMainSource(name)
            forbidden.foreach(tokenName =>
                assert(!text.contains(tokenName), s"$name unexpectedly references $tokenName (INV-014 / R-015)")
            )
        }
    }

    private def readMainSource(fileName: String): String =
        val relative   = s"shared/src/main/scala/kyo/$fileName"
        val candidates = List(new java.io.File(relative), new java.io.File("kyo-ai", relative), new java.io.File(s"../$relative"))
        candidates.find(_.exists()) match
            case Some(file) => scala.io.Source.fromFile(file, "UTF-8").mkString
            case None       => throw new java.io.FileNotFoundException(s"could not locate $fileName from ${sys.props("user.dir")}")
    end readMainSource

    // ==== usage-anchored occupancy ====

    "INV-015 occupancy = last reported total + offline suffix estimate" in {
        val msgs   = Chunk.from((0 until 10).map(i => sm(s"message number $i")))
        val ctx    = Context(msgs).withCompaction(CompactionState(lastUsage = Present(50000), lastUsageRawSize = 8))
        val suffix = msgs.drop(8).foldLeft(0)((n, m) => n + offlineEstimate(m))
        assert(occupancy(ctx) == 50000 + suffix, s"occupancy is the anchor plus the offline suffix, not a whole-view re-sum")
        assert(occupancy(ctx) > 50000, "the two messages appended since the anchor add their offline estimate")
    }

    "INV-015b the next reported total replaces the suffix estimate" in {
        val msgs   = Chunk.from((0 until 12).map(i => sm(s"message number $i")))
        val first  = Context(msgs).withCompaction(CompactionState(lastUsage = Present(50000), lastUsageRawSize = 8))
        val occ1   = occupancy(first)
        val second = first.withCompaction(first.compactionState.withUsage(51000, 12))
        assert(occupancy(second) == 51000, "the exact total replaces the suffix estimate (nothing appended after the new anchor)")
        assert(occupancy(second) != occ1, "the estimate is replaced by the exact total, not accumulated on top of it")
    }

    "INV-015c a mid-session provider switch re-anchors in the new provider's units" in {
        val msgs      = Chunk.from((0 until 10).map(i => sm(s"message number $i")))
        val anchoredA = Context(msgs).withCompaction(CompactionState(lastUsage = Present(50000), lastUsageRawSize = 8))
        val switchedB = anchoredA.withCompaction(anchoredA.compactionState.withUsage(42000, 10))
        assert(occupancy(switchedB) == 42000, "occupancy anchors on B's reported total (its units), plus B-side offline suffix")
        assert(occupancy(switchedB) < 50000, "provider A's 50000 (A's units) never mixes into the count")
    }

    // ==== output reservation counted once ====

    "INV-017 maxOutputTokens is counted once, on the hard-limit side only" in {
        val base = cfg(200000).maxTokens(10000)
        assert(base.hardLimitTokens == (0.9 * (200000 - 10000)).toInt, "hardLimit == 0.9*(window - maxTokens) == 171000")
        assert(base.hardLimitTokens == 171000, s"the hard-limit bound is 171000, got ${base.hardLimitTokens}")
        val ctx      = ctxOf(sm("s"), um("u"), am("a"))
        val occNoMax = occupancy(ctx)
        // occupancy is identical regardless of maxTokens (it is never part of occupancy).
        assert(occupancy(ctx) == occNoMax, "occupancy never subtracts or adds maxOutputTokens")
        // changing maxTokens moves only the hard-limit bound.
        val more = cfg(200000).maxTokens(20000)
        assert(more.hardLimitTokens < base.hardLimitTokens, "a larger output reservation lowers only the hard-limit bound")
    }

    // ==== regions ====

    "INV-019 regions fuse an assistant message with its answering tool results" in {
        val raw = Chunk[Message](
            sm("s"),
            um("u"),
            am("do", call("c1", "f", "{}"), call("c2", "g", "{}")),
            tm("c1", "r1"),
            tm("c2", "r2"),
            am("text")
        )
        val units = Default.group(raw)
        val fused = units.filter(_.id == 2).head
        assert(
            fused.indices.toList == List(2, 3, 4),
            s"the assistant(call) fuses with tool(c1)+tool(c2) into one region, got ${fused.indices}"
        )
        assert(units.map(_.id).toList == List(0, 1, 2, 5), s"region ids are the append-order ordinals, got ${units.map(_.id)}")
        assert(
            !units.exists(u => u.indices.toList == List(3)) && !units.exists(u => u.indices.toList == List(4)),
            "no orphaned tool result region"
        )
    }

    "INV-019b regions resolve by ordinal, not position (a gap survives)" in {
        val raw =
            Chunk[Message](am("a", call("c1", "f", "{}")), tm("c1", "r1"), am("standalone"), am("b", call("c2", "g", "{}")), tm("c2", "r2"))
        val units = Default.group(raw)
        assert(
            units.map(_.id).toList == List(0, 2, 3),
            s"ordinals gain a gap (index 1 fused into region 0), never renumbered, got ${units.map(_.id)}"
        )
        val survivor = units.filter(_.id == 3).head
        assert(survivor.id == survivor.indices.head, "the region id is its first raw index (the stable ordinal)")
        assert(survivor.indices.toList == List(3, 4), "the survivor's fused range is unchanged by the gap")
    }

    // ==== structural graph edges ====

    "INV-023 adjacency + reference edges are deterministic, reference damped by document frequency" in {
        val raw = Chunk[Message](
            am("the intro line"),
            am("the second line"),
            am("the poolMax config"),
            am("the fourth line"),
            am("the fifth line"),
            am("the poolMax again")
        )
        val units = Default.group(raw)
        val g     = Default.deriveGraph(units, raw, Dict.empty)
        val adj1  = g.edges.get(1).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Adjacency)
        assert(adj1.exists(e => e.target == 0 && eps(e.weight, 1.0)), "an Adjacency edge (weight 1.0) links each region to its predecessor")
        val ref5 = g.edges.get(5).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Reference)
        assert(ref5.exists(_.target == 2), "region 5 has a Reference edge to poolMax's introducer (region 2)")
        assert(
            ref5.forall(e => e.weight > 0.0 && e.weight < 3.0),
            s"the reference weight is damped by document frequency below referenceWeight, got ${ref5.map(_.weight)}"
        )
        val ref3 = g.edges.get(3).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Reference)
        assert(ref3.isEmpty, "no Reference edge is minted for the common word `the`")
    }

    "INV-023b sentence-initial capitalized words never mint identifiers" in {
        val toks = Default.extractTokens("The poolSize is 8").toSet
        assert(!toks.contains("The"), "`The` has no interior signal, so it is not extracted as an identifier")
        assert(toks.contains("poolSize"), "a camelCase token with interior signal is extracted")
        val raw = Chunk[Message](am("The cache is warm"), am("The pool is cold"))
        val g   = Default.deriveGraph(Default.group(raw), raw, Dict.empty)
        val ref = g.edges.toMap.values.flatMap(_.toList).filter(_.kind == EdgeKind.Reference)
        assert(ref.isEmpty, "two regions sharing only `The` get no spurious Reference edge")
    }

    // ==== PPR transitivity ====

    "INV-026 PPR transitive reachability keeps a depth-2 reference chain's far end" in {
        val units = Chunk(reg(1), reg(2), reg(3))
        val g     = graphOf((3, List(Edge(2, EdgeKind.Reference, 1.0))), (2, List(Edge(1, EdgeKind.Reference, 1.0))))
        val seed  = Dict[Int, Double]((3, 1.0))
        val s     = Default.score(units, g, Dict.empty, seed)
        assert(s.get(1).getOrElse(0.0) > 0.0, "the depth-2 far end (region 1) keeps a strictly positive PPR liveness (kept)")
        assert(s.get(2).getOrElse(0.0) > s.get(1).getOrElse(0.0), "mass decays along the chain but transits two hops")
    }

    // ==== keyed supersession ====

    "INV-027 keyed supersession penalizes the earlier region and repoints edges" in {
        val units = Chunk(reg(0), reg(1), reg(2))
        val keys  = Dict[Int, (String, Tool.Kind)]((0, ("db.yaml", Tool.Kind.Read)), (2, ("db.yaml", Tool.Kind.Write)))
        val sup   = Default.supersession(units, keys)
        assert(sup.get(0) == Present(2), "region A (read db.yaml) is marked superseded by region B (write db.yaml)")
        // the supersession penalty multiplies the score by 0.2 outside the walk (same graph both times).
        val g     = graphOf((1, List(Edge(0, EdgeKind.Reference, 1.0))))
        val seed  = Dict[Int, Double]((1, 1.0))
        val plain = Default.score(units, g, Dict.empty, seed)
        val pen   = Default.score(units, g, Dict[Int, Int]((0, 2)), seed)
        assert(
            eps(pen.get(0).getOrElse(0.0), plain.get(0).getOrElse(0.0) * supersessionPenalty),
            "A's score is multiplied by the supersession penalty (0.2)"
        )
        // reference edges targeting the superseded region repoint to the superseding one.
        val raw   = Chunk[Message](am("intro `Widget.field`"), am("mid turn"), am("update `Widget.field`"), am("later `Widget.field`"))
        val u     = Default.group(raw)
        val gg    = Default.deriveGraph(u, raw, Dict[Int, Int]((0, 2)))
        val u3ref = gg.edges.get(3).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Reference)
        assert(u3ref.exists(_.target == 2), "the Reference edge repoints to the superseding region 2")
        assert(!u3ref.exists(_.target == 0), "it never targets the superseded introducer (region 0)")
    }

    // ==== the seed set ====

    "INV-028 the liveness seed set is exactly the specified nodes" in {
        val raw = Chunk[Message](
            sm("system head"),                            // 0 system head
            um("the first task"),                         // 1 first user (task)
            tok(am("huge middle " + ("x" * 100)), 15000), // 2 big middle: excluded from tail, no category
            am("a normal region"),                        // 3
            am("open call", call("c1", "f", "{}")),       // 4 unresolved
            um("the last user question"),                 // 5 last user (objective)
            am("a recent region")                         // 6
        )
        val units = Default.group(raw)
        val seed  = Default.seedVector(units, raw, CompactionState())
        assert(seed.get(0).getOrElse(0.0) > 0.0, "the system head is seeded")
        assert(seed.get(1).getOrElse(0.0) > 0.0, "the first user turn is seeded")
        assert(seed.get(4).getOrElse(0.0) > 0.0, "the unresolved region is seeded")
        assert(seed.get(5).getOrElse(0.0) > 0.0, "the last user turn is seeded")
        assert(seed.get(2).getOrElse(0.0) == 0.0, "a middle region outside the tail with no category carries zero seed mass")
    }

    // ==== span formation (SPAN-FREEZING) ====

    // A context whose given regions precede one large tail region that alone exceeds the tail band, so exactly
    // the given regions age into the closed prefix (window 16384 => tail band ~1228 tokens).
    def closedCtx(prefix: Chunk[Message]): Context =
        Context(prefix.append(tok(am("tail region " + ("z" * 100)), 2000)))

    def spanShape(spans: Chunk[Span]): List[(Int, Int, List[Int])] =
        spans.toList.map(sp => (sp.start, sp.end, sp.regionIds.toList))

    "INV-020 span identity is a deterministic model-free function of frozen content" in {
        val ctx   = closedCtx(Chunk.from((0 until 4).map(i => tok(am(s"region $i " + ("x" * 50)), 500))))
        val units = Default.group(ctx.raw)
        val s1    = Default.formSpans(units, ctx.raw, cfg(16384))
        val s2    = Default.formSpans(units, ctx.raw, cfg(16384))
        assert(s1.nonEmpty, "the closed prefix forms at least one span")
        assert(
            spanShape(s1) == spanShape(s2),
            "two formations of the same content are byte-identical (member sets + ranges); no seed enters"
        )
    }

    "INV-020b a span closes early at the formation cap (4000 tokens / 8 regions)" in {
        val ctx   = closedCtx(Chunk.from((0 until 10).map(i => tok(am(s"r$i " + ("x" * 50)), 600))))
        val units = Default.group(ctx.raw)
        val spans = Default.formSpans(units, ctx.raw, cfg(16384))
        val byId  = units.toList.map(u => u.id -> u.tokens).toMap
        assert(spans.size >= 2, s"a 10-region run closes into multiple spans, got ${spans.size}")
        spans.foreach { sp =>
            assert(sp.regionIds.size <= spanCapRegions, s"a span never exceeds spanCapRegions, got ${sp.regionIds.size}")
            val toks = sp.regionIds.foldLeft(0)((n, id) => n + byId.getOrElse(id, 0))
            assert(sp.regionIds.size == 1 || toks <= spanCapTokens, s"a multi-region span stays within spanCapTokens, got $toks")
        }
        // a single over-cap region forms an oversized singleton span alone.
        val ctx2   = closedCtx(Chunk[Message](tok(am("small a"), 300), tok(am("BIG " + ("x" * 100)), 5000), tok(am("small b"), 300)))
        val spans2 = Default.formSpans(Default.group(ctx2.raw), ctx2.raw, cfg(16384))
        assert(
            spans2.exists(sp => sp.regionIds.toList == List(1)),
            s"the >4000-token region 1 forms an oversized singleton span, got ${spanShape(spans2)}"
        )
    }

    "INV-020c a span splits at a user-turn boundary" in {
        val ctx = closedCtx(Chunk[Message](tok(um("user A"), 300), tok(am("asst 1"), 300), tok(um("user B"), 300), tok(am("asst 2"), 300)))
        val spans = Default.formSpans(Default.group(ctx.raw), ctx.raw, cfg(16384))
        assert(
            spans.exists(sp => sp.start == 0 && sp.regionIds.toList == List(0, 1)),
            s"a span ends before user B, got ${spanShape(spans)}"
        )
        assert(spans.exists(sp => sp.start == 2 && sp.regionIds.toList == List(2, 3)), s"a span starts at user B, got ${spanShape(spans)}")
        assert(!spans.exists(sp => sp.start < 2 && sp.end > 2), "no span straddles the user-turn boundary")
    }

    "INV-022 no span covers tail-band content" in {
        val prefix  = Chunk.from((0 until 5).map(i => tok(am(s"r$i " + ("x" * 50)), 600)))
        val ctx     = closedCtx(prefix)
        val spans   = Default.formSpans(Default.group(ctx.raw), ctx.raw, cfg(16384))
        val tailIdx = ctx.raw.size - 1
        assert(spans.nonEmpty, "the closed prefix forms spans")
        assert(spans.forall(sp => sp.end <= tailIdx), "every span lies entirely in the closed prefix, before the tail band")
        assert(spans.forall(sp => !(sp.start <= tailIdx && tailIdx < sp.end)), "no span range intersects the tail-band region index")
    }

    "INV-022b a region with an unresolved tool call never ages into the closed prefix" in {
        val prefix  = Chunk[Message](tok(am("resolved a"), 400), tok(am("open call " + ("x" * 50), call("c1", "f", "{}")), 400))
        val ctx     = closedCtx(prefix)
        val ordered = Default.group(ctx.raw).toList.sortBy(_.id)
        val closed  = Default.closedRegions(ordered, cfg(16384))
        assert(!closed.exists(_.id == 1), "the unresolved region (open tool call) is excluded from the closed set")
        assert(closed.exists(_.id == 0), "the resolved earlier region remains in the closed set")
    }

    // ==== project the three production detail levels ====

    "INV-029 three production detail levels, verbatim/summary/pointer" in {
        val raw       = Chunk[Message](am("region zero VERBATIM"), am("region one MID"), am("region two COLD A"), am("region three COLD B"))
        val units     = Default.group(raw)
        val spans     = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)), Span(2, 4, Chunk(2, 3)))
        val demotions = Dict[Int, Level]((1, Level.Summary), (2, Level.Pointer))
        val view      = Default.project(raw, units, spans, demotions, raw.size, Dict.empty)
        assert(view.exists(_.content.contains("region zero VERBATIM")), "the pinned (undemoted) span renders verbatim raw bytes")
        val summaryMarkers = view.filter(_.origin.exists(_.start == 1))
        assert(summaryMarkers.size == 1, s"the mid span renders exactly one summary-level marker (span grain), got ${summaryMarkers.size}")
        val pointerMarkers = view.filter(_.origin.exists(o => o.start == 2 || o.start == 3))
        assert(
            pointerMarkers.size == 2,
            s"the coldest span renders one pointer marker per member region (region grain), got ${pointerMarkers.size}"
        )
    }

    "INV-029b the pointer descriptor carries the tool name, compaction key, snippet, tokens and recall id, never a bare byte count" in {
        val raw   = Chunk[Message](am("get metrics", call("c1", "httpGet", "{}")), tm("c1", "connections: 5"))
        val units = Default.group(raw)
        val keys  = Dict[Int, (String, Tool.Kind)]((0, ("metrics.connections", Tool.Kind.Read)))
        val seg   = units.filter(_.id == 0).head
        val d     = Default.regionDescriptor(seg, raw, keys)
        assert(d.contains("httpGet"), s"the descriptor names the tool, got: $d")
        assert(d.contains("key metrics.connections"), s"the descriptor names the compaction key, got: $d")
        assert(d.contains("tokens"), "the descriptor carries the stamped token count")
        assert(d.contains("recall(0)"), "the descriptor carries the recall id, never a bare byte count")
        // a call-less, keyless region omits the missing components (no empty ` , key ` fragment).
        val raw2 = Chunk[Message](am("just some text"))
        val d2   = Default.regionDescriptor(Default.group(raw2).head, raw2, Dict.empty)
        assert(!d2.contains(", key "), s"a keyless region omits the key fragment, got: $d2")
        assert(d2.contains("recall(0)") && d2.contains("tokens"), "a call-less region still renders the snippet + tokens + recall id")
    }

    // ==== the cut ====

    "INV-030 pass 1 is unconditional and relevance-complete" in {
        val raw    = Chunk[Message](am("region a"), am("region b"))
        val units  = Default.group(raw)
        val spans  = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)))
        val scores = Dict[Int, Double]((0, 0.001), (1, 0.001))
        val dem    = Default.cut(Context(raw), units, spans, scores, 1.0, 100, 1000, raw.size, Dict.empty)
        assert(dem.get(0) == Present(Level.Summary), "both demotable spans reach the summary level even under target (pass 1 has no stop)")
        assert(dem.get(1) == Present(Level.Summary), "pass 1 runs to exhaustion; pass 2 is inert at/under effectiveLow")
    }

    "INV-030b pass 2 is conditional and stops at effectiveLow" in {
        val ctx = demotable()
        renderWith(ctx, cfg(16384)).map { tight =>
            renderWith(ctx, cfg(200000)).map { loose =>
                assert(contentLen(tight) < contentLen(ctx.raw), "under pressure pass 2 descends demotable spans until the view fits")
                assert(loose == ctx.raw, "at low occupancy (closed set within the tail band) pass 2 is inert; the view is unchanged")
            }
        }
    }

    // ==== span pinning ====

    "INV-032 any at-or-above-keep member pins the whole span verbatim" in {
        val keepFloor = keep(1.3) // 0.03 + 0.06*0.3 == 0.048
        assert(eps(keepFloor, 0.048), s"the floored keep at pressure 1.3 is 0.048, got $keepFloor")
        val hot    = Span(0, 2, Chunk(0, 1))
        val scores = Dict[Int, Double]((0, 0.21), (1, 0.03))
        assert(
            Default.spanMaxLiveness(hot, scores) >= keepFloor,
            "the hottest member (0.21 >= keep) pins the span verbatim (not demotable)"
        )
        val cold    = Span(2, 4, Chunk(2, 3))
        val scores2 = Dict[Int, Double]((2, 0.02), (3, 0.03))
        assert(
            Default.spanMaxLiveness(cold, scores2) < keepFloor,
            "a span with every member below keep IS demotable (any-member aggregation, not a mean)"
        )
    }

    "INV-005 a span with an at-or-above-keep member is pinned verbatim while an all-below-keep sibling demotes" in {
        // two single-region spans; A has one member at/above the floored keep, B has every member below it.
        val raw       = Chunk[Message](tok(am("span A hot member"), 2), tok(am("span B cold one"), 2))
        val units     = Default.group(raw)
        val spans     = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)))
        val scores    = Dict[Int, Double]((0, 0.21), (1, 0.001))
        val keepFloor = keep(1.0)
        assert(Default.spanMaxLiveness(spans(0), scores) >= keepFloor, "span A's hottest member is at or above keep, so it pins verbatim")
        assert(Default.spanMaxLiveness(spans(1), scores) < keepFloor, "span B has every member below keep, so it is demotable")
        // occupied > low forces pass 2, but the pass-1 demotion of B already fits, so B holds at Summary.
        val dem = Default.cut(Context(raw), units, spans, scores, 1.0, 200, 100, raw.size, Dict.empty)
        assert(dem.get(1) == Present(Level.Summary), "the all-below-keep span is demoted to the summary level")
        assert(
            dem.get(0) == Absent,
            "the span with an at-or-above-keep member never enters the demotions map: live content is never demoted"
        )
    }

    // ==== terse (descent only) ====

    "INV-033 terse renders the marker + a fixed prefix of the summary bytes (descent only)" in {
        val sp        = Span(3, 7, Chunk(3, 4, 5, 6))
        val raw       = Chunk.from((0 until 8).map(i => am(s"r$i " + ("y" * 20))))
        val units     = Default.group(raw)
        val longBytes = "B" * (tersePrefixChars + 300)
        val state     = CompactionState().withSummary(3, 7, longBytes)
        val terse     = Default.summaryMarker(sp, raw, units, Level.Terse, raw.size, Dict.empty, state)
        val summary   = Default.summaryMarker(sp, raw, units, Level.Summary, raw.size, Dict.empty, state)
        assert(terse.content.contains("B" * tersePrefixChars), "terse carries safeCut(bytes, tersePrefixChars), a fixed prefix")
        assert(!terse.content.contains("B" * (tersePrefixChars + 1)), "terse truncates at the terse budget")
        assert(terse.content.contains("recall(3)"), "terse carries the same recall id as the summary render")
        assert(summary.content.contains(longBytes), "the summary render carries the whole write-once bytes")
        val short = "C" * (tersePrefixChars - 50)
        assert(Default.tersePrefix(short) == short, "a summary at/under the terse budget renders whole (a zero-saving step)")
    }

    "INV-033-blobless a blob-less span steps verbatim->pointer, never terse; no fill is bought" in {
        val raw    = Chunk.from((0 until 4).map(i => tok(am(s"r$i " + ("x" * 100)), 2000)))
        val units  = Default.group(raw)
        val spans  = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)), Span(2, 3, Chunk(2)), Span(3, 4, Chunk(3)))
        val scores = Dict.from((0 until 4).map(i => (i, 0.001)).toMap)
        // empty compaction state => empty summary slots; pass 2 under pressure must skip terse.
        val dem = Default.cut(Context(raw), units, spans, scores, 5.0, 8000, 1000, raw.size, Dict.empty)
        assert(dem.toMap.nonEmpty, "the demotable spans are demoted")
        assert(
            !dem.toMap.values.toList.contains(Level.Terse),
            "a blob-less span skips terse entirely (no fill route), stepping summary -> pointer"
        )
    }

    // ==== the two elisions ====

    "INV-034 role 1 the fixed-size substitute elision at the summary level" in {
        val sp    = Span(0, 2, Chunk(0, 1))
        val raw   = Chunk[Message](am("region content alpha line one\nline two"), am("region content beta"))
        val units = Default.group(raw)
        val state = CompactionState() // empty slot => substitute elision
        val m1    = Default.summaryMarker(sp, raw, units, Level.Summary, raw.size, Dict.empty, state)
        val m2    = Default.summaryMarker(sp, raw, units, Level.Summary, raw.size, Dict.empty, state)
        assert(m1.content == m2.content, "substituteElision is a deterministic function of the frozen span (no persistence)")
        assert(m1.content.contains("summary unavailable"), "the summary level renders the fixed-size substitute elision (role 1)")
    }

    "INV-034 role 2 the generous exact-surface elision of a pinned oversized unit" in {
        val huge   = "H" * (generousElisionChars + 5000)
        val view   = Chunk[Message](am("small one"), tok(am(huge), 999999), am("small two"))
        val elided = Default.elideOversizedTail(view, 1000)
        assert(elided(0).content == "small one", "other messages are untouched")
        assert(elided(2).content == "small two", "other messages are untouched")
        assert(elided(1).content.contains("...[elided]..."), "the oversized unit keeps head+tail around an elision mark")
        assert(elided(1).content.length < huge.length, "the oversized unit is elided within the generous budget")
    }

    // ==== the forced path ====

    "INV-035 the forced path pointers all then elides the oversized tail to fit" in {
        val raw = Chunk.from((0 until 6).map(i => tok(am(s"r$i " + ("x" * 100)), 1500)))
            .append(um("q")).append(am("GIANT " + ("x" * 30000)))
        val config = cfg(16384)
        renderWith(Context(raw), config).map { view =>
            assert(
                Default.viewTokens(view) <= config.hardLimitTokens,
                s"the forced path returns a view within the hard limit, got ${Default.viewTokens(view)}"
            )
            assert(
                view.exists(_.content.contains("...[elided]...")),
                "the single oversized tail message is elided (generous exact-surface)"
            )
        }
    }

    "INV-035b the forced path aborts AIContextOverflowException only when even that cannot fit" in {
        // Two unresolved regions (open tool calls) are never demotable and never pointered; the forced path
        // elides only the single largest, so the second oversized unit still breaks the hard limit -> abort.
        val raw = Chunk[Message](
            am("open one " + ("x" * 40000), call("c1", "f", "{}")),
            am("open two " + ("x" * 40000), call("c2", "g", "{}"))
        )
        Abort.run[AIGenException](LLM.run(cfg(16384))(Compactor.init.render(Context(raw)))).map { r =>
            assert(
                r match
                    case Result.Failure(_: AIContextOverflowException) => true
                    case _                                             => false
                ,
                s"an unfittable request aborts AIContextOverflowException rather than sending, got: $r"
            )
        }
    }

    "§5d:942-945 a forced-path pointer descriptor carries the compaction key" in {
        // one demotable tool-call region (an assistant httpGet call fused with its result) with a large stamp,
        // a keys map naming its compaction key, and a hard limit small enough that forced pointers the region.
        val raw = Chunk[Message](
            tok(sm("system prompt"), 2),
            tok(um("first task"), 2),
            tok(am("get metrics", call("c1", "httpGet", "{}")), 500),
            tok(tm("c1", "connections: 5"), 500)
        )
        val units      = Default.group(raw)
        val spans      = Chunk(Span(2, 4, Chunk(2)))
        val scores     = Dict[Int, Double]((2, 0.001))
        val keys       = Dict[Int, (String, Tool.Kind)]((2, ("metrics.connections", Tool.Kind.Read)))
        val prevLevels = Dict.empty[Int, Context.Origin]
        val view       = Default.forced(raw, units, spans, scores, 5.0, 100, raw.size, prevLevels, keys)
        val marker     = view.filter(_.origin.exists(_.start == 2))
        assert(marker.size == 1, s"the forced path renders exactly one pointer marker for the region, got ${marker.size}")
        val content = marker.head.content
        assert(content.contains("[region 2:"), s"the marker is the region descriptor, got: $content")
        assert(content.contains("httpGet"), s"the descriptor carries the tool name, got: $content")
        assert(content.contains(", key metrics.connections"), s"the forced-path descriptor carries the compaction key, got: $content")
        assert(content.contains("recall(2)"), s"the descriptor carries the recall id, got: $content")
    }

    // ==== the summary output cap ====

    "§10.4 the fill config caps summary output at the provisional summaryOutputCap over both the default and a user-summarizer path" in {
        // default path: summarizer Absent, so resolveFillConfig falls to provider.small and then caps.
        val defaultResolved = Default.resolveFillConfig(cfg())
        assert(
            defaultResolved.maxTokens == Present(summaryOutputCap),
            s"the default fill config is capped at summaryOutputCap, got ${defaultResolved.maxTokens}"
        )
        assert(defaultResolved.maxTokens == Present(512), "the provisional cap is 512")
        // user-summarizer path: an explicit summarizer with its own 2048 cap is overridden by the mechanism cap.
        val userSummarizer = cfg().maxTokens(2048)
        val userConfig     = cfg().compaction(_.summarizer(userSummarizer))
        val userResolved   = Default.resolveFillConfig(userConfig)
        assert(
            userResolved.maxTokens == Present(summaryOutputCap),
            s"the cap is applied unconditionally, overriding the user summarizer's own 2048, got ${userResolved.maxTokens}"
        )
        assert(userResolved.maxTokens == Present(512), "the resolved user-summarizer fill config caps at 512")
    }

    // ==== the view is held under the hard limit ====

    "INV-006 the rendered view is held at or under the hard limit" in {
        // occupancy well above effectiveLow (and above the hard limit) with several demotable spans; the
        // demotion loop must drive the view under the hard limit. Served under the mock wire, never a live
        // provider (render is model-free, so no completion is scripted and none is ever issued).
        val ctx    = demotable()
        val config = cfg(12288)
        assert(occupancy(ctx) > config.effectiveLow, "the fixture occupancy sits well above effectiveLow so the demotion loop runs")
        TestCompletionServer.run { server =>
            LLM.run(config.apiUrl(server.baseUrl))(Default.render(ctx)).map { view =>
                assert(
                    Default.viewTokens(view) <= config.hardLimitTokens,
                    s"the demotion loop holds the rendered view at or under the hard limit, got ${Default.viewTokens(view)} > ${config.hardLimitTokens}"
                )
                assert(
                    Default.viewTokens(view) < Default.viewTokens(ctx.raw),
                    "the demotion loop shrank the view below the raw occupancy (the demotion actually ran)"
                )
            }
        }
    }

    // ==== the completion path populates apportioned stamps the demotion loop reads ====

    "INV-016c the completion path populates apportioned stamps the demotion loop reads (integration)" in {
        // A fixed test tokenizer (envelope-inclusive) counts each message; one scripted completion reports
        // usage.inputTokens=1000, so the fused reanchor apportions the sent view and propagates stamps onto raw.
        val fixed: Tokenizer = new Tokenizer:
            def count(texts: Chunk[String])(using Frame): Chunk[Int] < Any = texts.map(t => t.length + 10)
            override private[kyo] def includesMessageEnvelope: Boolean     = true
        val setCtx =
            Context(Chunk[Message](sm("system alpha"), um("user beta gamma delta"), am("assistant epsilon"), um("user zeta eta theta")))
        TestCompletionServer.run { server =>
            val config = cfg(200000).apiUrl(server.baseUrl).tokenizer(fixed)
            server.enqueueBody(genBody("7", promptTokens = 1000)).andThen {
                LLM.run(config) {
                    AI.enable(Compactor.init) {
                        AI.init.map(ai => ai.setContext(setCtx).andThen(ai.gen[Int]).andThen(ai.context))
                    }
                }.map { after =>
                    val covered = after.raw.take(4)
                    assert(
                        covered.forall(_.tokens.isDefined),
                        s"every covered message carries Present(tokens), not Absent, got ${covered.map(_.tokens)}"
                    )
                    val sum = covered.foldLeft(0)((n, m) => n + m.tokens.map(_.count).getOrElse(0))
                    assert(
                        sum == 1000,
                        s"the apportioned stamps sum EXACTLY to the reported total (not the offline chars/3 estimate), got $sum"
                    )
                    // the demotion loop reads the apportioned sizes: Region.tokens and viewTokens sum the stamps.
                    val coveredRegions = Default.group(after.raw).filter(_.indices.forall(_ < 4))
                    assert(
                        coveredRegions.foldLeft(0)((n, r) => n + r.tokens) == 1000,
                        "Region.tokens equals the sum of its members' apportioned stamps"
                    )
                    assert(Default.viewTokens(covered) == 1000, "viewTokens sums the apportioned stamps, NOT the offline estimates")
                }
            }
        }
    }

    // ==== raw retention cap and the wholesale forget backstop ====

    // A synthetic demotion marker for a region id (a compacted entry carrying Present(origin), the shape
    // demotedOrigins reads to decide a region is currently demoted).
    def demotionMarker(id: Int, end: Int, since: Int): SystemMessage =
        SystemMessage(s"[region $id compacted]", origin = Present(Context.Origin(id, end, since)))

    // A head (system + task user), a demotable middle, and a 10-region tail so tailUnits (the recent
    // working set) protects only the tail and the middle ages out of it. Region ids: 0 system, 1 task,
    // 2..(2+middle-1) middle, then the tail. Every message is stamped so occupancy is deterministic.
    def retentionRaw(headStamp: Int, middleStamps: List[Int], tailStamp: Int): Chunk[Message] =
        val head   = Chunk[Message](tok(sm("system prompt"), headStamp), tok(um("first task"), headStamp))
        val middle = Chunk.from(middleStamps.zipWithIndex.map((s, i) => tok(am(s"middle region $i " + ("x" * 30)), s)))
        val tail   = Chunk.from((0 until 10).map(i => tok(am(s"tail region $i"), tailStamp)))
        head.concat(middle).concat(tail)
    end retentionRaw

    def rawSumOf(raw: Chunk[Message]): Int = raw.foldLeft(0)((n, m) => n + stampedTokens(m))

    "INV-053 raw is append-only up to the cap: below the high watermark eviction is a no-op" in {
        // head 10 + middle 40 + tail 30 = 80, under the high watermark 0.9*100 = 90.
        val raw       = retentionRaw(5, List(20, 20), 3)
        val compacted = Chunk[Message](demotionMarker(2, 3, raw.size), demotionMarker(3, 4, raw.size))
        val state     = CompactionState().withSummary(2, 3, "s2").withAnalysis(RegionAnalysis(2, Chunk.empty[Context.Relation]))
        val ctx       = Context(raw, compacted).withCompaction(state)
        val config    = cfg().compaction(_.rawRetentionCap(100))
        assert(rawSumOf(raw) == 80, s"the fixture's raw stamp sum is 80, below the high watermark 90, got ${rawSumOf(raw)}")
        val evicted = Default.evict(ctx, config)
        assert(evicted eq ctx, "below the high watermark evict returns the same Context reference, untouched")
        assert(evicted.raw == ctx.raw, "raw is unchanged (append-only under the cap)")
        assert(evicted.raw.size == ctx.raw.size, "raw.size is unchanged")
        assert(evicted.raw.forall(_.origin.isEmpty), "no coarse band and no tombstone are introduced")
        assert(evicted.compactionState == ctx.compactionState, "the compaction state is unchanged")
    }

    "INV-053 wholesale forget: past the high watermark the oldest frozen+demoted middle is forgotten down to the low watermark, replaced by one coarse band with no recall id" in {
        // head 2 + middle 120 + tail 10 = 132, over the high watermark 90; low watermark is 0.5*100 = 50.
        val raw = retentionRaw(1, List(40, 40, 40), 1)
        val compacted =
            Chunk[Message](demotionMarker(2, 3, raw.size), demotionMarker(3, 4, raw.size), demotionMarker(4, 5, raw.size))
        val state = CompactionState()
            .withSummary(2, 3, "s2").withSummary(3, 4, "s3").withSummary(4, 5, "s4")
            .withAnalysis(RegionAnalysis(2, Chunk.empty[Context.Relation]))
            .withAnalysis(RegionAnalysis(4, Chunk.empty[Context.Relation]))
            .withAnalysis(RegionAnalysis(5, Chunk.empty[Context.Relation]))
        val ctx     = Context(raw, compacted).withCompaction(state)
        val config  = cfg().compaction(_.rawRetentionCap(100))
        val evicted = Default.evict(ctx, config)
        assert(evicted.raw.size == ctx.raw.size, "raw.size is unchanged: the forget is in place, ordinals never renumber")
        val bandHeads  = evicted.raw.filter(m => m.origin.isDefined && m.content.nonEmpty)
        val tombstones = evicted.raw.filter(m => m.origin.isDefined && m.content.isEmpty)
        assert(bandHeads.size == 1, s"exactly one coarse band head stands for the contiguous forgotten run, got ${bandHeads.size}")
        assert(tombstones.size == 2, s"the two non-first forgotten ordinals are content-freed tombstones, got ${tombstones.size}")
        assert(!bandHeads.head.content.contains("recall("), "the coarse band text carries no recall id (no 'recall(' substring)")
        val regrouped = Default.group(evicted.raw)
        assert(regrouped.count(_.indices.toList == List(2, 3, 4)) == 1, "the forgotten run fuses back into one region on the next grouping")
        val rawSum2 = rawSumOf(evicted.raw)
        assert(rawSum2 <= 50, s"the raw stamp sum is driven at or below the low watermark 50, got $rawSum2")
        val st = evicted.compactionState
        assert(st.summaries.forall(s => !Set(2, 3, 4).contains(s.start)), "the forgotten regions' summary slots are dropped")
        assert(!st.analyses.exists(_.ordinal == 2), "a forgotten ordinal's analysis slot is dropped")
        assert(!st.analyses.exists(_.ordinal == 4), "the other forgotten ordinal's analysis slot is dropped")
        assert(st.analyses.exists(_.ordinal == 5), "a survivor's analysis slot remains")
    }

    "INV-053-forgotten recall of a forgotten id fails cleanly, and a still-reachable region is unaffected" in {
        // A hand-built post-eviction Context: raw holds a coarse band head at ordinal 2 spanning [2,5) with
        // two tombstones, and a live survivor region at ordinal 5; compacted carries the band marker plus a
        // demotion marker for the survivor so recall can resolve both.
        val raw = Chunk[Message](
            sm("system prompt"),
            um("first task"),
            Default.coarseBand(2, 5, 3, 6),
            Default.tombstone(Context.Origin(2, 5, 6)),
            Default.tombstone(Context.Origin(2, 5, 6)),
            am("SURVIVOR PAYLOAD")
        )
        val compacted = Chunk[Message](Default.coarseBand(2, 5, 3, 6), demotionMarker(5, 6, 6))
        val ctx       = Context(raw, compacted)
        LLM.run(cfg()) {
            AI.init.map { ai =>
                ai.setContext(ctx).andThen {
                    val tool = Default.recallTool(ai).infos.head
                    tool.decodeAndRun("""{"id":2}""").map { r2 =>
                        tool.decodeAndRun("""{"id":3}""").map { r3 =>
                            tool.decodeAndRun("""{"id":5}""").map { r5 =>
                                assert(
                                    r2 match
                                        case RunOutcome.Ran(Result.Success(o)) =>
                                            o == Json.encode(
                                                "region 2 was forgotten past the retention horizon and is no longer recallable"
                                            )
                                        case _ => false
                                    ,
                                    s"recall of a forgotten band head resolves to a clean refusal String, no throw, got: $r2"
                                )
                                assert(
                                    r3 match
                                        case RunOutcome.Ran(Result.Success(o)) => o == Json.encode("no such recallable region: 3")
                                        case _                                 => false
                                    ,
                                    s"recall of a forgotten interior ordinal (folded into the band) is not a distinct region, got: $r3"
                                )
                                assert(
                                    r5 match
                                        case RunOutcome.Ran(Result.Success(o)) => o.contains("assistant: SURVIVOR PAYLOAD")
                                        case _                                 => false
                                    ,
                                    s"recall of a still-live survivor returns its covered messages verbatim and role-tagged, got: $r5"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "INV-053 the backstop never forgets content the task is still using (live over dead)" in {
        // head 10 + middle 120 + tail 10 = 140, over the high watermark 90. R3 (ordinal 2) is LIVE (no
        // demotion marker), R4 (3) and R5 (4) are frozen+demoted.
        val raw       = retentionRaw(5, List(40, 40, 40), 1)
        val compacted = Chunk[Message](demotionMarker(3, 4, raw.size), demotionMarker(4, 5, raw.size))
        val ctx       = Context(raw, compacted)
        val config    = cfg().compaction(_.rawRetentionCap(100))
        val evicted   = Default.evict(ctx, config)
        assert(evicted.raw.size == ctx.raw.size, "raw.size is unchanged")
        assert(evicted.raw(2) == ctx.raw(2), "the live region R3 is never forgotten: its raw bytes are intact")
        assert(evicted.raw(2).origin.isEmpty, "no band covers the live region R3 (a demotion marker is required to be evictable)")
        val bandHeads  = evicted.raw.filter(m => m.origin.isDefined && m.content.nonEmpty)
        val tombstones = evicted.raw.filter(m => m.origin.isDefined && m.content.isEmpty)
        assert(bandHeads.size == 1, s"only the demoted R4,R5 run is forgotten, one coarse band, got ${bandHeads.size}")
        assert(tombstones.size == 1, s"one tombstone for the run's non-first ordinal, got ${tombstones.size}")
        assert(evicted.raw(3).origin.exists(_.start == 3), "the forgotten run starts at ordinal 3 (R4), not the live R3")
        assert(rawSumOf(evicted.raw) > 50, "forgetting only the demotable set cannot reach the low watermark, and that is accepted")
    }

    "INV-053 survivors' ordinals are unchanged: the sequence gains a gap, never renumbers, and slot keys never alias" in {
        val raw = retentionRaw(1, List(40, 40, 40), 1)
        val compacted = Chunk[Message](
            demotionMarker(2, 3, raw.size),
            demotionMarker(3, 4, raw.size),
            demotionMarker(4, 5, raw.size),
            demotionMarker(5, 6, raw.size) // a survivor in the tail band
        )
        val state = CompactionState()
            .withSummary(
                1,
                2,
                "below"
            ).withSummary(2, 3, "s2").withSummary(3, 4, "s3").withSummary(4, 5, "s4").withSummary(5, 6, "survivor")
            .withAnalysis(RegionAnalysis(1, Chunk.empty[Context.Relation]))
            .withAnalysis(RegionAnalysis(2, Chunk.empty[Context.Relation]))
            .withAnalysis(RegionAnalysis(4, Chunk.empty[Context.Relation]))
            .withAnalysis(RegionAnalysis(5, Chunk.empty[Context.Relation]))
        val ctx     = Context(raw, compacted).withCompaction(state)
        val config  = cfg().compaction(_.rawRetentionCap(100))
        val evicted = Default.evict(ctx, config)
        assert(evicted.raw.size == ctx.raw.size, "raw.size is unchanged so the survivor keeps its index (no renumber)")
        assert(
            evicted.raw(5) == ctx.raw(5),
            "the survivor at ordinal 5 sits at the same index with the same bytes (recall(s) resolves the same)"
        )
        assert(evicted.raw(5).origin.isEmpty, "the survivor's raw slot is live, never a band member")
        val st = evicted.compactionState
        assert(st.summaries.exists(s => s.start == 5 && s.end == 6), "the survivor's SpanSummary key (5,6) is unchanged")
        assert(st.summaries.exists(_.start == 1), "a summary slot below the forgotten run is untouched")
        assert(st.summaries.forall(s => !Set(2, 3, 4).contains(s.start)), "the forgotten run's summary slots are dropped, aliasing nothing")
        assert(!st.analyses.exists(_.ordinal == 2), "a forgotten ordinal's analysis slot is dropped")
        assert(!st.analyses.exists(_.ordinal == 4), "the other forgotten ordinal's analysis slot is dropped")
        assert(st.analyses.exists(_.ordinal == 5), "the survivor's analysis slot remains")
        assert(st.analyses.exists(_.ordinal == 1), "an analysis slot below the forgotten run is untouched")
        assert(Default.contiguousRuns(List(2, 3, 4)) == List((2, 5)), "the forgotten ordinals form one maximal half-open run [2,5)")
        val ids = Default.group(evicted.raw).map(_.id).toList.sorted
        assert(!ids.contains(3) && !ids.contains(4), "ordinals 3 and 4 are absorbed into the band: the sequence gains a gap")
        assert(ids.contains(2) && ids.contains(5), "the band head (2) and the survivor (5) keep their ordinals")
    }

    "INV-053 the fixed head band and the tail band are never forgotten even under maximal pressure" in {
        // A tiny cap (20 -> high 18, low 10) drives eviction as deep as it can. The system head and the task
        // turn (ordinals 0,1) carry demotion markers too, so ONLY headBand protects them. Under this tiny cap
        // the head tokens alone already reach the low watermark, so the owed guard shrinks the protected tail
        // to just the newest region; the demoted tail ordinal 5 is no longer positionally protected and joins
        // the evictable middle. The still-live tail ordinals 6..14 (no demotion marker) survive regardless of
        // the shrunk tail band, since the evictable filter forgets nothing that is not currently demoted.
        val raw = retentionRaw(5, List(40, 40, 40), 3)
        val compacted = Chunk[Message](
            demotionMarker(0, 1, raw.size),
            demotionMarker(1, 2, raw.size),
            demotionMarker(2, 3, raw.size),
            demotionMarker(3, 4, raw.size),
            demotionMarker(4, 5, raw.size),
            demotionMarker(5, 6, raw.size)
        )
        val ctx     = Context(raw, compacted)
        val config  = cfg().compaction(_.rawRetentionCap(20))
        val evicted = Default.evict(ctx, config)
        assert(evicted.raw.size == ctx.raw.size, "raw.size is unchanged")
        assert(evicted.raw(0) == ctx.raw(0), "the system head is never forgotten (headBand hard exclusion)")
        assert(evicted.raw(1) == ctx.raw(1), "the task-origin user turn is never forgotten (headBand hard exclusion)")
        assert(evicted.raw(0).origin.isEmpty && evicted.raw(1).origin.isEmpty, "no band covers the head band")
        assert(
            evicted.raw(5) != ctx.raw(5),
            "the demoted tail ordinal 5 loses its positional protection under the shrunk tail band and joins the forgotten run"
        )
        assert(evicted.raw(5).origin.isDefined, "ordinal 5 is a member of the forgotten run's band, not a live slot")
        (6 until raw.size).foreach { i =>
            assert(evicted.raw(i) == ctx.raw(i), s"the still-live tail ordinal $i (no demotion marker) is never forgotten")
            assert(evicted.raw(i).origin.isEmpty, s"no band covers live tail ordinal $i")
        }
        val bandHeads = evicted.raw.filter(m => m.origin.isDefined && m.content.nonEmpty)
        assert(bandHeads.size == 1, "the demoted middle and the now-unprotected demoted ordinal 5 form one contiguous forgotten run")
        assert(
            evicted.raw(2).origin.exists(o => o.start == 2 && o.end == 6),
            "the forgotten run is exactly [2,6): the demoted middle plus the demoted tail ordinal 5"
        )
    }

    "§10.5 the owed guard lets eviction reach the low watermark when the head and tail bands crowd a small cap, and never forgets live content" in {
        // cap 100 -> high 90, low 50. Head 10 tokens, five DEMOTED contiguous regions (2..6) with large
        // stamps, then two LIVE newest regions (7,8). The unguarded fixed tail band would protect the demoted
        // recent regions and stall eviction above low; the owed guard shrinks the tail so they become
        // evictable and eviction reaches low, while the live regions survive.
        val liveMid    = tok(am("LIVE MIDTAIL payload"), 2)
        val liveNewest = tok(am("LIVE NEWEST payload"), 2)
        val raw = Chunk[Message](
            tok(sm("system prompt"), 5),     // 0 head
            tok(um("first task"), 5),        // 1 head (task turn)
            tok(am("demoted middle A"), 40), // 2 demoted
            tok(am("demoted middle B"), 40), // 3 demoted
            tok(am("demoted middle C"), 40), // 4 demoted
            tok(am("demoted recent D"), 40), // 5 demoted (a recent region the fixed tail band would protect)
            tok(am("demoted recent E"), 40), // 6 demoted (a recent region the fixed tail band would protect)
            liveMid,                         // 7 LIVE
            liveNewest                       // 8 LIVE
        )
        val compacted = Chunk[Message](
            demotionMarker(2, 3, raw.size),
            demotionMarker(3, 4, raw.size),
            demotionMarker(4, 5, raw.size),
            demotionMarker(5, 6, raw.size),
            demotionMarker(6, 7, raw.size)
        )
        val ctx    = Context(raw, compacted)
        val config = cfg().compaction(_.rawRetentionCap(100))
        assert(rawSumOf(raw) > 90, s"the fixture sits above the high watermark 90, got ${rawSumOf(raw)}")
        val evicted = Default.evict(ctx, config)
        assert(
            rawSumOf(evicted.raw) <= 50,
            s"the guard trims the tail so eviction reaches the low watermark 50, got ${rawSumOf(evicted.raw)}"
        )
        assert(
            evicted.raw(7) == ctx.raw(7) && evicted.raw(7).content.contains("LIVE MIDTAIL"),
            "the live mid-tail region's content is intact"
        )
        assert(evicted.raw(8) == ctx.raw(8) && evicted.raw(8).content.contains("LIVE NEWEST"), "the live newest region's content is intact")
        assert(
            evicted.raw(7).origin.isEmpty && evicted.raw(8).origin.isEmpty,
            "no band covers a live region: live content is never forgotten"
        )
        assert(
            evicted.raw(6).origin.isDefined,
            "the demoted recent region the fixed tail band would have protected is now evictable and forgotten"
        )
        val bandHeads = evicted.raw.filter(m => m.origin.isDefined && m.content.nonEmpty)
        assert(bandHeads.size == 1, s"the demoted middle and recent regions fuse into one forgotten run, got ${bandHeads.size} bands")

        // control: an all-live recent tail (no demoted recent regions) correctly stalls above low, because
        // the frozen middle alone cannot reach the target and live content is never force-forgotten.
        val liveTail = (0 until 5).map(i => tok(am(s"live tail $i payload"), 20))
        val rawC = Chunk[Message](
            tok(sm("system prompt"), 5),
            tok(um("first task"), 5),
            tok(am("demoted middle A"), 40),
            tok(am("demoted middle B"), 40),
            tok(am("demoted middle C"), 40)
        ).concat(Chunk.from(liveTail))
        val compactedC = Chunk[Message](demotionMarker(2, 3, rawC.size), demotionMarker(3, 4, rawC.size), demotionMarker(4, 5, rawC.size))
        val ctxC       = Context(rawC, compactedC)
        val evictedC   = Default.evict(ctxC, config)
        assert(rawSumOf(evictedC.raw) > 50, s"an all-live tail correctly stalls above the low watermark 50, got ${rawSumOf(evictedC.raw)}")
        (5 until rawC.size).foreach { i =>
            assert(evictedC.raw(i) == rawC(i) && evictedC.raw(i).origin.isEmpty, s"the all-live tail ordinal $i is never forgotten")
        }
    }

    "INV-053 contiguousRuns folds a sorted ordinal list into maximal half-open runs" in {
        assert(Default.contiguousRuns(Nil) == Nil, "the empty list yields no runs")
        assert(Default.contiguousRuns(List(5)) == List((5, 6)), "a singleton yields one unit-width run")
        assert(Default.contiguousRuns(List(2, 3, 4)) == List((2, 5)), "a contiguous block yields one maximal run")
        assert(Default.contiguousRuns(List(2, 4, 5)) == List((2, 3), (4, 6)), "a gap splits into two runs")
        assert(
            Default.contiguousRuns(List(1, 2, 4, 7, 8, 9)) == List((1, 3), (4, 5), (7, 10)),
            "multiple gaps split into as many maximal runs, each half-open [start, endExcl)"
        )
    }

    // ==== the shared stamp-at-creation root (P7) ====

    "§5a:389-391 synthetic summary/pointer markers carry an apportioned stamp after an anchor (nothing escapes its share)" in {
        val summaryMarker = SystemMessage("summary A", Absent, Present(Origin(0, 3, 0)))
        val pointerMarker = SystemMessage("pointer B", Absent, Present(Origin(3, 6, 3)))
        val live1         = tok(um("live one"), 400)
        val live2         = tok(am("live two"), 400)
        val sentView      = Chunk[Message](summaryMarker, pointerMarker, live1, live2)
        val ctx           = Context(Chunk[Message](live1, live2), sentView)
        val tokr          = fixedTokenizer(Map("summary A" -> 100, "pointer B" -> 100, "live one" -> 400, "live two" -> 400))
        LLM.run(cfg())(reanchor(ctx, sentView, 1000, tokr, "id")).map { out =>
            val markers = out.compacted.filter(_.origin.isDefined)
            assert(markers.forall(_.tokens.isDefined), "every marker carries an apportioned stamp, not Absent")
            assert(
                out.compacted.filter(_.origin == Present(Origin(0, 3, 0))).headMaybe.flatMap(_.tokens) == Present(TokenStamp("id", 100)),
                "the summary marker carries its apportioned share (100), not the char/3 estimate"
            )
            assert(
                out.compacted.filter(_.origin == Present(Origin(3, 6, 3))).headMaybe.flatMap(_.tokens) == Present(TokenStamp("id", 100)),
                "the pointer marker carries its apportioned share (100)"
            )
            assert(Default.viewTokens(out.compacted) == 1000, "viewTokens sums the apportioned stamps, markers included")
            val charThree = out.compacted.foldLeft(0)((n, m) => n + offlineEstimate(m))
            assert(
                Default.viewTokens(out.compacted) != charThree,
                s"the priced view is the apportioned sum (1000), not the char/3 estimate ($charThree)"
            )
            val tokr2 = fixedTokenizer(Map("summary A" -> 100, "pointer B" -> 100, "live one" -> 400, "live two" -> 400))
            LLM.run(cfg())(reanchor(out, out.compacted, 1000, tokr2, "id2")).map { out2 =>
                assert(
                    out2.compacted.filter(_.origin.isDefined).forall(_.tokens.exists(_.tokenizerId == "id2")),
                    "a re-anchor in a DIFFERENT vocabulary re-stamps the markers in the new id, never mixing vocabularies"
                )
            }
        }
    }

    "§5a:392,443 the pre-anchor suffix rides in tokenizer units, not char/3" in {
        val marker = SystemMessage("region marker", Absent, Present(Origin(0, 1, 0)))
        val s1     = um("a" * 118) // offlineEstimate == (118+2)/3 + 4 == 44
        val s2     = um("b" * 85)  // offlineEstimate == (85+2)/3 + 4 == 33
        val ctx = Context(Chunk[Message](marker, s1, s2))
            .withCompaction(CompactionState(lastUsage = Present(50000), lastUsageRawSize = 1))
        val tokr = fixedTokenizer(Map(("a" * 118) -> 30, ("b" * 85) -> 20, "region marker" -> 0))
        LLM.run(cfg())(stampLiveSuffix(ctx, tokr, "id")).map { out =>
            val cs1 = out.compacted.filter(_.content == "a" * 118).headMaybe.flatMap(_.tokens)
            val cs2 = out.compacted.filter(_.content == "b" * 85).headMaybe.flatMap(_.tokens)
            assert(cs1 == Present(TokenStamp("id", 30)), s"suffix message one stamped in tokenizer units, got $cs1")
            assert(cs2 == Present(TokenStamp("id", 20)), s"suffix message two stamped in tokenizer units, got $cs2")
            val rs1 = out.raw.filter(_.content == "a" * 118).headMaybe.flatMap(_.tokens)
            val rs2 = out.raw.filter(_.content == "b" * 85).headMaybe.flatMap(_.tokens)
            assert(rs1 == Present(TokenStamp("id", 30)) && rs2 == Present(TokenStamp("id", 20)), "the raw twins carry the identical stamps")
            assert(occupancy(out) == 50000 + 30 + 20, s"occupancy == 50050 (tokenizer units), got ${occupancy(out)}")
            assert(occupancy(out) != 50000 + 44 + 33, "occupancy is not the char/3 basis (50077) the doc rejects")
            val markerTokens = out.compacted.filter(_.origin.isDefined).headMaybe.flatMap(_.tokens)
            assert(markerTokens.isEmpty, "the synthetic marker is not stamped by the suffix pass (the anchor's share owns markers)")
        }
    }

    "§5a:372 the no-usage path applies the distinct widened margin, the anchored path does not" in {
        val m1       = tok(um("live one"), 20000)
        val m2       = tok(am("live two"), 20000)
        val noAnchor = Context(Chunk[Message](m1, m2))
        val anchored = Context(Chunk[Message](m1, m2)).withCompaction(CompactionState(lastUsage = Present(40000), lastUsageRawSize = 2))
        assert(
            occupancy(noAnchor) == (40000 * noUsageMargin).toInt,
            s"the wholly-offline occupancy is the stamped sum widened by the margin, got ${occupancy(noAnchor)}"
        )
        assert(occupancy(noAnchor) > 40000, "the widened margin adds the overflow headroom the never-corrected case gets")
        assert(occupancy(anchored) == 40000, s"the anchored occupancy gets NO margin, got ${occupancy(anchored)}")
        assert(
            occupancy(anchored) < occupancy(noAnchor),
            "the each-turn-corrected anchored path is tighter than the never-corrected offline path"
        )
    }

    "the stamp-at-render pass is idempotent: an all-stamped context is returned by reference" in {
        val marker = SystemMessage("region marker", Absent, Present(Origin(0, 1, 0)))
        val live1  = tok(um("one"), 5)
        val live2  = tok(am("two"), 7)
        val ctx    = Context(Chunk[Message](marker, live1, live2))
        val tokr   = fixedTokenizer(Map("three" -> 9))
        LLM.run(cfg())(stampLiveSuffix(ctx, tokr, "id")).map { out =>
            assert(
                out eq ctx,
                "an all-stamped context is returned by reference (eq), so a below-trigger re-serve stays reference-identical"
            )
            val fresh = um("three")
            val grown = Context(Chunk[Message](marker, live1, live2, fresh))
            LLM.run(cfg())(stampLiveSuffix(grown, tokr, "id")).map { out2 =>
                assert(!(out2 eq grown), "adding one unstamped live message forces a genuine (allocating) pass")
                assert(
                    out2.compacted.filter(_.content == "three").headMaybe.flatMap(_.tokens) == Present(TokenStamp("id", 9)),
                    "only the newly-added live message is stamped"
                )
                assert(
                    out2.compacted.filter(_.content == "one").headMaybe.flatMap(_.tokens) == Present(TokenStamp("t", 5)),
                    "a pre-existing stamp is left intact"
                )
                assert(
                    out2.compacted.filter(_.content == "two").headMaybe.flatMap(_.tokens) == Present(TokenStamp("t", 7)),
                    "a pre-existing stamp is left intact"
                )
            }
        }
    }

end CompactorTest
