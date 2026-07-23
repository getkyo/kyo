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

end CompactorTest
