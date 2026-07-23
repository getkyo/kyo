package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Context.*

/** The analysis pass mechanics: the typed decode and its five load-bearing properties
  * (backward-only, capped, reachable-target-only, no weights, no summary), the hostile-input
  * drop-not-throw pairing, the two semantic edge kinds, keyless supersession, the event-driven
  * low-water cadence, and the analysis-failure degrade. Deterministic throughout: the analysis wire is
  * scripted through TestCompletionServer, occupancy sits below the fill trigger so only the analysis
  * call hits the server, and every wait is an async suspension (Channel/Fiber), never a sleep.
  */
class CompactorAnalysisTest extends kyo.test.Test[Any]:

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def tok(m: Message, n: Int): Message              = stamp(m, TokenStamp("t", n))
    def reg(id: Int, tokens: Int = 1): Region         = Region(id, Chunk(id), false, tokens)

    def graphOf(es: (Int, List[Edge])*): Graph =
        Graph(Dict.from(es.map((k, v) => (k, Chunk.from(v))).toMap))

    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    def cfg(window: Int = 16384): Config =
        Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    // Wraps a decoded Analysis as the assistant content of a scripted OpenAI completion body: the pass
    // reads reply.messages.head.content and decodes THAT as the typed Analysis.
    def analysisReply(a: Analysis): String =
        val content = Json.encode(a)
        val esc     = content.replace("\\", "\\\\").replace("\"", "\\\"")
        s"""{"choices":[{"message":{"role":"assistant","content":"$esc"}}]}"""
    end analysisReply

    // A snapshot whose seeded head regions (system + first task turn) age below the tail band (closed) as
    // ten recent turns fill the tail band, while occupancy stays under the fill trigger. The head regions
    // are pinned (seeded), so no span is demotable and fillNeed is empty: only the ONE analysis call
    // reaches the server per arming event, and analysisPending names the closed head regions.
    def closedCtx(): Context =
        val head = Chunk[Message](sm("system prompt"), um("the first task question"))
        val mids = (0 until 10).map(i => tok(am(s"recent turn $i"), 30))
        Context(head.concat(Chunk.from(mids)))
    end closedCtx

    // ==== the two semantic edge kinds ====

    "analyzedEdges maps DependsOn->Dependency(3.0), Relates->Relatedness(0.5), Supersedes->no edge, backward-only" in {
        val analyses =
            Chunk(RegionAnalysis(
                9,
                Chunk(Relation(2, RelationKind.DependsOn), Relation(5, RelationKind.Relates), Relation(1, RelationKind.Supersedes))
            ))
        val edges = Default.analyzedEdges(analyses)
        assert(
            edges == Chunk((9, 2, EdgeKind.Dependency), (9, 5, EdgeKind.Relatedness)),
            "DependsOn and Relates mint edges; Supersedes mints none"
        )
        assert(Default.analyzedSupersession(analyses).get(1) == Present(9), "Supersedes marks region 1 superseded by region 9")
        val raw   = Chunk.from((0 to 9).map(i => am(s"region $i unique$i")))
        val units = Default.group(raw)
        val g     = Default.deriveGraph(units, raw, Dict.empty, edges)
        val e9    = g.edges.get(9).getOrElse(Chunk.empty)
        assert(
            e9.exists(e => e.target == 2 && e.kind == EdgeKind.Dependency && eps(e.weight, dependencyWeight)),
            "the Dependency edge 9->2 carries weight 3.0"
        )
        assert(
            e9.exists(e => e.target == 5 && e.kind == EdgeKind.Relatedness && eps(e.weight, relatednessWeight)),
            "the Relatedness edge 9->5 carries weight 0.5"
        )
        assert(edges.forall((from, target, _) => target < from), "every analyzed edge points backward")
    }

    "parseAnalysis caps at relationCap, keeps only backward in-reach relations, drops out-of-reach members' relations" in {
        // region 20: six backward relations (targets 1,2,3,4,5,6); target 3 is pointer-level (out of reach).
        // region 8: one FORWARD relation (target 12, target > ordinal).
        val a =
            Analysis(
                Chunk(
                    RegionAnalysis(
                        20,
                        Chunk(
                            Relation(1, RelationKind.Relates),
                            Relation(2, RelationKind.Relates),
                            Relation(3, RelationKind.Relates),
                            Relation(4, RelationKind.Relates),
                            Relation(5, RelationKind.Relates),
                            Relation(6, RelationKind.Relates)
                        )
                    ),
                    RegionAnalysis(8, Chunk(Relation(12, RelationKind.DependsOn)))
                )
            )
        // reachable EXCLUDES the pointer-level ordinal 3 and the out-of-range 12.
        val valid  = Set(1, 2, 4, 5, 6, 8, 20)
        val parsed = Default.parseAnalysis(Json.encode(a), valid)
        assert(parsed.size == 2, "both listed members whose ordinal is in reach survive")
        val r20 = parsed.filter(_.ordinal == 20).head
        assert(
            r20.relations.map(_.target) == Chunk(1, 2, 4, 5),
            "target 3 dropped as out-of-reach; first relationCap (4) of the rest kept in emission order"
        )
        assert(r20.relations.size == Default.relationCap, "capped at relationCap")
        val r8 = parsed.filter(_.ordinal == 8).head
        assert(r8.relations.isEmpty, "region 8's forward relation is dropped, leaving the member with zero relations")
    }

    // ==== the typed decode + write-once staging ====

    "a well-formed Analysis decodes, stages write-once by ordinal, and round-trips through Schema" in {
        TestCompletionServer.run { server =>
            val ctx     = closedCtx()
            val config  = cfg().apiUrl(server.baseUrl)
            val pending = Default.analysisPending(ctx, config)
            val units   = Default.group(ctx.raw)
            val spans   = Default.formSpans(units, ctx.raw, config)
            val reach   = Default.analysisReach(units, spans, Dict.empty, Default.tailUnits(units))
            val reply   = Analysis(pending.map(u => RegionAnalysis(u.id, Chunk.empty)))
            server.enqueueBody(analysisReply(reply)).andThen(server.enqueueBody(analysisReply(reply))).andThen {
                Preparation.init.map { prep =>
                    Default.runAnalysis(ctx, pending, config, prep, reach).andThen {
                        prep.staged.get.map { staged1 =>
                            assert(pending.nonEmpty, "the snapshot has closed pending regions to analyze")
                            assert(pending.forall(u => staged1.analysisOf(u.id).isDefined), "every pending region stages by ordinal")
                            Default.runAnalysis(ctx, pending, config, prep, reach).andThen {
                                prep.staged.get.map { staged2 =>
                                    assert(
                                        pending.forall(u => staged2.analysisOf(u.id) == staged1.analysisOf(u.id)),
                                        "a re-run leaves the staged analyses untouched (write-once first-writer-wins)"
                                    )
                                    val ra =
                                        RegionAnalysis(9, Chunk(Relation(2, RelationKind.DependsOn), Relation(5, RelationKind.Relates)))
                                    val decoded = Json.decode[RegionAnalysis](Json.encode(ra))
                                    assert(
                                        decoded == Result.Success(ra),
                                        "the artifact round-trips through Schema (encode then decode == the original)"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "every malformed shape yields a dropped artifact, never a throw (parameterized)" in {
        val validEncoded = Json.encode(Analysis(Chunk(RegionAnalysis(9, Chunk(Relation(2, RelationKind.DependsOn))))))
        val valid        = Set(1, 2, 3, 4, 5, 6, 7, 8, 9, 20)
        // (a) malformed JSON, (e) unknown discriminator: whole-batch drop (decode failure).
        // (b) out-of-range target, (c) backward violation: per-relation drop, member survives.
        // (d) over-cap: keep the first relationCap.
        val overCap = Analysis(Chunk(RegionAnalysis(20, Chunk.from((1 to 6).map(t => Relation(t, RelationKind.Relates))))))
        val cases: List[(String, String, Chunk[RegionAnalysis] => Boolean)] =
            List(
                ("a malformed JSON", "{not valid json", _.isEmpty),
                ("e unknown discriminator", validEncoded.replace("DependsOn", "Mystery"), _.isEmpty),
                (
                    "b out-of-range target",
                    Json.encode(Analysis(Chunk(RegionAnalysis(9, Chunk(Relation(99, RelationKind.Relates)))))),
                    r => r.size == 1 && r.head.ordinal == 9 && r.head.relations.isEmpty
                ),
                (
                    "c backward violation",
                    Json.encode(Analysis(Chunk(RegionAnalysis(5, Chunk(Relation(7, RelationKind.Relates)))))),
                    r => r.size == 1 && r.head.ordinal == 5 && r.head.relations.isEmpty
                ),
                (
                    "d over-cap",
                    Json.encode(overCap),
                    r => r.size == 1 && r.head.relations.size == Default.relationCap
                )
            )
        cases.foldLeft(Kyo.unit) { (acc, c) =>
            val (name, input, check) = c
            acc.andThen {
                val parsed = Default.parseAnalysis(input, valid)
                assert(check(parsed), s"hostile shape [$name] drops correctly without throwing")
            }
        }
    }

    // ==== keyless supersession ====

    "a Supersedes relation penalizes the earlier region and repoints its edges (no compaction key)" in {
        val analyses = Chunk(RegionAnalysis(41, Chunk(Relation(14, RelationKind.Supersedes))))
        val keyless  = Default.analyzedSupersession(analyses)
        assert(keyless.get(14) == Present(41), "the keyless detector marks region 14 superseded by region 41")
        val merged = Default.mergeSupersession(Dict.empty[Int, Int], keyless)
        assert(merged.get(14) == Present(41), "mergeSupersession carries the keyless mark when the keyed map is empty")
        // the supersession penalty multiplies region 14's score by supersessionPenalty (0.2).
        val units = Chunk(reg(14), reg(15), reg(41))
        val g     = graphOf((15, List(Edge(14, EdgeKind.Reference, 1.0))))
        val seed  = Dict[Int, Double]((15, 1.0))
        val plain = Default.score(units, g, Dict.empty[Int, Int], seed)
        val pen   = Default.score(units, g, merged, seed)
        assert(
            eps(pen.get(14).getOrElse(0.0), plain.get(14).getOrElse(0.0) * supersessionPenalty),
            "region 14's score is multiplied by the supersession penalty"
        )
        // reference edges targeting the superseded region repoint to the superseding one.
        val raw = Chunk[Message](am("intro `Widget.field`"), am("mid turn"), am("update `Widget.field`"), am("later `Widget.field`"))
        val u   = Default.group(raw)
        val sup2 = Default.mergeSupersession(
            Dict.empty[Int, Int],
            Default.analyzedSupersession(Chunk(RegionAnalysis(2, Chunk(Relation(0, RelationKind.Supersedes)))))
        )
        val gg    = Default.deriveGraph(u, raw, sup2)
        val u3ref = gg.edges.get(3).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Reference)
        assert(u3ref.exists(_.target == 2), "the Reference edge repoints to the superseding region 2")
        assert(!u3ref.exists(_.target == 0), "it never targets the superseded introducer (region 0)")
    }

    // ==== event-driven cadence ====

    "event-driven: one analysis call per arming event covers every closed unanalyzed region; write-once tops up only the delta" in {
        TestCompletionServer.run { server =>
            val ctx     = closedCtx()
            val config  = cfg().apiUrl(server.baseUrl)
            val pending = Default.analysisPending(ctx, config)
            assert(pending.nonEmpty, "the snapshot has multiple closed pending regions")
            assert(Default.analysisLowWater(ctx, config) == pending.head.id, "analysisLowWater is the lowest closed unanalyzed ordinal")
            assert(pending.map(_.id) == pending.map(_.id).sorted, "analysisPending is sorted ascending")
            val reply = Analysis(pending.map(u => RegionAnalysis(u.id, Chunk.empty)))
            server.enqueueBody(analysisReply(reply)).andThen {
                Preparation.init.map { prep =>
                    Default.preparationRun(ctx, config, prep, Chunk.empty, driftCause = false).andThen {
                        server.captured.map { cap =>
                            assert(cap.size == 1, "EXACTLY ONE analysis call fires per arming event, not one per pending region")
                            prep.staged.get.map { staged =>
                                val adopted = Default.adopt(CompactionState(), staged)
                                val ctx2    = ctx.withCompaction(adopted)
                                assert(
                                    Default.analysisPending(ctx2, config).isEmpty,
                                    "after adoption every prior pending region is analyzed; the re-arm delta is empty when nothing new closed"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "a region newly closed since the last event participates through structural edges only" in {
        val raw   = Chunk.from((0 to 9).map(i => am(s"turn $i tok$i")))
        val units = Default.group(raw)
        // state analyzed an earlier region 3 (Relates->1); the newly closed region 8 is absent from state.
        val analyses = Chunk(RegionAnalysis(3, Chunk(Relation(1, RelationKind.Relates))))
        val edges    = Default.analyzedEdges(analyses)
        assert(!edges.exists((from, _, _) => from == 8), "the not-yet-analyzed region 8 mints no semantic edge")
        val g  = Default.deriveGraph(units, raw, Dict.empty, edges)
        val e8 = g.edges.get(8).getOrElse(Chunk.empty)
        assert(e8.exists(_.kind == EdgeKind.Adjacency), "region 8 still contributes its structural adjacency edge")
        assert(
            !e8.exists(e => e.kind == EdgeKind.Dependency || e.kind == EdgeKind.Relatedness),
            "region 8 carries no semantic edge until the next event analyzes it"
        )
    }

    // ==== the analysis-failure degrade + no blocking ====

    "an analysis-call failure leaves regions unanalyzed, the graph runs structural, gen never fails, no thread blocks".notJs in {
        TestCompletionServer.run { server =>
            val ctx    = closedCtx()
            val config = cfg().apiUrl(server.baseUrl)
            // "not json" fails the provider's Response decode -> HttpException -> AITransportException, all
            // recovered inside runAnalysis. Occupancy is below the fill trigger, so only this call fires.
            server.enqueueBody("not json").andThen {
                Preparation.init.map { prep =>
                    Default.preparationRun(ctx, config, prep, Chunk.empty, driftCause = false).andThen {
                        prep.staged.get.map { staged =>
                            assert(staged.analyses.isEmpty, "a failed analysis stages nothing (a dropped artifact, not an error)")
                            val state = Default.adopt(CompactionState(), staged)
                            assert(state.analyses.isEmpty, "adoption of an empty staging cell leaves compaction state unanalyzed")
                            val units    = Default.group(ctx.raw)
                            val g        = Default.deriveGraph(units, ctx.raw, Dict.empty, Default.analyzedEdges(state.analyses))
                            val allEdges = units.flatMap(u => g.edges.get(u.id).getOrElse(Chunk.empty))
                            val hasSemantic =
                                allEdges.exists(e => e.kind == EdgeKind.Dependency || e.kind == EdgeKind.Relatedness)
                            assert(!hasSemantic, "the boundary graph carries only structural edges (Adjacency + Reference)")
                            noBlockingConstructs()
                        }
                    }
                }
            }
        }
    }

    // The no-blocking-construct grep gate over the touched main sources. Every wait in the analysis path is a
    // Fiber.get/Channel suspension.
    def noBlockingConstructs()(using kyo.test.AssertScope): Unit =
        val banned = List("Thread.sleep", "synchronized", "CountDownLatch", "Future.await", ".await(", "Await.", "AllowUnsafe")
        List("Compactor.scala").foreach { name =>
            readMainSourceOpt(name).foreach { text =>
                banned.foreach(b => assert(!text.contains(b), s"$name must carry no blocking construct: $b"))
            }
        }
    end noBlockingConstructs

    def readMainSourceOpt(fileName: String): Maybe[String] =
        try
            val relative   = s"shared/src/main/scala/kyo/$fileName"
            val candidates = Chunk(new java.io.File(relative), new java.io.File("kyo-ai", relative), new java.io.File(s"../$relative"))
            Maybe.fromOption(candidates.find(_.exists()).map(f => scala.io.Source.fromFile(f, "UTF-8").mkString))
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

end CompactorAnalysisTest
