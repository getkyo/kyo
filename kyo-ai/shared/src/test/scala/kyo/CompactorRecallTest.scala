package kyo

import Compactor.internal.*
import Tool.internal.RunOutcome
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** Recall aspect of the default Compactor: verbatim role-tagged restoration bound to the calling
  * instance, the decaying-seed reinstatement, its decay and re-demotion, and the conditional clearing of a
  * reinstated recall exchange.
  */
class CompactorRecallTest extends kyo.test.Test[Any]:
    // The shipped policy and calibration, so these tests read the same values the default compactor runs
    // re-bound because it now takes the tuning that owns its floor constants.
    private val tuning      = Compactor.Tuning()
    private val calibration = Compactor.internal.Calibration()
    import calibration.*
    import tuning.*
    private def keepFloor(regions: Int): Double = Compactor.internal.keepFloor(regions, tuning)

    def um(s: String): UserMessage                             = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                           = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage          = AssistantMessage(s, Chunk.from(calls))
    def tm(id: String, s: String): ToolMessage                 = ToolMessage(CallId(id), s)
    def call(id: String, fn: String, args: String): Call       = Call(CallId(id), fn, args)
    def cfg: Config                                            = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", 200000)
    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    "recall(id) restores the covered region verbatim, role-tagged, own instance only" in {
        // The view carries a pointer marker for region 14 (origin start=14, end=17); raw holds the 3 originals.
        val head   = Chunk.from((0 until 14).map(i => am(s"m$i")))
        val region = Chunk[Message](am("ASSISTANT PAYLOAD"), tm("c1", "TOOL PAYLOAD"), um("USER PAYLOAD"))
        val raw    = head.concat(region)
        val marker = SystemMessage("[regions 14-16 compacted]", origin = Present(Context.Origin(14, 17, 17)))
        val ctx    = Context(raw, Chunk(marker))
        LLM.run(cfg) {
            AI.init.map { ai =>
                ai.setContext(ctx).andThen {
                    Default.recallTool(ai).infos.head.decodeAndRun("""{"id":14}""").map { r =>
                        assert(
                            r match
                                case RunOutcome.Ran(Result.Success(o), _) =>
                                    o.contains("assistant: ASSISTANT PAYLOAD") &&
                                    o.contains("tool: TOOL PAYLOAD") &&
                                    o.contains("user: USER PAYLOAD")
                                case _ => false
                            ,
                            s"recall(14) returns the 3 raw messages each role-prefixed, byte-exact, from THIS instance's raw, got: $r"
                        )
                    }
                }
            }
        }
    }

    "a fresh recall's seed contribution is recallSeedWeight, and in SCORE space it is that times the restart weight" in {
        // This test used to compare the SEED contribution against the SCORE-space keep floor and conclude
        // the region was reinstated. That does not follow, and the numbers are not close: a 0.20 seed is
        // above the 0.084 floor at sixteen regions, but the cut consults `score`, and PPR attenuates a
        // seed's effect on its own node by the restart weight, giving 0.030, which is BELOW that floor.
        //
        // Every edge points at an earlier region (targets are strictly earlier by construction), so the
        // graph is a backward DAG: mass a seed injects flows away and never returns, which makes the
        // attenuation exactly restartWeight rather than approximately it. That exactness is asserted here,
        // because it is the one topology-independent quantity in the ranking.
        val raw   = Chunk.from((0 until 16).map(i => am(s"region $i")))
        val units = Default.group(raw)
        val state = Compaction.State(boundaryCounter = 5).withRecall(14)

        val withRecall = Default.seedVector(units, raw, state)
        val baseline   = Default.seedVector(units, raw, Compaction.State(boundaryCounter = 5))
        val seedDelta  = withRecall.get(14).getOrElse(0.0) - baseline.get(14).getOrElse(0.0)
        assert(
            eps(seedDelta, recallSeedWeight * math.pow(recallDecay, 0)),
            s"region 14's seed gains recallSeedWeight*decay^0, got $seedDelta"
        )

        val graph      = Default.deriveGraph(units, raw, Dict.empty[Int, Int])
        val scoreWith  = Default.score(units, graph, Dict.empty[Int, Int], withRecall)
        val scoreBase  = Default.score(units, graph, Dict.empty[Int, Int], baseline)
        val scoreDelta = scoreWith.get(14).getOrElse(0.0) - scoreBase.get(14).getOrElse(0.0)
        assert(
            eps(scoreDelta, restartWeight * seedDelta, tol = 1e-6),
            s"a seed of $seedDelta moves its own region's SCORE by exactly restartWeight*seed = " +
                s"${restartWeight * seedDelta}, got $scoreDelta (backward DAG: injected mass never returns)"
        )
        assert(
            scoreDelta < keepFloor(units.size),
            s"and that is BELOW the keep floor at ${units.size} regions ($scoreDelta against " +
                s"${keepFloor(units.size)}), so a bare seed does not reinstate on its own: the tool " +
                "reinstates through the reference edges its raw-entering exchange mints, which the next " +
                "test exercises"
        )
    }

    "the recall exchange's own bytes mint the inflow that actually reinstates the region" in {
        // The reinstatement the tool achieves is seed PLUS inflow, and the inflow is why. A recall's
        // exchange enters `raw`, and the quoted text repeats the target region's own distinctive terms,
        // so the next boundary's graph mints reference edges from the exchange back into the target. A
        // fixture of bare regions with no exchange in raw can never show this, which is why the previous
        // version of the test above could only assert the seed and had to overreach to reach a verdict.
        val target     = "the ingestBatchSize is 384 records"
        val head       = Chunk.from((0 until 14).map(i => am(s"unrelated note $i about other matters")))
        val withTarget = head.append(am(s"region 14: $target"))
        // the recall exchange: the assistant's call fused with the tool result quoting the region back
        val exchange = Chunk[Message](
            am("recalling", call("r1", "recall", """{"id":14}""")),
            tm("r1", s"[region 14, restored verbatim]\nassistant: region 14: $target")
        )

        val without = withTarget
        val with_   = withTarget.concat(exchange)

        def inflowTo(region: Int, raw: Chunk[Message]): Int =
            val units = Default.group(raw)
            val graph = Default.deriveGraph(units, raw, Dict.empty[Int, Int])
            units.toList.map(_.id).map(from =>
                graph.edges.get(from).getOrElse(Chunk.empty).count(e => e.target == region && e.kind == EdgeKind.Reference)
            ).sum
        end inflowTo

        val before = inflowTo(14, without)
        val after  = inflowTo(14, with_)
        assert(
            after > before,
            s"the exchange's quoted text mints reference edges into region 14: $before before, $after after"
        )
    }

    "the recall boost decays and the region re-demotes after interest cools" in {
        val raw   = Chunk.from((0 until 16).map(i => am(s"region $i")))
        val units = Default.group(raw)
        def contribAt(n: Int): Double =
            val state   = Compaction.State(boundaryCounter = 5 + n, recalls = Chunk(RecallRecord(14, 5)))
            val withR   = Default.seedVector(units, raw, state)
            val without = Default.seedVector(units, raw, Compaction.State(boundaryCounter = 5 + n))
            withR.get(14).getOrElse(0.0) - without.get(14).getOrElse(0.0)
        end contribAt
        assert(eps(contribAt(0), recallSeedWeight), "at the recall boundary the contribution is recallSeedWeight*decay^0")
        assert(eps(contribAt(1), recallSeedWeight * recallDecay), "one boundary later it is recallSeedWeight*decay^1")
        assert(eps(contribAt(4), recallSeedWeight * math.pow(recallDecay, 4)), "n boundaries later it is recallSeedWeight*decay^n")
        assert(contribAt(0) > contribAt(1) && contribAt(1) > contribAt(4), "the contribution decays monotonically toward 0")
        assert(
            contribAt(4) < keepFloor(units.size),
            "once it falls below the floor, the region re-demotes (the decay replaces a promotion flag)"
        )
    }

    "the recall exchange is cleared when reinstated, kept when pressure prevents it" in {
        // raw holds region 14 plus a tail recall exchange: an assistant recall call fused with its tool result.
        val head       = Chunk.from((0 until 14).map(i => am(s"m$i")))
        val region14   = am("REGION 14 CONTENT")
        val recallCall = am("recalling", call("rc1", "recall", """{"id":14}"""))
        val toolResult = tm("rc1", "REGION 14 CONTENT")
        val raw        = head.append(region14).append(recallCall).append(toolResult) // indices 14, 15, 16
        val state      = Compaction.State().withRecall(14)
        // (a) low pressure: region 14 reinstated (absent from any demoted span) -> the exchange is cleared.
        val clearedA = Default.reinstatedRecallIndices(raw, Set.empty[Int], state)
        assert(
            clearedA.contains(15) && clearedA.contains(16),
            s"the recall call (15) and its answering tool result (16) are both cleared, got $clearedA"
        )
        // (b) high pressure: region 14 stays demoted -> the exchange is kept so the model never loses what it asked for.
        val clearedB = Default.reinstatedRecallIndices(raw, Set(14), state)
        assert(clearedB.isEmpty, "when region 14 remains demoted the tail recall copy is retained")
        // the RecallRecord lives in state and is untouched by clearing, so the decaying seed survives.
        assert(state.recalls.map(_.region).toList == List(14), "the recall record in state is untouched by view clearing")
    }

    "the recall tool's record survives the call that produced it" in {
        // Live evidence: with the compactor enabled and the model explicitly told to use the recall tool,
        // the tool clearly ran (the turn appended a tool exchange and the model quoted recalled detail),
        // yet the session's recall count stayed at 0 for every dump. The record is what carries recall's
        // SECOND half: the decaying seed that reinstates the recalled region at the next boundary. If the
        // record is lost, recall degrades to a one-shot read with no effect on what compaction keeps.
        //
        // The existing tool test calls decodeAndRun and asserts only the returned STRING, so it cannot see
        // this. This asserts the state the tool writes.
        val head   = Chunk.from((0 until 14).map(i => am(s"m$i")))
        val region = Chunk[Message](am("ASSISTANT PAYLOAD"), tm("c1", "TOOL PAYLOAD"), um("USER PAYLOAD"))
        val raw    = head.concat(region)
        val marker = SystemMessage("[regions 14-16 compacted]", origin = Present(Context.Origin(14, 17, 17)))
        val ctx    = Context(raw, Chunk(marker))
        LLM.run(cfg) {
            AI.init.map { ai =>
                ai.setContext(ctx).andThen {
                    Default.recallTool(ai).infos.head.decodeAndRun("""{"id":14}""").andThen(ai.context).map { after =>
                        assert(
                            after.compactionState.recalls.exists(_.region == 14),
                            s"the tool must leave a recall record for region 14, got ${after.compactionState.recalls}"
                        )
                    }
                }
            }
        }
    }

    "merging a forked context keeps the compaction state the fork recorded" in {
        // The live defect. A tool runs against a FORKED AI instance, and its context is folded back with
        // Context.merge, which rebuilt the parent as copy(raw = ..., compacted = ...) and so silently kept
        // the PARENT's compaction field, discarding whatever the fork recorded. The recall tool writes its
        // record exactly there, so a recall the model performed was thrown away on merge: the content still
        // reached the model, but the record carrying recall's decaying reinstatement seed never survived,
        // and a live session reported recalls=0 no matter how many times the tool ran.
        val raw    = Chunk.from((0 until 4).map(i => am(s"m$i")))
        val parent = Context(raw).withCompaction(Compaction.State(boundaryCounter = 7, lastUsage = Present(1234), lastUsageRawSize = 4))
        val forked = parent.withCompaction(parent.compactionState.withRecall(2))
        val merged = parent.merge(forked)
        assert(
            merged.compactionState.recalls.exists(_.region == 2),
            s"a recall recorded in the fork must survive the merge, got ${merged.compactionState.recalls}"
        )
        // the parent's own clock and anchor are NOT reseated by a fork
        assert(merged.compactionState.boundaryCounter == 7, "the boundary counter stays the parent's clock")
        assert(merged.compactionState.lastUsage == Present(1234), "the usage anchor stays the parent's")
    }

    "a merge unions the write-once slots, parent winning a collision" in {
        val raw    = Chunk.from((0 until 4).map(i => am(s"m$i")))
        val parent = Context(raw).withCompaction(Compaction.State().withSummary(0, 2, "PARENT"))
        val forked = Context(raw).withCompaction(Compaction.State().withSummary(0, 2, "FORK").withSummary(2, 4, "FORK-NEW"))
        val merged = parent.merge(forked)
        assert(merged.compactionState.summaryOf(0, 2) == Present("PARENT"), "a frozen slot keeps whichever write landed first")
        assert(merged.compactionState.summaryOf(2, 4) == Present("FORK-NEW"), "a slot only the fork filled is carried over")
    }

    "every recall id the served view advertises still resolves, for as long as it advertises it" in {
        // The closure property, and the hazard it guards is one our design creates on purpose.
        //
        // A marker advertises an id; `recallTool` resolves it by scanning the SERVED VIEW for a matching
        // origin, then refuses if the raw slot behind it was folded into a forgotten retention band.
        // Eviction rewrites raw at a boundary, but between boundaries the view is frozen and re-served
        // unchanged, which is exactly what keeps the provider's prefix cache alive. So a pointer can keep
        // advertising an id for many turns after the content behind it was forgotten, and a model that
        // follows the advertisement gets a refusal string instead of bytes. That window is created by the
        // cache-stability guarantee itself, which is why it needs a test rather than an argument.
        val window = 16384
        val rawCap = 24000
        val cfgT   = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)
        val capped = Compactor.init(Compactor.Tuning(rawRetentionCap = Present(rawCap)))
        def bulk(i: Int) =
            s"step $i: continue the design work. " +
                ("the service coordinates writes across replicas and reconciles them on read, " +
                    "and the reconciliation order decides which write wins on conflict. ") * 24
        def genBody(v: String) =
            val env = Json.encode(s"""{"resultValue":${Json.encode(v)}}""")
            s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$env}}]}}]}"""
        def toks(v: Chunk[Message]) = v.foldLeft(0)((a, m) => a + m.tokens.map(_.count).getOrElse(m.content.length / 3 + 4))

        TestCompletionServer.run { server =>
            Kyo.foreachDiscard(0 until 900)(i => server.enqueueBody(genBody(s"answer $i"))).andThen {
                LLM.run(cfgT.apiUrl(server.baseUrl)) {
                    AI.init.map(_.enable(capped)).map { ai =>
                        // Grow THROUGH the seam past the retention cap, checking closure at every turn.
                        Kyo.foreach(0 until 60) { i =>
                            ai.userMessage(bulk(i)).andThen(ai.gen[String]).handle(Abort.run[Any]).andThen {
                                ai.context.map { ctx =>
                                    // INVITATION-bearing markers only. A coarse band head also carries an
                                    // origin and refuses recall forever, and that is the design: it says
                                    // "no longer recallable" and offers no id. What the design promises is
                                    // that an entry which INVITES a call ("recall(N) restores verbatim")
                                    // can have it answered.
                                    val advertised = ctx.compacted
                                        .filter(m => m.origin.isDefined && m.content.contains("restores verbatim"))
                                        .flatMap(_.origin).map(_.start)
                                    Kyo.foreach(advertised) { id =>
                                        Default.recallTool(ai).infos.head.decodeAndRun(s"""{"id":$id}""").map {
                                            case RunOutcome.Ran(Result.Success(out), _) => (id, out)
                                            case other                                  => (id, s"UNEXPECTED: $other")
                                        }
                                    }.map(results => (ctx, results))
                                }
                            }
                        }.map { perTurn =>
                            val lastCtx          = perTurn.last._1
                            val allResults       = perTurn.flatMap(_._2)
                            val advertisedCounts = perTurn.map(_._2.size)

                            // (1) REGIME: the forget must actually have fired, or closure holds trivially.
                            // Read as the MARKER's presence, not as raw exceeding the cap: eviction is what
                            // brings raw back under the cap, and it does so by substituting zero-token
                            // tombstones in place, so raw's token count is LOWER after a forget than before.
                            // Asserting raw > cap would therefore fail on exactly the runs that did evict.
                            assert(
                                lastCtx.raw.exists(_.content.contains("forgotten past the retention horizon")),
                                s"REGIME: the forget must have fired, raw ${toks(lastCtx.raw)} tokens against a cap of $rawCap"
                            )

                            // (5) NON-VACUITY, first, because it is what stops every other assertion from
                            // passing on an empty set. A compactor that stopped emitting origins would make
                            // closure hold perfectly and mean nothing.
                            assert(
                                advertisedCounts.max > 1,
                                s"REGIME: the view must advertise more than one id at its deepest, got ${advertisedCounts.max}"
                            )

                            // (2) CLOSURE, matching the refusal strings exactly. A substring match on
                            // "region" would pass ON the refusal, which is the failure this is guarding.
                            val forgotten = allResults.count((_, o) => o.contains("was forgotten past the retention horizon"))
                            val nosuch    = allResults.count((_, o) => o.startsWith("no such recallable region:"))
                            println(s"[recall-closure] probes=${allResults.size} forgotten=$forgotten noSuchRegion=$nosuch")
                            val refused = allResults.filter { (_, out) =>
                                out.contains("was forgotten past the retention horizon and is no longer recallable") ||
                                out.startsWith("no such recallable region:")
                            }
                            assert(
                                refused.isEmpty,
                                s"every id the view advertises must resolve while it is advertised; " +
                                    s"${refused.size} of ${allResults.size} were refused, first: ${refused.headOption}"
                            )

                            // (3) FIDELITY: the resolved bytes are the raw content of the covered range,
                            // role-tagged. Not "non-empty", which the marker's own descriptor would satisfy.
                            // Fidelity is checked WITHIN the last turn: an id probed on an earlier turn was
                            // answered against the raw of that turn, and comparing it to the final raw would
                            // be comparing two different transcripts.
                            val lastResults = perTurn.last._2
                            val checked = lastCtx.compacted
                                .filter(m => m.origin.isDefined && m.content.contains("restores verbatim"))
                                .flatMap(_.origin).headMaybe
                            checked match
                                case Present(o) =>
                                    val covered = lastCtx.raw.slice(o.start, o.end).filter(_.content.nonEmpty)
                                    val out     = lastResults.filter((id, _) => id == o.start).headOption.map(_._2).getOrElse("")
                                    assert(
                                        covered.forall(m => out.contains(m.content)),
                                        s"recall must return the raw bytes over [${o.start}, ${o.end}), not the marker's descriptor"
                                    )
                                case Absent => assert(false, "REGIME: at least one advertised origin must exist to check fidelity")
                            end match
                        }
                    }
                }
            }
        }
    }

end CompactorRecallTest
