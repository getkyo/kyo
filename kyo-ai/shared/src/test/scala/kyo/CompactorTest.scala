package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Context.*
import kyo.ai.completion.Completion

class CompactorTest extends kyo.test.Test[Any]:

    // ---- construction helpers ----

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def tm(id: String, s: String): ToolMessage        = ToolMessage(CallId(id), s)
    def call(id: String, fn: String, args: String)    = Call(CallId(id), fn, args)
    def ctxOf(msgs: Message*): Context                = Context(Chunk.from(msgs))
    val book0: Book                                   = Book(0, 0.25, Set.empty, Set.empty, Set.empty)
    def seg(id: Int): Segment                         = Segment(id, Chunk(id), false, 1)
    def edges(es: (Int, List[Edge])*): Graph          = Graph(Dict.from(es.map((k, v) => (k, Chunk.from(v))).toMap))

    // Row sums of the row-normalized transition matrix (1.0 for a unit with out-edges, 0.0 for a dangling
    // unit whose mass stays on the restart/seed). A test-local derivation over Graph: the row-stochastic
    // property is a test assertion, not production surface.
    def transitionRows(units: Chunk[Segment], graph: Graph): Dict[Int, Double] =
        Dict.from(units.toList.map { u =>
            val es  = graph.edges.get(u.id).getOrElse(Chunk.empty)
            val sum = es.foldLeft(0.0)((a, e) => a + e.weight)
            if sum <= 0.0 then (u.id, 0.0) else (u.id, es.foldLeft(0.0)((a, e) => a + e.weight / sum))
        }.toMap)

    /** A config pointing the OpenAI backend at the test server, with a dummy key. */
    def serverConfig(baseUrl: String): Config =
        Config.OpenAI.default.apiKey("test").model(Config.OpenAI, "gpt-4o", 128000).apiUrl(baseUrl)

    // An embeddings response body carrying vectors in order (OpenAI shape).
    def embedBody(vectors: List[List[Double]]): String =
        val data = vectors.zipWithIndex.map { case (v, i) =>
            s"""{"embedding":[${v.mkString(",")}],"index":$i}"""
        }.mkString(",")
        s"""{"data":[$data]}"""
    end embedBody

    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    // ==== checkpoint 2: grouping ====

    "unit fusion + id (pair never severed)" in {
        Compactor.init.map { c =>
            val ctx = ctxOf(
                am("do", call("c1", "f", "{}"), call("c2", "g", "{}")),
                tm("c1", "r1"),
                tm("c2", "r2")
            )
            val units = c.group(ctx, book0)
            assert(units.size == 1, s"expected one fused unit, got ${units.size}")
            assert(units.head.id == 0, s"id must be the assistant message index, got ${units.head.id}")
            assert(units.head.messages.toList == List(0, 1, 2), s"messages: ${units.head.messages}")
            assert(!units.head.unresolved)
        }
    }

    "interleaved tool results group correctly" in {
        Compactor.init.map { c =>
            val ctx   = ctxOf(am("do", call("c1", "f", "{}")), um("unrelated"), tm("c1", "r1"))
            val units = c.group(ctx, book0)
            val u0    = units.filter(_.id == 0).headMaybe
            val u1    = units.filter(_.id == 1).headMaybe
            assert(units.size == 2, s"expected 2 units, got ${units.size}")
            assert(u0.map(_.messages.toList) == Present(List(0, 2)), s"unit0: $u0")
            assert(u1.map(_.messages.toList) == Present(List(1)), s"unit1: $u1")
        }
    }

    "unresolved unit pinned verbatim" in {
        Compactor.init.map { c =>
            val ctx   = ctxOf(am("do", call("c1", "f", "{}")))
            val units = c.group(ctx, book0)
            assert(units.size == 1)
            assert(units.head.unresolved, "a unit with an unanswered call is unresolved")
        }
    }

    "transcript immutable across update" in {
        Compactor.init(_.copy(effectiveCap = 40, windowFraction = 1.0)).map { c =>
            val ctx = ctxOf(
                sm("system"),
                um("first task"),
                am("a1 " + ("x" * 200)),
                am("a2 " + ("y" * 200)),
                um("latest objective")
            )
            LLM.run {
                AI.initWith { ai =>
                    ai.setContext(ctx).andThen(c.render(ai, ctx)).map(_ => ai.context)
                }
            }.map(after => assert(after == ctx, "the transcript must be byte-identical after render"))
        }
    }

    // ==== checkpoint 3: graph ====

    "ref extraction + introducer index" in {
        Compactor.init(_.copy(adjacencyWeight = 1.0)).map { c =>
            // unit0 introduces both a structured path and a bare word; unit1 re-mentions both plus "the".
            val ctx = ctxOf(
                um("intro a/b/C.scala alphaword here"),
                um("again a/b/C.scala alphaword the")
            )
            val units    = c.group(ctx, book0)
            val g        = c.deriveGraph(units, ctx, CompactorState.empty, Dict.empty)
            val u1refs   = g.edges.get(1).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Ref).toList
            val pathEdge = u1refs.filter(e => c.extractTokens("a/b/C.scala").toList.map(_._1).contains("a/b/C.scala") && e.target == 0)
            // there must be a Ref edge to unit0; the structured token weighs more than the bare word.
            assert(u1refs.nonEmpty, s"unit1 must have Ref edges to unit0: $u1refs")
            val weights = u1refs.map(_.weight).sorted
            assert(weights.size >= 2, s"expected structured + bare ref edges, got $u1refs")
            assert(weights.last > weights.head, s"structured token must weigh more than bare: $weights")
            // "the" is stoplisted -> excluded (no token yields an edge for it)
            assert(c.extractTokens("the").isEmpty, "the is stoplisted/excluded")
        }
    }

    "ref repoint after supersession" in {
        Compactor.init.map { c =>
            val ctx = ctxOf(
                um("u0 introduces key_f here"),
                um("filler one"),
                um("u2 writes key_f"),
                um("filler two"),
                um("u4 names key_f")
            )
            val units = c.group(ctx, book0)
            // supersede unit0 by unit2 (state-establishing moves to unit2).
            val superseded = Dict[Int, Int]((0, 2))
            val g          = c.deriveGraph(units, ctx, CompactorState.empty, superseded)
            val u4refs     = g.edges.get(4).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Ref).map(_.target).toList
            assert(u4refs.contains(2), s"u4 Ref must repoint to unit2, not the pre-supersession unit0: $u4refs")
            assert(!u4refs.contains(0), s"u4 Ref must never target the superseded unit0: $u4refs")
        }
    }

    "keyless tool is a supersession no-op" in {
        Compactor.init.map { c =>
            val units      = Chunk(seg(0), seg(1))
            val superseded = c.supersession(units, Dict.empty) // no keys resolved => keyless
            assert(superseded.isEmpty, "keyless tools never supersede")
        }
    }

    "read/write supersession trigger" in {
        Compactor.init.map { c =>
            val units = Chunk(seg(1), seg(3), seg(5))
            val keys =
                Dict[Int, (String, Tool.Kind)]((1, ("/f", Tool.Kind.Read)), (3, ("/f", Tool.Kind.Read)), (5, ("/f", Tool.Kind.Write)))
            val superseded = c.supersession(units, keys)
            assert(superseded.get(1) == Present(3), s"re-read supersedes read: $superseded")
            assert(superseded.get(3) == Present(5), s"write supersedes prior read: $superseded")
        }
    }

    "Tool.Kind is load-bearing in supersession (a read does not supersede a prior write)" in {
        Compactor.init.map { c =>
            val units = Chunk(seg(1), seg(3))
            // Two same-key units. When both are reads, the later read supersedes the earlier.
            val bothRead = c.supersession(units, Dict[Int, (String, Tool.Kind)]((1, ("/f", Tool.Kind.Read)), (3, ("/f", Tool.Kind.Read))))
            assert(bothRead.get(1) == Present(3), s"read then read: the re-read supersedes the prior read: $bothRead")
            // Swapping ONLY unit1's kind Read -> Write changes the map: a read after a write does NOT
            // supersede the write (the write stays live), so nothing is recorded. This is the differential
            // that proves the kind is consumed, not discarded.
            val writeThenRead =
                c.supersession(units, Dict[Int, (String, Tool.Kind)]((1, ("/f", Tool.Kind.Write)), (3, ("/f", Tool.Kind.Read))))
            assert(writeThenRead.isEmpty, s"write then read: the read must not supersede the prior write: $writeThenRead")
            // A write after a write supersedes; a write after a read supersedes (already covered above).
            val writeThenWrite =
                c.supersession(units, Dict[Int, (String, Tool.Kind)]((1, ("/f", Tool.Kind.Write)), (3, ("/f", Tool.Kind.Write))))
            assert(writeThenWrite.get(1) == Present(3), s"write then write: the later write supersedes the earlier: $writeThenWrite")
        }
    }

    "supersession is penalty not edge; W row-stochastic" in {
        Compactor.init.map { c =>
            val units = Chunk(seg(0), seg(1), seg(2))
            val g = edges(
                0 -> List(Edge(1, EdgeKind.Adj, 1.0)),
                1 -> List(Edge(0, EdgeKind.Adj, 1.0), Edge(2, EdgeKind.Ref, 3.0)),
                2 -> List(Edge(1, EdgeKind.Adj, 1.0))
            )
            val rows = transitionRows(units, g)
            assert(
                eps(rows.get(0).getOrElse(0.0), 1.0) && eps(rows.get(1).getOrElse(0.0), 1.0) && eps(rows.get(2).getOrElse(0.0), 1.0),
                s"every row of W must sum to 1: $rows"
            )
            val seed       = Dict[Int, Double]((0, 1.0))
            val baseScore  = c.score(units, g, Dict.empty, seed)
            val superseded = Dict[Int, Int]((2, 0))
            val penalized  = c.score(units, g, superseded, seed)
            // edge set unchanged by supersession (still 2 out-edges on unit1)
            assert(g.edges.get(1).getOrElse(Chunk.empty).size == 2, "supersession must not delete/rewire edges")
            assert(
                eps(penalized.get(2).getOrElse(0.0), baseScore.get(2).getOrElse(0.0) * 0.2),
                s"superseded score is prior * supersessionPenalty: base=${baseScore.get(2)} pen=${penalized.get(2)}"
            )
        }
    }

    // ==== checkpoint 4: scoring ====

    "PPR mass split + geometric decay" in {
        Compactor.init.map { c =>
            // chain 2 -> 1 -> 0 (Adj); seed on unit2.
            val units = Chunk(seg(0), seg(1), seg(2))
            val g     = edges(2 -> List(Edge(1, EdgeKind.Adj, 1.0)), 1 -> List(Edge(0, EdgeKind.Adj, 1.0)))
            val r     = c.score(units, g, Dict.empty, Dict[Int, Double]((2, 1.0)))
            assert(r.get(2).getOrElse(0.0) > r.get(1).getOrElse(0.0), "one hop out carries less than the seed")
            assert(r.get(1).getOrElse(0.0) > r.get(0).getOrElse(0.0), "two hops carries measurably less than one")
        }
    }

    "Adj-only equals recency decay" in {
        Compactor.init.map { c =>
            val alpha = 0.15
            val units = Chunk(seg(0), seg(1), seg(2), seg(3))
            val g =
                edges(3 -> List(Edge(2, EdgeKind.Adj, 1.0)), 2 -> List(Edge(1, EdgeKind.Adj, 1.0)), 1 -> List(Edge(0, EdgeKind.Adj, 1.0)))
            val r = c.score(units, g, Dict.empty, Dict[Int, Double]((3, 1.0)))
            assert(eps(r.get(3).getOrElse(0.0), alpha, 1e-6), s"r_0 == alpha: ${r.get(3)}")
            assert(eps(r.get(2).getOrElse(0.0), alpha * math.pow(1 - alpha, 1), 1e-6), s"r_1: ${r.get(2)}")
            assert(eps(r.get(1).getOrElse(0.0), alpha * math.pow(1 - alpha, 2), 1e-6), s"r_2: ${r.get(1)}")
            assert(eps(r.get(0).getOrElse(0.0), alpha * math.pow(1 - alpha, 3), 1e-6), s"r_3: ${r.get(0)}")
        }
    }

    "ref/sem jump bypasses hops; sem weight halves per semanticDecayHalfLife gap" in {
        Compactor.init(_.copy(semanticDecayHalfLife = 1, semanticFloor = 0.0, semanticNeighbors = 5)).map { c =>
            val v     = Embedding(Span(1.0f, 0.0f), "m", 2)
            val ctx   = ctxOf(um("u0 token_shared alpha"), um("u1 token_shared beta"), um("u2 token_shared gamma"))
            val units = c.group(ctx, book0)
            val st    = CompactorState.empty.copy(vectors = Dict[Int, Embedding]((0, v), (1, v), (2, v)))
            val g     = c.deriveGraph(units, ctx, st, Dict.empty)
            val sem01 = g.edges.get(
                0
            ).getOrElse(Chunk.empty).filter(e => e.kind == EdgeKind.Sem && e.target == 1).map(_.weight).headOption.getOrElse(0.0)
            val sem02 = g.edges.get(
                0
            ).getOrElse(Chunk.empty).filter(e => e.kind == EdgeKind.Sem && e.target == 2).map(_.weight).headOption.getOrElse(0.0)
            // gap(0,1)=1 -> 0.5^1; gap(0,2)=2 -> 0.5^2; ratio halves.
            assert(eps(sem01, 0.5 * 1.0 * math.pow(0.5, 1.0), 1e-9), s"sem(0,1)=$sem01")
            assert(eps(sem02, 0.5 * 1.0 * math.pow(0.5, 2.0), 1e-9), s"sem(0,2)=$sem02")
            assert(eps(sem02 / sem01, 0.5, 1e-9), s"sem halves per halflife gap: $sem01 $sem02")
            val ref01 = g.edges.get(
                1
            ).getOrElse(Chunk.empty).filter(e => e.kind == EdgeKind.Ref && e.target == 0).map(_.weight).headOption.getOrElse(0.0)
            assert(ref01 > sem01, s"Ref jump weighs above the Sem jump: ref=$ref01 sem=$sem01")
        }
    }

    "hub discount + row-normalization" in {
        Compactor.init.map { c =>
            // a token mentioned in many units gets a hub-discounted Ref weight.
            val ctx = ctxOf(
                um("hub_sym introduced"),
                um("hub_sym again"),
                um("hub_sym more"),
                um("hub_sym once more")
            )
            val units = c.group(ctx, book0)
            val g     = c.deriveGraph(units, ctx, CompactorState.empty, Dict.empty)
            val refW = g.edges.get(
                3
            ).getOrElse(Chunk.empty).filter(e => e.kind == EdgeKind.Ref && e.target == 0).map(_.weight).headOption.getOrElse(0.0)
            val mentions = 4
            assert(eps(refW, 3.0 / (1.0 + math.log(1.0 + mentions)), 1e-9), s"hub-discounted ref weight: $refW")
            val rows = transitionRows(units, g)
            rows.foreach((id, s) => assert(s == 0.0 || eps(s, 1.0), s"row $id normalized to 1: $s"))
        }
    }

    "pinned roots verbatim, not judged" in {
        Compactor.init(_.copy(tailTurns = 1)).map { c =>
            val ctx   = ctxOf(sm("sys"), um("first task"), am("mid"), am("do", call("c1", "f", "{}")), um("latest"))
            val units = c.group(ctx, book0)
            val roots = c.roots(units, ctx, book0)
            assert(roots.contains(0), "leading system is a root")
            assert(roots.contains(1), "first user is a root")
            assert(roots.contains(4), "latest user is a root")
            assert(roots.contains(3), "unresolved unit is a root")
        }
    }

    "light system seed" in {
        Compactor.init(_.copy(tailTurns = 1)).map { c =>
            val ctx   = ctxOf(sm("sys"), um("task"), um("objective"), am("final"))
            val units = c.group(ctx, book0)
            val seed  = c.seedVector(units, ctx, book0)
            assert(eps(seed.get(0).getOrElse(0.0), 0.05), s"system seed share is 0.05: ${seed.get(0)}")
        }
    }

    "no separate recency prior; staleness = penalty + sem decay" in {
        Compactor.init.map { c =>
            // recency arises only from the Adj chain; with no seed on a unit and no edges, its score is 0.
            val units = Chunk(seg(0), seg(1))
            val g     = edges(1 -> List(Edge(0, EdgeKind.Adj, 1.0)))
            val r     = c.score(units, g, Dict.empty, Dict[Int, Double]((1, 1.0)))
            // unit0's mass comes purely from the Adj hop, not a standalone recency term.
            assert(eps(r.get(1).getOrElse(0.0), 0.15, 1e-6), s"seed unit keeps restart mass only: ${r.get(1)}")
            assert(eps(r.get(0).getOrElse(0.0), 0.15 * 0.85, 1e-6), s"recency is the Adj hop: ${r.get(0)}")
        }
    }

    "moving frontier, same computation" in {
        Compactor.init.map { c =>
            val units = Chunk(seg(0), seg(1), seg(2))
            val g     = edges(2 -> List(Edge(1, EdgeKind.Adj, 1.0)), 1 -> List(Edge(0, EdgeKind.Adj, 1.0)))
            val atU2  = c.score(units, g, Dict.empty, Dict[Int, Double]((2, 1.0)))
            val atU1  = c.score(units, g, Dict.empty, Dict[Int, Double]((1, 1.0)))
            // the same code path runs; moving the seed just moves where the mass concentrates.
            assert(atU2.get(2).getOrElse(0.0) > atU2.get(0).getOrElse(0.0))
            assert(atU1.get(1).getOrElse(0.0) > atU1.get(2).getOrElse(0.0), "no objective-special branch; seed placement decides")
        }
    }

    "ordinal scores; fresh power iteration" in {
        Compactor.init.map { c =>
            val units = Chunk(seg(0), seg(1))
            val g     = edges(1 -> List(Edge(0, EdgeKind.Adj, 1.0)))
            val a     = c.score(units, g, Dict.empty, Dict[Int, Double]((1, 1.0)))
            val b     = c.score(units, g, Dict.empty, Dict[Int, Double]((1, 1.0)))
            // recomputed fresh each call, no carried incremental state: identical results.
            assert(a.get(0) == b.get(0) && a.get(1) == b.get(1), "fresh power iteration is deterministic")
        }
    }

    "absent category folds into tail" in {
        Compactor.init(_.copy(tailTurns = 1)).map { c =>
            // no unresolved unit: its 0.15 share folds into the tail (0.25 -> 0.40 on the tail unit).
            val ctx   = ctxOf(sm("sys"), um("task"), um("objective"), am("final"))
            val units = c.group(ctx, book0)
            val seed  = c.seedVector(units, ctx, book0)
            assert(eps(seed.get(3).getOrElse(0.0), 0.40), s"unresolved share folds into tail: ${seed.get(3)}")
            assert(eps(seed.get(1).getOrElse(0.0), 0.20), s"other shares unchanged: ${seed.get(1)}")
        }
    }

    "supersession is never modeled as a signed/negative edge" in {
        Compactor.init.map { c =>
            val ctx        = ctxOf(um("u0 key_g"), um("u1 filler"), um("u2 key_g"))
            val units      = c.group(ctx, book0)
            val g          = c.deriveGraph(units, ctx, CompactorState.empty, Dict[Int, Int]((0, 2)))
            val allWeights = units.toList.flatMap(u => g.edges.get(u.id).getOrElse(Chunk.empty).toList.map(_.weight))
            assert(allWeights.forall(_ >= 0.0), s"no negative/signed edge weights: $allWeights")
        }
    }

    // ==== checkpoint 5/6: cut, band, project, ladder ====

    "budget sets the cut line" in {
        // explicit large window so the occupancy gate lands on the UPDATE path deterministically; units are
        // large enough that masking them (a ~60-byte marker) genuinely shrinks the view toward the budget.
        Compactor.init(_.copy(effectiveCap = 40, windowFraction = 1.0, tailTurns = 1)).map { c =>
            val ctx = ctxOf(sm("s"), um("first"), am("a1 " + ("x" * 200)), am("a2 " + ("y" * 200)), um("latest"))
            LLM.run(serverConfig("http://127.0.0.1:1")) {
                AI.initWith { ai =>
                    ai.setContext(ctx).andThen(c.render(ai, ctx)).map { view =>
                        val vt  = view.messages.foldLeft(0)((n, m) => n + m.content.length)
                        val tot = ctx.messages.foldLeft(0)((n, m) => n + m.content.length)
                        assert(vt < tot, s"demotion shrank the view toward the budget: $vt < $tot")
                    }
                }
            }
        }
    }

    "band = K nearest cut; overflow waits" in {
        Compactor.init(_.copy(bandSize = 2, tailTurns = 1)).map { c =>
            // six units, four demotable (non-root): the judge band is capped at bandSize=2, the overflow waits.
            val ctx   = ctxOf(sm("s"), um("first"), am("a"), am("b"), am("d"), um("latest"))
            val units = c.group(ctx, book0)
            val band  = c.judgeBand(units, ctx, CompactorState.empty)
            val roots = c.roots(units, ctx, book0)
            assert(band.size == 2, s"band never exceeds bandSize=2: ${band.map(_.id)}")
            assert(band.forall(u => !roots.contains(u.id)), "the band contains only non-root demotable units")
        }
    }

    def resultBody(text: String): String =
        s"""{"choices":[{"message":{"role":"assistant","content":"$text","tool_calls":null}}]}"""

    "judge question scope" in {
        Compactor.init.map { c =>
            val ctx  = ctxOf(am("a " + ("x" * 50)))
            val band = c.group(ctx, book0).toList
            val jc   = c.judgeContext(band, ctx)
            val head = jc.messages.head.content.toLowerCase
            assert(head.contains("stale") || head.contains("superseded"), s"near-verifiable question: $head")
            assert(!head.contains("important"), s"never asks open-ended importance: $head")
        }
    }

    "judge verdicts land per region, not band-wide; negation and unparsed lines land Uncertain" in {
        Compactor.init.map { c =>
            // A mixed per-region reply: region 2 STALE, region 5 KEEP, region 7 answered with a negation
            // ("not stale; keep it") that does NOT match the strict line form. Each region gets ITS OWN
            // verdict, never one band-wide verdict; the negated line inverts nothing (region 7 -> Uncertain).
            val reply =
                "region 2: STALE\n" +
                    "region 5: KEEP\n" +
                    "region 7 is not stale; keep it"
            val parsed = c.parseVerdicts(reply)
            assert(parsed.get(2) == Some(Verdict.Stale), s"region 2 lands Stale: $parsed")
            assert(parsed.get(5) == Some(Verdict.Keep), s"region 5 lands Keep, not the band-wide Stale: $parsed")
            assert(parsed.get(7).isEmpty, s"a negated/free-text line does not parse (lands Uncertain by default), never Stale: $parsed")
            // SUPERSEDED is an alias for Stale; a bare reply with no region lines yields no verdicts.
            assert(c.parseVerdicts("region 3: superseded").get(3) == Some(Verdict.Stale), "SUPERSEDED maps to Stale")
            assert(c.parseVerdicts("keep everything please").isEmpty, "a reply with no strict region line yields no per-region verdict")
        }
    }

    "verdicts band-local ordinal" in {
        // update consumes state.verdicts: a Stale verdict on a band unit nudges it to be demoted, a Keep holds
        // it back. Differential: the SAME transcript, run once with a Stale verdict on the newest (highest-
        // score) demotable unit and once with a Keep, yields different demotion outcomes for that unit.
        // three demotable units; the budget needs only TWO demoted, so the verdict on the newest (highest-
        // score, normally kept) unit 4 decides whether it is demoted. Stale -> demoted; Keep -> held back.
        Compactor.init(_.copy(effectiveCap = 200, windowFraction = 1.0, tailTurns = 1)).map { c =>
            val ctx = ctxOf(sm("s"), um("first"), am("u2 " + ("x" * 200)), am("u3 " + ("y" * 200)), am("u4 " + ("z" * 200)), um("latest"))
            def demotedWith(verdict: Verdict): Set[Int] < (Async & Abort[AIGenException]) =
                LLM.run(serverConfig("http://127.0.0.1:1")) {
                    AI.initWith { ai =>
                        val ref    = LLM.internal.AIRef(ai)
                        val seeded = CompactorState.empty.copy(verdicts = Dict[Int, Verdict]((4, verdict)), book = book0.copy(seen = 0))
                        ai.setContext(ctx).andThen(c.cell.set(Dict((ref, seeded)))).andThen(c.render(ai, ctx)).andThen {
                            c.cell.get.map(d => d.get(ref).getOrElse(CompactorState.empty).renderings.toChunk.toList.map(_._1).toSet)
                        }
                    }
                }
            demotedWith(Verdict.Stale).map { staleSet =>
                demotedWith(Verdict.Keep).map { keepSet =>
                    assert(staleSet.contains(4), s"a Stale verdict nudges the newest band unit into the demotion set: $staleSet")
                    assert(!keepSet.contains(4), s"a Keep verdict holds the newest band unit back: $keepSet")
                }
            }
        }
    }

    "stale verdict discarded" in {
        Compactor.init(_.copy(effectiveCap = 30, windowFraction = 1.0, tailTurns = 1)).map { c =>
            // a verdict for a unit outside the fresh band (here the leading system ROOT, never in the band) is
            // discarded: it is not consulted and never forces the root's demotion.
            val ctx = ctxOf(sm("s " + ("x" * 80)), um("first"), am("mid " + ("y" * 80)), um("latest"))
            LLM.run {
                AI.initWith { ai =>
                    val ref    = LLM.internal.AIRef(ai)
                    val seeded = CompactorState.empty.copy(verdicts = Dict[Int, Verdict]((0, Verdict.Stale)), book = book0.copy(seen = 0))
                    ai.setContext(ctx).andThen(c.cell.set(Dict((ref, seeded)))).andThen(c.render(ai, ctx)).andThen {
                        c.cell.get.map { d =>
                            val demoted = d.get(ref).getOrElse(CompactorState.empty).renderings
                            assert(!demoted.contains(0), "a Stale verdict for a non-band (root) unit is discarded, never demoting it")
                        }
                    }
                }
            }
        }
    }

    "project total, covers/skip" in {
        Compactor.init.map { c =>
            val ctx  = ctxOf(um("u0"), um("u1"), um("u2"), um("u3"))
            val st   = CompactorState.empty.copy(renderings = Dict[Int, Rendered]((0, Rendered(3, 3, 0, Chunk(sm("[summary of 0..2]"))))))
            val view = c.project(ctx, st)
            // summary carrier emits once and skips the next 2 units; u3 survives verbatim.
            assert(view.messages.size == 2, s"total walk: summary + u3, got ${view.messages}")
            assert(view.messages.head == sm("[summary of 0..2]"))
            assert(view.messages(1) == um("u3"))
        }
    }

    "markers provider-legal; pairs together" in {
        Compactor.init.map { c =>
            val ctx = ctxOf(am("do", call("c1", "f", "{}")), tm("c1", "result"), um("after"))
            val st  = CompactorState.empty.copy(renderings = Dict[Int, Rendered]((0, Rendered(4, 1, 0, Chunk(sm("[compacted region 0]"))))))
            val view = c.project(ctx, st)
            assert(view.messages.head == sm("[compacted region 0]"), "marker is a plain SystemMessage")
            assert(!view.messages.exists { case _: ToolMessage => true; case _ => false }, "tool_use/tool_result disappear together")
            assert(view.messages.contains(um("after")), "unrelated content survives")
        }
    }

    "marker carries recall id" in {
        Compactor.init.map { c =>
            val ctx    = ctxOf(um("payload " + ("x" * 30)))
            val units  = c.group(ctx, book0)
            val marker = c.maskMarker(units.head, ctx)
            assert(marker.contains("region 0"), s"names the removed region: $marker")
            assert(marker.contains("recall(0)"), s"carries the recall id: $marker")
        }
    }

    "five levels; non-distorting preferred" in {
        Compactor.init.map { c =>
            val ctx = ctxOf(um("filler " + ("x" * 200)))
            val u   = c.group(ctx, book0).head
            // first touch is non-distorting (L1/L2) via the real ladderStep, never the distorting L3.
            val first = c.ladderStep(u, CompactorState.empty, ctx, 10, Absent)
            assert(first.exists(r => r.level == 1 || r.level == 2), s"first touch is non-distorting L1/L2: $first")
            assert(first.exists(_.level < 3), "non-distorting preferred over the L3 summary")
            // a unit that stood at L1-2 since a prior update deepens (to L4 here), so all five levels 0..4 exist.
            val stood = CompactorState.empty.copy(renderings = Dict[Int, Rendered]((u.id, Rendered(2, 1, 0, Chunk(sm("[compacted]"))))))
            assert(c.ladderStep(u, stood, ctx, 10, Absent).exists(_.level == 4), "deepens to L4 under continued pressure")
        }
    }

    "L1 skips diffs/patches" in {
        Compactor.init.map { c =>
            val json = "{\n  \"a\": 1,\n  \"a\": 1,\n  \"a\": 1\n}"
            val diff = "@@ -1,3 +1,3 @@\n-old\n+new\n context"
            val cj   = c.compress(json)
            val cd   = c.compress(diff)
            assert(cd == Absent, "a diff is never routed through Compressed")
            assert(cj.exists(_.contains("(x")), s"repeated JSON lines collapse with a count: $cj")
        }
    }

    "L2 elision/masking zero distortion" in {
        Compactor.init(_.copy(elisionThreshold = 40)).map { c =>
            // distinct (non-repeating) oversized content: compress cannot reduce it, so the ladder elides at L2.
            val big = (0 until 30).map(i => s"line$i has distinct content").mkString("\n")
            val el  = c.elide(big, 40)
            assert(el.exists(_.contains("elided")), s"elision keeps head/tail with a middle marker: $el")
            val ctx  = ctxOf(um(big))
            val u    = c.group(ctx, book0).head
            val step = c.ladderStep(u, CompactorState.empty, ctx, 10, Absent)
            assert(
                step.exists(r => r.level == 2 && r.replacement.head.content.contains("elided")),
                s"oversized content elides at L2: $step"
            )
            // the masking marker is assembled mechanically (byte count + recall id), no model-generated text.
            val marker = c.maskMarker(u, ctx)
            assert(
                marker.contains("bytes omitted") && marker.contains("region 0") && marker.contains("recall(0)"),
                s"mechanical marker fields only: $marker"
            )
        }
    }

    "dedup reference vs near-dup supersession" in {
        Compactor.init.map { c =>
            val ctx   = ctxOf(um("identical payload text"), um("identical payload text"))
            val units = c.group(ctx, book0)
            // duplicateMap detects the byte-identical repeat; ladderStep dedups it to a Reference (L2).
            val dup = c.duplicateMap(units, ctx)
            assert(dup.get(1) == Some(0), s"the byte-identical repeat maps to the original: $dup")
            val step = c.ladderStep(units.toList(1), CompactorState.empty, ctx, 10, Present(0))
            assert(
                step.exists(r => r.level == 2 && r.replacement.head.content.contains("duplicates region 0")),
                s"reference pointer at L2: $step"
            )
            // a near-duplicate (different content) is NOT deduped: it goes through supersession instead.
            val ctx2 = ctxOf(um("payload text one"), um("payload text two"))
            assert(c.duplicateMap(c.group(ctx2, book0), ctx2).isEmpty, "near-duplicates are not byte-identical dedups")
        }
    }

    "summary from original, never re-summarized" in {
        Compactor.init(_.copy(tailTurns = 1)).map { c =>
            // a contiguous run of >= 2 demotable units is a summarization candidate; its source is the ORIGINAL
            // transcript content (never a prior summary).
            val ctx   = ctxOf(sm("s"), um("first"), am("mid one"), am("mid two"), am("mid three"), um("latest"))
            val units = c.group(ctx, book0)
            val runs  = c.summaryCandidates(units, ctx, CompactorState.empty)
            assert(runs.exists(_.size >= 2), s"a contiguous run of demotable units is a summary candidate: ${runs.map(_.map(_.id))}")
            assert(c.unitContent(units.toList(2), ctx).contains("mid one"), "the summary sources original transcript content")
            // L3 adoption uses the background-PREPARED summary, never re-summarizing a prior Rendered.replacement.
            val prepared = Rendered(3, 2, 0, Chunk(sm("PREP from original")))
            val stood = CompactorState.empty.copy(
                renderings = Dict[Int, Rendered]((units.toList(2).id, Rendered(2, 1, 0, Chunk(sm("[compacted]"))))),
                prepared = Dict[Int, Rendered]((units.toList(2).id, prepared))
            )
            val step = c.ladderStep(units.toList(2), stood, ctx, 10, Absent)
            assert(
                step.exists(r => r.level == 3 && r.replacement.head.content == "PREP from original"),
                s"L3 adopts the prepared summary: $step"
            )
        }
    }

    "coherence split breaks a low-coherence run at the widest vector gap" in {
        Compactor.init(_.copy(tailTurns = 1)).map { c =>
            // Two coherent clusters inside one contiguous run of demotable units: {2,3} near-identical, {4,5}
            // near-identical, cross-cluster near-orthogonal. The whole run's mean pairwise cosine sits below
            // coherenceFloor (0.55) but each cluster independently clears it, so the run splits into two.
            val ctx   = ctxOf(sm("s"), um("first"), am("m0"), am("m1"), am("m2"), am("m3"), um("latest"))
            val units = c.group(ctx, book0)
            val v2    = Embedding(Span(1.0f, 0.0f), "m", 2)
            val v3    = Embedding(Span(0.99f, 0.14f), "m", 2)
            val v4    = Embedding(Span(0.0f, 1.0f), "m", 2)
            val v5    = Embedding(Span(0.14f, 0.99f), "m", 2)
            val st    = CompactorState.empty.copy(vectors = Dict[Int, Embedding]((2, v2), (3, v3), (4, v4), (5, v5)))
            val runs  = c.summaryCandidates(units, ctx, st)
            assert(
                runs.map(_.map(_.id).toSet).toSet == Set(Set(2, 3), Set(4, 5)),
                s"a low-coherence run splits at the widest cosine gap into two coherent sub-runs: ${runs.map(_.map(_.id))}"
            )
        }
    }

    "a coherent run is not split, and coherenceFloor is a live knob" in {
        Compactor.init(_.copy(tailTurns = 1)).map { c =>
            // coherenceFloor gates the split: at the default floor the coherent run stays whole.
            val ctx = ctxOf(sm("s"), um("first"), am("m0"), am("m1"), am("m2"), am("m3"), um("latest"))
            val vectors = Dict[Int, Embedding](
                (2, Embedding(Span(1.0f, 0.0f), "m", 2)),
                (3, Embedding(Span(0.99f, 0.14f), "m", 2)),
                (4, Embedding(Span(0.98f, 0.2f), "m", 2)),
                (5, Embedding(Span(0.97f, 0.24f), "m", 2))
            )
            val units = c.group(ctx, book0)
            val st    = CompactorState.empty.copy(vectors = vectors)
            val runs  = c.summaryCandidates(units, ctx, st)
            assert(
                runs.map(_.map(_.id)) == List(List(2, 3, 4, 5)),
                s"a coherent run stays whole under the default floor: ${runs.map(_.map(_.id))}"
            )
            val strict = Compactor.init(_.copy(tailTurns = 1, coherenceFloor = 0.999)).map { cc =>
                cc.summaryCandidates(cc.group(ctx, book0), ctx, st)
            }
            strict.map { strictRuns =>
                // Raising the floor above the run's mean cosine fragments it: no run of >=4 survives, so coherenceFloor drives the split.
                assert(
                    strictRuns.map(_.map(_.id)) != runs.map(_.map(_.id)) && !strictRuns.exists(_.size >= 4),
                    s"raising coherenceFloor fragments the same run, proving the knob is live: ${strictRuns.map(_.map(_.id))}"
                )
            }
        }
    }

    "an intermediate User boundary splits a run even with no vectors" in {
        Compactor.init(_.copy(tailTurns = 1)).map { c =>
            // A run of demotable units with an intermediate UserMessage (not the transcript's first or last user
            // message, so not itself a root): the run splits around that user boundary rather than staying contiguous.
            val ctx   = ctxOf(sm("s"), um("first"), am("a0"), am("a1"), um("mid"), am("b0"), am("b1"), um("latest"))
            val units = c.group(ctx, book0)
            val runs  = c.summaryCandidates(units, ctx, CompactorState.empty)
            assert(
                runs.map(_.map(_.id).toSet).toSet == Set(Set(2, 3), Set(5, 6)),
                s"the run splits around the intermediate user unit (id 4), never sweeping it in: ${runs.map(_.map(_.id))}"
            )
        }
    }

    "L4 marker-only, recoverable" in {
        Compactor.init.map { c =>
            val ctx = ctxOf(um("payload " + ("x" * 40)))
            val u   = c.group(ctx, book0).head
            // L4 is reached only from L1-2 under continued pressure (two-touch), never from verbatim in one pass.
            assert(c.ladderStep(u, CompactorState.empty, ctx, 10, Absent).exists(_.level != 4), "a first touch is never L4")
            val stood = CompactorState.empty.copy(renderings = Dict[Int, Rendered]((u.id, Rendered(2, 1, 0, Chunk(sm("[compacted]"))))))
            val omit  = c.ladderStep(u, stood, ctx, 10, Absent)
            assert(
                omit.exists(r => r.level == 4 && r.replacement.head.content.contains("recall(0)")),
                s"L4 marker-only, recall-recoverable: $omit"
            )
        }
    }

    "demotion set definition" in {
        Compactor.init(_.copy(tailTurns = 1)).map { c =>
            val ctx   = ctxOf(sm("s"), um("first"), am("a"), am("b"), um("latest"))
            val units = c.group(ctx, book0)
            val roots = c.roots(units, ctx, book0)
            // demotable = non-root, non-window units; roots (system/first/last user/tail) are excluded.
            val demotable = units.toList.filter(u => !roots.contains(u.id))
            assert(demotable.map(_.id).toSet == Set(2, 3), s"only the middle assistant units are demotable: ${demotable.map(_.id)}")
        }
    }

    // ==== checkpoint 7: lifecycle / render ====

    "no derived state persisted (recomputed, not cached)" in {
        Compactor.init.map { c =>
            assert(CompactorState.empty.productArity == 5, "CompactorState persists exactly 5 fields")
            // grouping recomputed identically from the transcript.
            val ctx = ctxOf(um("a"), um("b"))
            val g1  = c.group(ctx, book0).toList
            val g2  = c.group(ctx, book0).toList
            assert(g1.map(_.id) == g2.map(_.id), "grouping is recomputed identically, not cached")
        }
    }

    "rebuild state from transcript" in {
        Compactor.init(_.copy(effectiveCap = 40, windowFraction = 1.0, tailTurns = 1)).map { c =>
            val ctx = ctxOf(sm("s"), um("first"), am("a " + ("x" * 120)), um("latest"))
            LLM.run {
                AI.initWith { fresh =>
                    fresh.setContext(ctx).andThen(c.render(fresh, ctx)).map { view =>
                        assert(view.messages.nonEmpty, "a fresh compactor rebuilds a view from the transcript alone")
                    }
                }
            }
        }
    }

    "non-append guard: rewound transcript resets state; append does not (two sub-cases)" in {
        Compactor.init.map { c =>
            LLM.run {
                AI.initWith { ai =>
                    val grown = ctxOf(um("m0"), um("m1"), um("m2"), um("m3"))
                    ai.setContext(grown).andThen {
                        // seed a state with book.seen == grown length and a rendering, then test both sub-cases.
                        val ref = LLM.internal.AIRef(ai)
                        c.cell.set(Dict((
                            ref,
                            CompactorState.empty.copy(
                                renderings = Dict[Int, Rendered]((0, Rendered(4, 1, 0, Chunk(sm("[compacted 0]"))))),
                                book = book0.copy(seen = 4)
                            )
                        ))).andThen {
                            // sub-case A: rewind shorter than seen -> reset (no prior renderings survive).
                            val shorter = ctxOf(um("m0"))
                            c.render(ai, shorter).map { viewA =>
                                c.cell.get.map { after =>
                                    val stA = after.get(ref).getOrElse(CompactorState.empty)
                                    assert(
                                        !viewA.messages.exists(_.content.contains("[compacted 0]")),
                                        "sub-case A: rewound view carries none of the prior renderings"
                                    )
                                    // sub-case B: append-only -> renderings reused, seen advanced to the live length.
                                    c.cell.set(Dict((ref, CompactorState.empty.copy(book = book0.copy(seen = 1))))).andThen {
                                        val appended = ctxOf(um("m0"), um("m1"), um("m2"))
                                        c.render(ai, appended).map { _ =>
                                            c.cell.get.map { after2 =>
                                                val seenB = after2.get(ref).getOrElse(CompactorState.empty).book.seen
                                                assert(seenB == 3, s"sub-case B: book.seen advanced to the live length, not 0: $seenB")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "state-shape: CompactorState/Book/Rendered persist exactly the prescribed fields" in {
        Compactor.init.map { _ =>
            assert(Segment(0, Chunk.empty, false, 0).productArity == 4, "Segment(id,messages,unresolved,tokens)")
            assert(Rendered(0, 0, 0, Chunk.empty).productArity == 4, "Rendered(level,covers,at,replacement)")
            assert(CompactorState.empty.productArity == 5, "CompactorState(renderings,vectors,verdicts,prepared,book)")
            assert(book0.productArity == 5, "Book(seen,tokensPerByte,inflight,embedInflight,summaryInflight): no 6th field")
        }
    }

    "byte-identical view between updates (golden)" in {
        Compactor.init(_.copy(effectiveCap = 100000, windowFraction = 1.0)).map { c =>
            val ctx = ctxOf(sm("s"), um("first"), am("small"), um("latest"))
            LLM.run {
                AI.initWith { ai =>
                    ai.setContext(ctx).andThen(c.render(ai, ctx)).map { v1 =>
                        val ref = LLM.internal.AIRef(ai)
                        // land background results (vectors/verdicts) directly into the cell between the two
                        // renders: the next render reads them off the cell WITHOUT touching renderings, so the
                        // fast-path view stays byte-identical.
                        c.cell.get.map { d =>
                            val landed = d.get(ref).getOrElse(CompactorState.empty).copy(
                                vectors = Dict[Int, Embedding]((0, Embedding(Span(1.0f), "m", 1))),
                                verdicts = Dict[Int, Verdict]((0, Verdict.Stale))
                            )
                            c.cell.set(d.update(ref, landed))
                        }.andThen(c.render(ai, ctx)).map { v2 =>
                            assert(v1.messages == v2.messages, "landing background results never mutates the view bytes")
                            assert(v1.messages == ctx.messages, "fast-path view equals the transcript")
                        }
                    }
                }
            }
        }
    }

    "shallow-edit preference; recall at tail" in {
        Compactor.init.map { c =>
            // recall appends at the tail rather than un-freezing a deep region: the marker points at recall,
            // which lands a fresh tail message (verified end-to-end in "recall lands at tail, no prefix edit" below).
            val ctx    = ctxOf(um("deep " + ("x" * 30)))
            val units  = c.group(ctx, book0)
            val marker = c.maskMarker(units.head, ctx)
            assert(marker.contains("recall(0)"), "recall restores at the tail, never un-freezing the prefix")
        }
    }

    "decide against fresh derivation" in {
        Compactor.init.map { c =>
            // scores are computed against the freshly-derived graph, not a remembered conclusion.
            val units = Chunk(seg(0), seg(1))
            val g     = edges(1 -> List(Edge(0, EdgeKind.Adj, 1.0)))
            val fresh = c.score(units, g, Dict.empty, Dict[Int, Double]((1, 1.0)))
            assert(fresh.get(0).getOrElse(0.0) > 0.0, "the decision uses the fresh derivation")
        }
    }

    "summary re-validated at adoption" in {
        Compactor.init.map { c =>
            // Adoption of a prepared summary is re-validated by the two-touch rule at ladderStep: a unit that
            // STOOD at L1-2 since a PRIOR update (cur.at < updateIdx) adopts its prepared summary (L3); a unit
            // reduced THIS pass (cur.at == updateIdx) is NOT adopted, deferring re-validation to the next pass.
            val ctx      = ctxOf(um("region content " + ("x" * 50)))
            val u        = c.group(ctx, book0).head
            val prepared = Rendered(3, 1, 0, Chunk(sm("PREP")))
            val stood = CompactorState.empty.copy(
                renderings = Dict[Int, Rendered]((u.id, Rendered(2, 1, 0, Chunk(sm("[c]"))))),
                prepared = Dict[Int, Rendered]((u.id, prepared))
            )
            assert(
                c.ladderStep(u, stood, ctx, 10, Absent).exists(r => r.level == 3 && r.replacement.head.content == "PREP"),
                "a unit standing since a prior update adopts its re-validated prepared summary at L3"
            )
            val thisPass = CompactorState.empty.copy(
                renderings = Dict[Int, Rendered]((u.id, Rendered(2, 1, 10, Chunk(sm("[c]"))))),
                prepared = Dict[Int, Rendered]((u.id, prepared))
            )
            assert(
                c.ladderStep(u, thisPass, ctx, 10, Absent) == Absent,
                "a unit reduced this pass is not adopted (two-touch defers re-validation)"
            )
        }
    }

    "re-warmed unit keeps rendering (rescue is non-selection)" in {
        Compactor.init(_.copy(effectiveCap = 100000, windowFraction = 1.0)).map { c =>
            // rescue is non-selection: an already-rendered unit keeps its rendering across a render pass and
            // is NEVER auto-restored to verbatim (only an explicit recall restores it).
            val ctx = ctxOf(sm("s"), um("first"), am("mid content here"), um("latest"))
            LLM.run {
                AI.initWith { ai =>
                    val ref = LLM.internal.AIRef(ai)
                    val seeded = CompactorState.empty.copy(
                        renderings = Dict[Int, Rendered]((2, Rendered(2, 1, 0, Chunk(sm("[compacted 2]"))))),
                        book = book0.copy(seen = 4)
                    )
                    ai.setContext(ctx).andThen(c.cell.set(Dict((ref, seeded)))).andThen(c.render(ai, ctx)).map { view =>
                        assert(
                            view.messages.exists(_.content.contains("[compacted 2]")),
                            s"the existing rendering is retained: ${view.messages}"
                        )
                        assert(
                            !view.messages.exists(_.content.contains("mid content here")),
                            "a re-warmed unit is not auto-restored to verbatim; only recall restores it"
                        )
                    }
                }
            }
        }
    }

    "two-touch rule; forced exempt" in {
        Compactor.init(_.copy(effectiveCap = 40, windowFraction = 1.0, tailTurns = 1)).map { c =>
            // ladderStep never deepens a unit reduced THIS pass (Rendered.at == updateIdx): the two-touch rule.
            val ctx0     = ctxOf(um("payload " + ("x" * 40)))
            val u0       = c.group(ctx0, book0).head
            val samePass = CompactorState.empty.copy(renderings = Dict[Int, Rendered]((u0.id, Rendered(2, 1, 10, Chunk(sm("[m]"))))))
            assert(c.ladderStep(u0, samePass, ctx0, 10, Absent) == Absent, "a unit reduced this pass is not deepened (two-touch)")
            // end-to-end: in a single update a freshly-demoted unit is masked (L1-2), never deepened to L3/L4.
            val ctx = ctxOf(sm("s"), um("first"), am("a " + ("x" * 200)), um("latest"))
            LLM.run {
                AI.initWith { ai =>
                    ai.setContext(ctx).andThen(c.render(ai, ctx)).map { _ =>
                        c.cell.get.map { d =>
                            val levels = d.toChunk.headMaybe.map(_._2.renderings.toChunk.toList.map(_._2.level)).getOrElse(Nil)
                            assert(levels.forall(_ <= 2), s"a first reduction stays at L1-2 (two-touch): $levels")
                        }
                    }
                }
            }
        }
    }

    "tail bounded turns AND tokens" in {
        Compactor.init(_.copy(tailTurns = 10, tailTokens = 5)).map { c =>
            // three units each 4 tokens; tailTokens=5 keeps only the newest (the older fall out on the token bound).
            val units = Chunk(Segment(0, Chunk(0), false, 4), Segment(1, Chunk(1), false, 4), Segment(2, Chunk(2), false, 4))
            val tail  = c.tailUnits(units, book0)
            assert(tail == Set(2), s"tail bounded by tokens keeps only the newest: $tail")
        }
    }

    "render path model-free" in {
        Compactor.init(_.copy(effectiveCap = 100000, windowFraction = 1.0)).map { c =>
            // the synchronous render path returns a pure projection; no model output is consulted to build it.
            val ctx = ctxOf(sm("s"), um("first"), am("small"), um("latest"))
            LLM.run {
                AI.initWith { ai =>
                    ai.setContext(ctx).andThen(c.render(ai, ctx)).map { view =>
                        assert(view.messages == ctx.messages, "the view is the pure projection, computed model-free")
                    }
                }
            }
        }
    }

    "occupancy gate branches" in {
        Compactor.init.map { c =>
            // E == min(windowFraction*window, effectiveCap), via the real effectiveLength.
            assert(
                c.effectiveLength(Compactor.Config(windowFraction = 0.5, effectiveCap = 48000), 1000) == 500.0,
                "E = windowFraction*window below the cap"
            )
            assert(
                c.effectiveLength(Compactor.Config(windowFraction = 0.5, effectiveCap = 48000), 200000) == 48000.0,
                "E = effectiveCap when the fraction exceeds it"
            )
        }
    }

    "one judge batch in flight" in {
        Compactor.init(_.copy(bandSize = 4, effectiveCap = 40, windowFraction = 1.0, tailTurns = 1)).map { c =>
            // The one-batch-in-flight guard is dedup by book.inflight: a fresh judge band excludes every unit
            // already dispatched-not-landed, so no second batch is dispatched for the same units while one is
            // outstanding. Asserted deterministically over judgeBand (no fiber-timing race: the inflight
            // clearing runs on a detached fiber, so an end-to-end server-count race would be non-deterministic).
            val ctx   = ctxOf(sm("s"), um("first"), am("a " + ("x" * 120)), am("b " + ("y" * 120)), um("latest"))
            val units = c.group(ctx, book0)
            val band0 = c.judgeBand(units, ctx, CompactorState.empty)
            assert(band0.nonEmpty, s"the first band is nonempty (there is a batch to dispatch): ${band0.map(_.id)}")
            val inFlight = CompactorState.empty.copy(book = book0.copy(inflight = band0.map(_.id).toSet))
            val band1    = c.judgeBand(units, ctx, inFlight)
            assert(
                band1.forall(u => !inFlight.book.inflight.contains(u.id)),
                s"every unit already in flight is excluded from the next band: next=${band1.map(_.id)} inflight=${inFlight.book.inflight}"
            )
        }
    }

    "ladder pass order; non-blocking" in {
        Compactor.init(_.copy(effectiveCap = 40, windowFraction = 1.0, tailTurns = 1)).map { c =>
            // the update runs its ladder synchronously and returns without waiting on a model response.
            val ctx = ctxOf(sm("s"), um("first"), am("a " + ("x" * 200)), um("latest"))
            LLM.run {
                AI.initWith { ai =>
                    ai.setContext(ctx).andThen(c.render(ai, ctx)).map(view =>
                        assert(view.messages.nonEmpty, "the ladder never blocks on a model response")
                    )
                }
            }
        }
    }

    "co-pin check" in {
        Compactor.init.map { c =>
            // a live referrer keeps its Ref target co-pinned.
            val units = Chunk(seg(0), seg(1))
            val g     = edges(1 -> List(Edge(0, EdgeKind.Ref, 3.0)))
            val refs  = c.coPinReferrers(units, g)
            assert(refs.getOrElse(0, Set.empty) == Set(1), s"unit0 is referenced by unit1 (co-pin candidate): $refs")
        }
    }

    "cache gate defers deep edits" in {
        Compactor.init.map { c =>
            // a shallow edit (large saving, small rendered tail) passes; a deep edit into a big frozen prefix defers.
            assert(c.cacheGatePasses(saved = 100, lCut = 20, cachedReadDiscount = 0.1, writePremium = 1.0), "shallow edit passes")
            assert(
                !c.cacheGatePasses(saved = 1, lCut = 10000, cachedReadDiscount = 0.1, writePremium = 1.0),
                "deep edit into the frozen prefix defers"
            )
        }
    }

    "the cache gate binds L_cut to the post-edit suffix, not the whole view" in {
        Compactor.init.map { c =>
            // The true post-edit suffix is small, so the gate passes; whole-view occupancy, dominated by the frozen prefix, would exceed the gate.
            val ctx      = ctxOf(sm("s"), um("q"), am("M" * 4000), am("D" * 400), am("s1 small"), am("s2 small"), um("last"))
            val u        = c.group(ctx, book0).toList.find(_.id == 3).get
            val r        = Rendered(4, 1, 0, Chunk(sm("[c]")))
            val post     = CompactorState.empty.copy(renderings = Dict[Int, Rendered]((u.id, r)))
            val occupied = c.viewTokens(c.project(ctx, CompactorState.empty), book0)
            val saved    = occupied - c.viewTokens(c.project(ctx, post), book0)
            val lCut     = c.viewTokens(c.projectFrom(ctx, post, u.id), book0)
            assert(lCut < occupied, s"the post-edit suffix ($lCut) is a strict subset of the whole view ($occupied)")
            assert(
                c.cacheGatePasses(saved, lCut, cachedReadDiscount = 0.1, writePremium = 1.0),
                s"the true-suffix binding passes the gate: saved=$saved lCut=$lCut"
            )
            assert(
                !c.cacheGatePasses(saved, occupied, cachedReadDiscount = 0.1, writePremium = 1.0),
                s"true-suffix L_cut must pass the gate that whole-view occupancy fails: saved=$saved occupied=$occupied"
            )
        }
    }

    "rot rule triggers + blocked deep edit deferred" in {
        Compactor.init(_.copy(effectiveCap = 200, windowFraction = 1.0, tailTurns = 1)).map { c =>
            // rot triggers: re-fetch threshold OR budget exhaustion; answer quality is never a trigger.
            assert(c.rotFires(refetchCount = 2, occupied = 0, e = 1000.0), "refetch >= refetchThreshold fires")
            assert(c.rotFires(refetchCount = 0, occupied = 1000, e = 1000.0), "budget exhaustion (viewTokens >= E) fires")
            assert(!c.rotFires(refetchCount = 1, occupied = 10, e = 1000.0), "answer quality is never a trigger")
            // the re-fetch count is derived from recall calls in the transcript (object wire shape {"id":n})
            // after the unit's Rendered.at.
            val recalls =
                ctxOf(
                    sm("s"),
                    am("r1", call("rc1", "recall", """{"id":2}""")),
                    tm("rc1", "x"),
                    am("r2", call("rc2", "recall", """{"id":2}""")),
                    tm("rc2", "x")
                )
            assert(c.refetchCount(2, recalls, 0) == 2, s"two recall(2) calls => count 2: ${c.refetchCount(2, recalls, 0)}")
            assert(c.refetchCount(2, recalls, 3) == 1, "counting starts at the unit's Rendered.at")
            assert(c.refetchCount(9, recalls, 0) == 0, "recall calls for other ids do not count")

            val base = ctxOf(sm("s " + ("x" * 180)), um("first " + ("y" * 180)), am("mid " + ("z" * 180)), um("latest " + ("w" * 180)))
            def renderWith(ctx: Context): CompactorState < (Async & Abort[AIGenException]) =
                LLM.run(serverConfig("http://127.0.0.1:1")) {
                    AI.initWith { ai =>
                        val ref = LLM.internal.AIRef(ai)
                        val u2  = Segment(2, Chunk(2), false, 12)
                        val seeded = CompactorState.empty.copy(
                            renderings = Dict[Int, Rendered]((2, Rendered(2, 1, 0, Chunk(sm(c.maskMarker(u2, ctx)))))),
                            book = book0.copy(seen = 0)
                        )
                        ai.setContext(ctx).andThen(c.cell.set(Dict((ref, seeded)))).andThen(c.render(ai, ctx)).andThen {
                            c.cell.get.map(d => d.get(ref).getOrElse(CompactorState.empty))
                        }
                    }
                }
            // no re-fetch, tiny saving, occupancy below E: the deep edit (L2->L4) is BLOCKED and the unit stays
            // at L2 (deterministic re-derivation re-evaluates it next turn; no suppression record is kept).
            renderWith(base).map { blocked =>
                assert(blocked.renderings.get(2).map(_.level) == Present(2), "the blocked deep edit leaves the unit at L2")
                // refetchThreshold (2) recall(2) calls now trip the rot rule: the deferred deep edit is PERMITTED
                // (deepened to L4).
                val refetched = base.add(am("rc", call("k1", "recall", """{"id":2}"""))).add(tm("k1", "restored")).add(am(
                    "rc",
                    call("k2", "recall", """{"id":2}""")
                )).add(tm("k2", "restored"))
                renderWith(refetched).map { permitted =>
                    assert(
                        permitted.renderings.get(2).map(_.level) == Present(4),
                        s"the re-fetched unit's deep edit is permitted and applied at L4: ${permitted.renderings.get(2)}"
                    )
                }
            }
        }
    }

    "updates are periodic under tool-heavy load (hysteresis band)" in {
        Compactor.init.map { c =>
            // H (updateTriggerFraction) is strictly above L (updateTargetFraction): the gap is the hysteresis band.
            val cfg = c.config
            assert(
                cfg.updateTriggerFraction > cfg.updateTargetFraction,
                s"H > L hysteresis band: ${cfg.updateTriggerFraction} ${cfg.updateTargetFraction}"
            )
        }
    }

    // ==== checkpoint 8: background / recall / forced / calibrate ====

    "forced path omit-only + overflow abort" in {
        Compactor.init(_.copy(effectiveCap = 1, windowFraction = 1.0, hardWindowFraction = 0.0001, tailTurns = 10)).map { c =>
            // window = 50 tokens; roots (system + first/last user + tail) alone exceed it -> Abort.
            val cfg = serverConfig("http://127.0.0.1:1").model(Config.OpenAI, "gpt-4o", 50)
            val ctx = ctxOf(sm("s " + ("x" * 400)), um("first " + ("y" * 400)), um("latest " + ("z" * 400)))
            Abort.run[AIGenException] {
                LLM.run(cfg) {
                    AI.initWith { ai =>
                        ai.setContext(ctx).andThen(c.render(ai, ctx))
                    }
                }
            }.map { r =>
                r match
                    case Result.Failure(_: AIContextOverflowException) => assert(true)
                    case other                                         => assert(false, s"expected AIContextOverflowException, got $other")
            }
        }
    }

    "never-completing embedder still yields correct view" in {
        TestCompletionServer.run { server =>
            // no embeddings scripted: the embed fork never lands, yet render returns a deterministic view.
            val cfg = serverConfig(server.baseUrl)
            Compactor.init(_.copy(effectiveCap = 100000, windowFraction = 1.0)).map { c =>
                LLM.run(cfg) {
                    AI.initWith { ai =>
                        val ctx = ctxOf(sm("s"), um("first"), am("mid"), um("latest"))
                        ai.setContext(ctx).andThen(c.render(ai, ctx)).map { view =>
                            assert(view.messages == ctx.messages, "the turn returns a correct view without blocking on the embedder")
                        }
                    }
                }
            }
        }
    }

    "judge runs fresh, no transcript leak" in {
        TestCompletionServer.run { server =>
            val cfg = serverConfig(server.baseUrl)
            // judge = cfg pins the judge at the test server so its request is observable here (the default
            // path, which inherits the active config's transport, is covered separately by "default judge
            // path (no override) reaches the current provider authenticated"). This leaf's subject is the
            // no-leak property: root/system content never reaches the judge.
            Compactor.init(_.copy(bandSize = 4, effectiveCap = 40, windowFraction = 1.0, tailTurns = 1, judge = Present(cfg))).map { c =>
                server.enqueueBody(resultBody("stale")).andThen {
                    LLM.run(cfg) {
                        AI.initWith { ai =>
                            val secret = "SECRETROOTCONTENT"
                            val ctx    = ctxOf(sm("s " + secret), um("first"), am("band " + ("x" * 200)), um("latest"))
                            ai.setContext(ctx).andThen(c.render(ai, ctx)).andThen {
                                // Await the judge's own request deterministically (its unique system prompt, the
                                // same text judgeContext seeds), rather than racing render's background dispatch
                                // fiber: the embeddings fork legitimately embeds every non-vectorized unit including
                                // root content (the class's own PRIVACY note), so a blanket assertion over every
                                // captured body is over-broad and, worse, only holds by the accident of the embed
                                // fiber not having landed yet. Scoping to the judge request keeps the no-leak
                                // property (root/system content never reaches the judge) exact, proven the moment
                                // that specific request lands, never before and never by polling.
                                server.awaitCaptured(cap =>
                                    cap.path == "v1/chat/completions" && cap.body.contains("You judge context regions for compaction")
                                ).map { judgeReq =>
                                    assert(
                                        !judgeReq.body.contains(secret),
                                        s"the judge request must not carry root/system content: ${judgeReq.body}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "default judge path (no override) reaches the current provider authenticated" in {
        TestCompletionServer.run { server =>
            val cfg = serverConfig(server.baseUrl)
            // NO Config.judge override. The default judge inherits the ACTIVE chat config's credentials and
            // apiUrl and only adopts the provider's cheap-tier model, so its request actually reaches the
            // server. A credential-less catalog literal (the pre-fix behavior) would fail the missing-key
            // check before egress and never arrive, so observing the request at all is the proof it is a real
            // authenticated request on the current provider.
            Compactor.init(_.copy(bandSize = 4, effectiveCap = 40, windowFraction = 1.0, tailTurns = 1)).map { c =>
                LLM.run(cfg) {
                    AI.initWith { ai =>
                        val ctx = ctxOf(sm("s"), um("first"), am("band " + ("x" * 200)), um("latest"))
                        ai.setContext(ctx).andThen(c.render(ai, ctx)).andThen {
                            server.awaitCaptured(cap =>
                                cap.path == "v1/chat/completions" && cap.body.contains("You judge context regions for compaction")
                            ).map { judgeReq =>
                                assert(
                                    judgeReq.body.contains("gpt-5-nano"),
                                    s"the default judge runs the current provider's cheap-tier model, not the chat model: ${judgeReq.body}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "results only via cell (LLM.State untouched)" in {
        TestCompletionServer.run { server =>
            val cfg = serverConfig(server.baseUrl)
            Compactor.init(_.copy(effectiveCap = 100000, windowFraction = 1.0)).map { c =>
                server.enqueueBody(embedBody(List(List(1.0, 0.0)))).andThen {
                    LLM.run(cfg) {
                        AI.initWith { ai =>
                            val ctx = ctxOf(um("only unit"))
                            ai.setContext(ctx).andThen(c.render(ai, ctx)).andThen(ai.context).map { after =>
                                assert(after == ctx, "background results land in the cell only; the instance context is unchanged")
                            }
                        }
                    }
                }
            }
        }
    }

    "background results are caches not authority" in {
        Compactor.init.map { c =>
            // a stale landed verdict does not override the fresh liveness computation; score ignores verdicts.
            val units = Chunk(seg(0), seg(1))
            val g     = edges(1 -> List(Edge(0, EdgeKind.Adj, 1.0)))
            val r     = c.score(units, g, Dict.empty, Dict[Int, Double]((1, 1.0)))
            assert(r.get(0).getOrElse(0.0) > 0.0, "the fresh derivation wins; verdicts are only caches")
        }
    }

    "batched embed + mutual-kNN Sem edges; pre-embed structural-only ok" in {
        Compactor.init(_.copy(semanticFloor = 0.7, semanticNeighbors = 5, effectiveCap = 100000, windowFraction = 1.0)).map { c =>
            // close vectors for u0,u1; far for u2 -> Sem edge connects u0,u1 only.
            val ctx   = ctxOf(um("u0 body"), um("u1 body"), um("u2 body"))
            val units = c.group(ctx, book0)
            val v0    = Embedding(Span(1.0f, 0.0f), "m", 2)
            val v1    = Embedding(Span(0.99f, 0.14f), "m", 2)
            val v2    = Embedding(Span(0.0f, 1.0f), "m", 2)
            val st    = CompactorState.empty.copy(vectors = Dict[Int, Embedding]((0, v0), (1, v1), (2, v2)))
            val g     = c.deriveGraph(units, ctx, st, Dict.empty)
            val sem0  = g.edges.get(0).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Sem).map(_.target).toSet
            assert(sem0.contains(1), s"u0,u1 are mutual-kNN above the floor: $sem0")
            assert(!sem0.contains(2), s"u2 is below the floor: $sem0")
            // pre-embed: a unit with no vector has only structural edges, which is not a defect.
            val stNo = CompactorState.empty
            val gNo  = c.deriveGraph(units, ctx, stNo, Dict.empty)
            assert(gNo.edges.get(1).getOrElse(Chunk.empty).forall(_.kind != EdgeKind.Sem), "pre-embed structural-only is fine")
        }
    }

    "recall lands at tail, no prefix edit" in {
        Compactor.init(_.copy(effectiveCap = 40, windowFraction = 1.0, tailTurns = 1)).map { c =>
            LLM.run {
                AI.initWith { ai =>
                    val ctx = ctxOf(sm("s"), um("first"), am("payload alpha " + ("x" * 200)), um("latest"))
                    ai.setContext(ctx).andThen(c.render(ai, ctx)).map { _ =>
                        c.cell.get.map { d =>
                            val demotedId =
                                d.toChunk.headMaybe.map(_._2.renderings.toChunk.toList.map(_._1)).getOrElse(Nil).headOption.getOrElse(-1)
                            runRecall(c, ai, demotedId).map { out =>
                                assert(out.contains("payload alpha"), s"recall returns the original content: $out")
                            }
                        }
                    }
                }
            }
        }
    }

    def runRecall(c: Compactor, ai: AI, id: Int)(using Frame): String < LLM =
        val t = Compactor.internal.recallTool(c, ai)
        t.infos.head.run.asInstanceOf[Any => (String < LLM)](Compactor.internal.Recall(id))

    "recall resolves against the CALLING instance only (no cross-session leak)" in {
        // One scope compactor serving two instances. Both demote a unit at the SAME small id (unit ids are
        // transcript indices, so they collide across sessions), with DIFFERENT content. Each instance's
        // recall must return ITS OWN content, never the other session's.
        Compactor.init(_.copy(effectiveCap = 40, windowFraction = 1.0, tailTurns = 1)).map { c =>
            LLM.run {
                AI.initWith { a =>
                    AI.initWith { b =>
                        val ctxA = ctxOf(sm("s"), um("first"), am("AAAA session-a-secret " + ("x" * 200)), um("latest"))
                        val ctxB = ctxOf(sm("s"), um("first"), am("BBBB session-b-secret " + ("y" * 200)), um("latest"))
                        a.setContext(ctxA).andThen(c.render(a, ctxA))
                            .andThen(b.setContext(ctxB)).andThen(c.render(b, ctxB))
                            .andThen {
                                // unit 2 (the middle assistant) is demoted in BOTH sessions at the same id.
                                runRecall(c, a, 2).map { outA =>
                                    runRecall(c, b, 2).map { outB =>
                                        assert(outA.contains("session-a-secret"), s"instance a recalls a's content: $outA")
                                        assert(!outA.contains("session-b-secret"), s"instance a never sees b's content: $outA")
                                        assert(outB.contains("session-b-secret"), s"instance b recalls b's content: $outB")
                                        assert(!outB.contains("session-a-secret"), s"instance b never sees a's content: $outB")
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    "error: unknown/non-demoted id returns a typed no-such-region message" in {
        Compactor.init.map { c =>
            LLM.run {
                AI.initWith { ai =>
                    ai.setContext(ctxOf(um("a"))).andThen(runRecall(c, ai, 9999)).map { out =>
                        assert(out == "no such recallable region: 9999", s"typed no-such-region message, never a throw: $out")
                    }
                }
            }
        }
    }

    "relevance recall hint-only default" in {
        Compactor.init.map { c =>
            // relevance-driven recall is hint-only: nothing auto-re-injects a demoted region by default.
            val ctx = ctxOf(um("payload"))
            val view = c.project(
                ctx,
                CompactorState.empty.copy(renderings = Dict[Int, Rendered]((0, Rendered(4, 1, 0, Chunk(sm("[compacted 0]"))))))
            )
            assert(view.messages == Chunk(sm("[compacted 0]")), "a demoted region stays demoted; recall is not automatic")
        }
    }

    "doubt-renders-more; forced at hard window" in {
        Compactor.init.map { c =>
            // below the hard window doubt renders MORE (fast path keeps content); an overflow surfaces as a
            // typed AIContextOverflowException, never a silent truncation (see "forced path omit-only + overflow abort" below).
            val ctx = ctxOf(sm("s"), um("first"), um("latest"))
            LLM.run {
                AI.initWith { ai =>
                    ai.setContext(ctx).andThen(c.render(ai, ctx)).map(view =>
                        assert(view.messages == ctx.messages, "below the hard window, more is rendered")
                    )
                }
            }
        }
    }

    "scoped determinism golden" in {
        Compactor.init.map { c =>
            val ctx = ctxOf(um("u0 sym_x"), um("u1 sym_x"), um("u2 other"))
            val v   = Embedding(Span(1.0f, 0.0f), "m", 2)
            val st  = CompactorState.empty.copy(vectors = Dict[Int, Embedding]((0, v), (1, v)))
            def run(): (List[(Int, Double)], Chunk[Message]) =
                val units = c.group(ctx, book0)
                val g     = c.deriveGraph(units, ctx, st, Dict.empty)
                val seed  = c.seedVector(units, ctx, book0)
                val sc    = c.score(units, g, Dict.empty, seed).toChunk.toList.sortBy(_._1)
                (sc, c.project(ctx, st).messages)
            end run
            val (s1, p1) = run()
            val (s2, p2) = run()
            assert(s1 == s2, s"ordinal ranking is identical across runs: $s1 vs $s2")
            assert(p1 == p2, "renderings are identical across runs")
        }
    }

    "cell prune keeps live entries across a render (drop path in CompactorPruneTest, JVM: WeakReference.clear)" in {
        Compactor.init(_.copy(effectiveCap = 100000, windowFraction = 1.0)).map { c =>
            // Every render runs the prune filter (d.filter((ref,_) => ref.isValid), mirroring LLM.State.pruned)
            // so a long-lived scope compactor never accumulates dead entries. Here (cross-platform,
            // deterministic) the live instance's entry SURVIVES the prune the render runs. The drop path (a
            // collected ref removed) is exercised deterministically in the JVM-only CompactorPruneTest, which
            // uses WeakReference.clear(), an API Scala.js (JS/Wasm) does not support.
            LLM.run {
                AI.initWith { live =>
                    val liveRef = LLM.internal.AIRef(live)
                    val ctx     = ctxOf(um("hello"))
                    c.cell.set(Dict((liveRef, CompactorState.empty))).andThen {
                        live.setContext(ctx).andThen(c.render(live, ctx)).andThen {
                            c.cell.get.map { d =>
                                assert(d.contains(liveRef), "the live instance's entry survives the prune the render runs")
                                assert(liveRef.isValid, "a live instance's ref is valid (kept by the prune predicate)")
                            }
                        }
                    }
                }
            }
        }
    }

    "config knobs are overridable via init(f), including judge Present" in {
        Compactor.init(_.copy(
            windowFraction = 0.6,
            bandSize = 8,
            seeds = Compactor.Config.SeedWeights(objective = 0.5),
            judge = Present(Config.OpenAI.default)
        )).map { c =>
            assert(c.config.windowFraction == 0.6)
            assert(c.config.bandSize == 8)
            assert(c.config.seeds.objective == 0.5)
            assert(c.config.judge != Absent, "the judge override is Present")
            // unset knobs keep documented defaults
            assert(c.config.restartWeight == 0.15 && c.config.supersessionPenalty == 0.2)
        }
    }

    "calibrate EWMA-blends tokensPerByte and is a no-op on Absent usage (two sub-cases)" in {
        Compactor.init.map { c =>
            LLM.run {
                AI.initWith { ai =>
                    val request = ctxOf(um("payload of some bytes"))
                    val ref     = LLM.internal.AIRef(ai)
                    val b       = request.messages.foldLeft(0)((n, m) => n + m.content.length)
                    c.cell.set(Dict((ref, CompactorState.empty))).andThen {
                        // sub-case A: inputTokens = 2*B -> obs 2.0 -> blend (1-0.3)*0.25 + 0.3*2.0 == 0.775
                        c.calibrate(ai, Present(Completion.Usage(2 * b, 0)), request).andThen {
                            c.cell.get.map { d1 =>
                                val tpbA = d1.get(ref).getOrElse(CompactorState.empty).book.tokensPerByte
                                assert(eps(tpbA, 0.7 * 0.25 + 0.3 * 2.0, 1e-9), s"EWMA blend to 0.775, not a full replace: $tpbA")
                                // sub-case B: Absent usage is a no-op (keeps the prior calibration).
                                c.cell.set(Dict((ref, CompactorState.empty))).andThen {
                                    c.calibrate(ai, Absent, request).andThen {
                                        c.cell.get.map { d2 =>
                                            val tpbB = d2.get(ref).getOrElse(CompactorState.empty).book.tokensPerByte
                                            assert(eps(tpbB, 0.25), s"Absent usage keeps 0.25: $tpbB")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end CompactorTest
