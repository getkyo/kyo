package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Context.*

/** The §5g relevance-drift trigger: the model-free stale-verbatim mass S (measure + pinning complement +
  * threshold), the structural over-arm upper bound, the arm-confirm-fire ordering with the refractory of
  * four, and the same-machinery fire that sheds no size and collapses S to zero. Deterministic throughout:
  * drift is driven by hand-built Contexts with a KNOWN stale set (never timing); occupancy is pinned via
  * the usage anchor; the two end-to-end passes pre-stage analyses and summaries so the background fiber
  * issues zero completions, and every wait is an async suspension (Fiber/Channel), never a sleep.
  */
class CompactorDriftTest extends kyo.test.Test[Any]:

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def tok(m: Message, n: Int): Message              = stamp(m, TokenStamp("t", n))

    // window 16384 => effectiveHigh 8192, effectiveLow 4915, prepareLine 6553.
    def cfg(window: Int = 16384): Config =
        Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    val noInfos: Chunk[Tool.internal.Info[?, ?, LLM]] = Chunk.empty

    // A minimal OpenAI completion body that ends the gen loop via the result tool (no usage: the anchor
    // stays offline-estimated, so occupancy holds at the forced value across passes).
    def genBody(resultValue: String): String =
        val envelope = Json.encode(s"""{"resultValue":$resultValue}""")
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$envelope}}]}}]}"""
    end genBody

    // A long, deliberately vocab-disjoint transcript: plain lowercase prose mints no structural
    // identifiers (extractTokens requires interior signal), so no Reference edge forms and liveness flows
    // only along the backward adjacency chain from the seeded ends. The single early user turn (task +
    // objective seed) and the recent tail carry the seeds; a deep middle band sits far enough from both
    // that its regions fall below keep(1) and form clean stale-verbatim spans. Each region is one message
    // and carries a known stamped token count.
    def driftRaw(): Chunk[Message] =
        val head = Chunk[Message](
            tok(sm("orientation preface for the working session"), 50),
            tok(um("please assist with the primary objective outcome"), 50)
        )
        val middle = Chunk.from((0 until 30).map(i => tok(am(distinctWords(i)), 400)))
        val recent = Chunk.from((0 until 10).map(i => tok(am(distinctWords(100 + i)), 400)))
        val tail   = Chunk[Message](tok(am(distinctWords(900) + " closing recap"), 1400))
        head.concat(middle).concat(recent).concat(tail)
    end driftRaw

    // Ten unique lowercase words per region, seeded off the index, so no two regions share a token.
    def distinctWords(seed: Int): String =
        val letters = "abcdefghijklmnopqrstuvwxyz"
        Chunk.from((0 until 10).map { k =>
            val n = seed * 31 + k * 7 + 3
            List(letters((n) % 26), letters((n / 26 + 5) % 26), letters((n / 7 + 11) % 26), letters((n / 3 + 19) % 26))
                .mkString
        }).mkString(" ")
    end distinctWords

    // The forced-occupancy Context: raw == compacted (verbatim served view), the usage anchor pinning
    // occupancy to `occ` (compacted size == the anchor raw size, so the offline suffix is zero).
    def forced(raw: Chunk[Message], occ: Int, state: CompactionState => CompactionState = identity): Context =
        val base = CompactionState(lastUsage = Present(occ), lastUsageRawSize = raw.size)
        Context(raw, raw, Present(state(base)))

    // Replicates driftSignal's internal derivation so a test can name the exact stale set and keep floor.
    final case class Bits(
        units: Chunk[Region],
        spans: Chunk[Span],
        scores: Dict[Int, Double],
        keepFloor: Double,
        demoted: Dict[Int, Context.Origin]
    )

    def bits(ctx: Context, config: Config, analyses: Chunk[RegionAnalysis]): Bits =
        val units = Default.group(ctx.raw)
        val superseded = Default.mergeSupersession(
            Default.supersession(units, Default.superKeysFrom(units, ctx.raw, noInfos)),
            Default.analyzedSupersession(analyses)
        )
        val spans     = Default.formSpans(units, ctx.raw, config)
        val graph     = Default.deriveGraph(units, ctx.raw, superseded, Default.analyzedEdges(analyses))
        val seed      = Default.seedVector(units, ctx.raw, ctx.compactionState)
        val scores    = Default.score(units, graph, superseded, seed)
        val occupied  = occupancy(ctx)
        val low       = config.effectiveLow
        val pressure  = if low <= 0 then 1.0 else occupied.toDouble / low.toDouble
        val keepFloor = keep(math.max(pressure, 1.0))
        Bits(units, spans, scores, keepFloor, Default.demotedOrigins(ctx.compacted))
    end bits

    def staleSpans(b: Bits): Chunk[Span] =
        b.spans.filter(sp => Default.spanMaxLiveness(sp, b.scores) < b.keepFloor && !sp.regionIds.exists(id => b.demoted.contains(id)))

    def tokensOf(b: Bits, spans: Chunk[Span]): Int =
        val byId = b.units.foldLeft(Dict.empty[Int, Region])((m, u) => m.update(u.id, u))
        spans.foldLeft(0)((n, sp) => n + sp.regionIds.foldLeft(0)((t, id) => t + byId.get(id).map(_.tokens).getOrElse(0)))

    // ==== the measure ====

    "INV-045 the drift signal S = the stale-verbatim mass, the exact complement of the pinning clause" in {
        val raw       = driftRaw()
        val config    = cfg()
        val ctx       = forced(raw, 4000)
        val b         = bits(ctx, config, Chunk.empty)
        val stale     = staleSpans(b)
        val pinned    = b.spans.filter(sp => Default.spanMaxLiveness(sp, b.scores) >= b.keepFloor)
        val closedIds = b.spans.flatMap(_.regionIds).toSet
        val tailIds   = b.units.map(_.id).filterNot(closedIds.contains)
        // the drift signal equals the independently-summed stale-verbatim mass, exactly (not an estimate).
        assert(
            Default.driftSignal(ctx, config, noInfos, Chunk.empty) == tokensOf(b, stale),
            "S is the exact token sum of the stale-verbatim spans"
        )
        // the fixture is non-degenerate: there is a genuinely stale band AND a pinned band.
        assert(stale.nonEmpty, "the deep middle band forms at least one stale-verbatim span")
        assert(pinned.nonEmpty, "the seeded ends form at least one pinned span (its hottest member at or above keep)")
        // the stale predicate is the cut's own demotable filter (spanMaxLiveness < keepFloor), no second rule.
        val demotable = b.spans.filter(sp => Default.spanMaxLiveness(sp, b.scores) < b.keepFloor)
        assert(stale.forall(sp => Default.spanMaxLiveness(sp, b.scores) < b.keepFloor), "every counted span is below the keep floor")
        assert(
            demotable.map(_.start).toSet == stale.map(_.start).toSet,
            "with nothing pre-demoted the stale set IS the demotable set (the pinning complement)"
        )
        // the pinned span contributes zero.
        assert(!pinned.exists(sp => stale.map(_.start).toSet.contains(sp.start)), "a pinned span is never counted toward S")
        // the pending tail band is closed-prefix-excluded, so its tokens never enter S.
        assert(tailIds.nonEmpty, "the fixture keeps a pending tail band outside the closed prefix")
        assert(
            !stale.exists(sp => sp.regionIds.exists(tailIds.contains)),
            "no tail-band region enters a stale span (formSpans is closed-prefix)"
        )

        // an ALREADY-DEMOTED stale span contributes zero: mark the first stale span's start in compacted.
        val victim = stale.head
        val marker = SystemMessage(s"[demoted ${victim.start}]", origin = Present(Context.Origin(victim.start, victim.end, raw.size)))
        val demCtx = ctx.copy(compacted = ctx.compacted.append(marker))
        val bDem   = bits(demCtx, config, Chunk.empty)
        assert(bDem.demoted.contains(victim.start), "the marker registers the victim span as already demoted in the served view")
        assert(
            Default.driftSignal(demCtx, config, noInfos, Chunk.empty) == tokensOf(b, stale) - tokensOf(b, Chunk(victim)),
            "an already-demoted span drops out of S by exactly its own token mass (no double counting with the size trigger)"
        )
    }

    "INV-045b the fire condition fires iff S >= driftThreshold * effectiveLow; Absent disables drift" in {
        val raw = driftRaw()
        // a wider window so effectiveLow comfortably exceeds S: the threshold knob (clamped to (0,1)) can
        // then be tuned to either side of the fire line S >= threshold * effectiveLow without saturating.
        val base = cfg(65536)
        val ctx  = forced(raw, 4000)
        val s    = Default.driftSignal(ctx, base, noInfos, Chunk.empty)
        val low  = base.effectiveLow
        assert(s > 0, "the fixture crosses with mass to spare")
        assert(s < low, s"the stale mass ($s) sits below effectiveLow ($low) so the threshold ratio stays inside (0,1)")
        // the fire line is S >= threshold * effectiveLow; tune the threshold knob around S/low.
        val ratio  = s.toDouble / low.toDouble
        val below  = base.compaction(_.driftThreshold(math.min(0.99, ratio + 0.05)))
        val above  = base.compaction(_.driftThreshold(math.max(0.001, ratio - 0.05)))
        val absent = base.compaction(_.noDriftThreshold)
        assert(Default.driftDecision(ctx, below, noInfos, Chunk.empty) == Default.DriftDecision.Idle, "(a) S under the line does not arm")
        assert(
            Default.driftDecision(ctx, above, noInfos, Chunk.empty) == Default.DriftDecision.Arm,
            "(b) S at or over the line arms on the first crossing"
        )
        assert(
            Default.driftDecision(ctx, absent, noInfos, Chunk.empty) == Default.DriftDecision.Idle,
            "(c) driftThreshold Absent disables drift (size-only triggering)"
        )
        // the default 0.15 knob is consumed: with the fixture's S the default decision matches the raw comparison.
        val defaultDecision = Default.driftDecision(ctx, base, noInfos, Chunk.empty)
        val defaultCrosses  = s.toDouble >= 0.15 * low.toDouble
        assert(
            (defaultDecision == Default.DriftDecision.Arm) == defaultCrosses,
            "the default 0.15 driftThreshold compares S against 0.15 * effectiveLow exactly"
        )
    }

    "INV-046 the drift measure is model-free: computing S and the decision issues ZERO completions" in {
        val raw = driftRaw()
        TestCompletionServer.run { server =>
            val config = cfg().apiUrl(server.baseUrl)
            val ctx    = forced(raw, 4000)
            // driftSignal / driftDecision are pure, synchronous Int/enum values: no completion path is entered.
            val s = Default.driftSignal(ctx, config, noInfos, Chunk.empty)
            val d = Default.driftDecision(ctx, config, noInfos, Chunk.empty)
            server.captured.map { cap =>
                assert(cap.isEmpty, "no completion is issued while measuring drift (relations read from state, never fetched)")
                assert(s > 0, "the measure still produced a real signal without any model call")
                assert(d == Default.DriftDecision.Arm, "the first crossing decides Arm without blocking on a model call")
            }
        }
    }

    "INV-047 structural-only S is an upper bound: it over-arms, never under-arms" in {
        val raw         = driftRaw()
        val config      = cfg()
        val ctx         = forced(raw, 4000)
        val bStruct     = bits(ctx, config, Chunk.empty)
        val staleStruct = staleSpans(bStruct)
        assert(staleStruct.nonEmpty, "structural-only leaves a stale band")
        // pick a stale region and lift it with adopted analysis edges from live regions (Relatedness).
        val victimSpan = staleStruct.head
        val victim     = victimSpan.regionIds.head
        // the most-live regions point at the victim via Relates: the analyzed layer only ADDS liveness.
        val liveIds  = bStruct.scores.toChunk.toList.sortBy(-_._2).map(_._1).filter(_ != victim).take(4)
        val analyses = Chunk.from(liveIds.map(id => RegionAnalysis(id, Chunk(Relation(victim, RelationKind.Relates)))))
        val bAna     = bits(ctx, config, analyses)
        val staleAna = staleSpans(bAna)
        val sStruct  = Default.driftSignal(ctx, config, noInfos, Chunk.empty)
        val sAna     = Default.driftSignal(ctx, config, noInfos, analyses)
        assert(
            bAna.scores.get(victim).getOrElse(0.0) > bStruct.scores.get(victim).getOrElse(0.0),
            "the analysis edge strictly lifts the victim's liveness"
        )
        assert(
            staleAna.map(_.start).toSet.subsetOf(staleStruct.map(_.start).toSet),
            "the analyzed stale set is a subset of the structural one (never adds staleness)"
        )
        assert(sAna <= sStruct, "structural S is an upper bound on analyzed S: the lag over-counts, never under-counts")
        assert(sAna < sStruct, "with the victim lifted out, analyzed S is strictly below structural S")
    }

    // ==== arm, confirm, fire, refractory ====

    "INV-048c the refractory of four blocks a re-fire within four boundary generations, allows at or beyond" in {
        val raw    = driftRaw()
        val config = cfg()
        val within = forced(raw, 4000, _.copy(boundaryCounter = 10, lastDriftFire = 9, driftPendingConfirm = true))
        val atBnd  = forced(raw, 4000, _.copy(boundaryCounter = 10, lastDriftFire = 6, driftPendingConfirm = true))
        val fresh  = forced(raw, 4000, _.copy(boundaryCounter = 10, lastDriftFire = -1, driftPendingConfirm = true))
        assert(
            Default.driftDecision(within, config, noInfos, Chunk.empty) == Default.DriftDecision.Idle,
            "diff 1 < 4: blocked by the refractory"
        )
        assert(
            Default.driftDecision(atBnd, config, noInfos, Chunk.empty) == Default.DriftDecision.Fire,
            "diff 4 >= 4: the refractory allows the fire"
        )
        assert(
            Default.driftDecision(fresh, config, noInfos, Chunk.empty) == Default.DriftDecision.Fire,
            "lastDriftFire < 0 always allows the first fire"
        )
        // refractoryAllows is a pure function of exactly (boundaryCounter, lastDriftFire, driftRefractory).
        assert(!Default.refractoryAllows(within.compactionState), "the within-window state does not allow a fire")
        assert(Default.refractoryAllows(atBnd.compactionState), "the at-boundary state allows a fire")
        assert(Default.refractoryAllows(fresh.compactionState), "the never-fired state allows the first fire")
    }

    // Builds the end-to-end drift session: raw == compacted, occupancy pinned below the size boundary,
    // every closed region pre-analyzed with empty relations (so analysisPending is empty) and every stale
    // span pre-summarized (so fillNeed is empty). The background fiber therefore issues ZERO completions,
    // so the only scripted reply consumed per pass is the gen result.
    def driftSession(pending: Boolean, extra: CompactionState => CompactionState = identity): (Context, Chunk[Span]) =
        val raw    = driftRaw()
        val config = cfg()
        val probe  = forced(raw, 4000)
        val b      = bits(probe, config, Chunk.empty)
        val stale  = staleSpans(b)
        val seeded =
            b.units.foldLeft(CompactionState(lastUsage = Present(4000), lastUsageRawSize = raw.size, driftPendingConfirm = pending)) {
                (st, u) => st.withAnalysis(RegionAnalysis(u.id, Chunk.empty))
            }
        val withSum = stale.foldLeft(seeded)((st, sp) => st.withSummary(sp.start, sp.end, s"summary of span ${sp.start}"))
        (Context(raw, raw, Present(extra(withSum))), stale)
    end driftSession

    "INV-048 the arm: a first crossing arms the drift cause and serves the view unchanged" in {
        val (ctx, _) = driftSession(pending = false)
        val before   = ctx.compacted
        TestCompletionServer.run { server =>
            server.enqueueBody(genBody("1")).andThen {
                LLM.run(cfg().apiUrl(server.baseUrl)) {
                    AI.enable(Compactor.init) {
                        AI.init.map { ai =>
                            ai.setContext(ctx).andThen(ai.gen[Int]).andThen(ai.context)
                        }
                    }
                }.map { after =>
                    assert(after.compactionState.driftPendingConfirm, "the first crossing latches pending-confirm")
                    assert(after.compactionState.boundaryCounter == 0, "arming fires NO boundary (the counter is unchanged)")
                    // arming never changes bytes: the served closed-prefix view carries no new demotion marker.
                    val newMarkers = after.compacted.filter(m => m.origin.isDefined && before.forall(_ ne m))
                    assert(newMarkers.isEmpty, "the served view is byte-identical below the line (no demotion on an arm)")
                }
            }
        }
    }

    "INV-048b the confirm-fire: a second crossing with pending latched fires, demoting the stale set" in {
        val (armed, stale) = driftSession(pending = true)
        val staleStarts    = stale.map(_.start).toSet
        val config         = cfg()
        val pinnedStart    = Chunk.from(bits(armed, config, Chunk.empty).spans.map(_.start).toSet.diff(staleStarts)).headMaybe
        TestCompletionServer.run { server =>
            server.enqueueBody(genBody("2")).andThen {
                LLM.run(config.apiUrl(server.baseUrl)) {
                    AI.enable(Compactor.init) {
                        AI.init.map { ai =>
                            ai.setContext(armed).andThen(ai.gen[Int]).andThen(ai.context)
                        }
                    }
                }.map { after =>
                    assert(
                        after.compactionState.boundaryCounter == 1,
                        "the confirmed drift fires the boundary (counter incremented by one)"
                    )
                    assert(!after.compactionState.driftPendingConfirm, "the fire clears pending-confirm")
                    assert(after.compactionState.lastDriftFire == 1, "the fire stamps lastDriftFire at the post-tick boundary index")
                    val demoted = after.compacted.flatMap(_.origin.map(_.start)).toSet
                    assert(
                        staleStarts.forall(demoted.contains),
                        s"every stale span renders demoted after the fire, got $demoted for $staleStarts"
                    )
                    pinnedStart.foreach(ps => assert(!demoted.contains(ps), "a pinned verbatim span is never demoted by the drift fire"))
                }
            }
        }.andThen {
            // control: a single UNARMED crossing only arms (never fires on the first crossing), proving
            // the two-pass ordering. A fresh session (pending clear) runs one pass and does not fire.
            val (fresh, _) = driftSession(pending = false)
            TestCompletionServer.run { server =>
                server.enqueueBody(genBody("3")).andThen {
                    LLM.run(config.apiUrl(server.baseUrl)) {
                        AI.enable(Compactor.init) {
                            AI.init.map { ai =>
                                ai.setContext(fresh).andThen(ai.gen[Int]).andThen(ai.context)
                            }
                        }
                    }.map { after =>
                        assert(
                            after.compactionState.boundaryCounter == 0,
                            "a single unarmed crossing never fires (structure alone arms, the confirm fires)"
                        )
                        assert(after.compactionState.driftPendingConfirm, "the single unarmed pass latches pending for the next confirm")
                    }
                }
            }
        }
    }

    "INV-049 the drift fire runs the same machinery and S collapses to zero afterward" in {
        val (armed, stale) = driftSession(pending = true)
        val config         = cfg()
        val staleStarts    = stale.map(_.start).toSet
        TestCompletionServer.run { server =>
            server.enqueueBody(genBody("4")).andThen {
                LLM.run(config.apiUrl(server.baseUrl)) {
                    AI.enable(Compactor.init) {
                        AI.init.map { ai =>
                            ai.setContext(armed).andThen(ai.gen[Int]).andThen(ai.context)
                        }
                    }
                }.map { after =>
                    // the fired stale spans are demoted in the served view, so they leave the stale set:
                    // the unified rule's reset. driftSignal over the post-fire view is zero.
                    assert(staleStarts.nonEmpty, "the fixture had a stale set to reset")
                    assert(
                        Default.driftSignal(after, config, noInfos, Chunk.empty) == 0,
                        "S collapses to zero after the fire (the fired spans are no longer verbatim)"
                    )
                    // a subsequent pass with pending cleared is Idle: gradual drift yields widely spaced fires.
                    assert(
                        Default.driftDecision(after, config, noInfos, Chunk.empty) == Default.DriftDecision.Idle,
                        "with S zero the next pass is Idle"
                    )
                }
            }
        }
    }

    "INV-049b the drift fire sheds no size, and a one-turn aside re-lifts liveness and never fires" in {
        // sheds no size: at occupied <= effectiveLow the fire demotes ONLY the stale spans (pass 1 depth),
        // leaving every pinned verbatim span byte-unchanged. Verified against the fire in INV-048b via the
        // aside path here, which additionally proves the two-pass persistence.
        val raw          = driftRaw()
        val config       = cfg()
        val probe        = forced(raw, 4000)
        val b            = bits(probe, config, Chunk.empty)
        val stale        = staleSpans(b)
        val staleRegions = stale.flatMap(_.regionIds)
        // the return turn re-references the previously-stale regions (recall records lift their liveness),
        // so the confirm's S drops below the line: the aside is not a drift.
        val recalled = staleRegions.foldLeft(
            CompactionState(lastUsage = Present(4000), lastUsageRawSize = raw.size, driftPendingConfirm = true)
        )((st, id) => st.withRecall(id).withRecall(id).withRecall(id))
        val aside = Context(raw, raw, Present(recalled))
        assert(stale.nonEmpty, "the old task had a stale band on the arm pass")
        assert(
            Default.driftSignal(aside, config, noInfos, Chunk.empty) == 0,
            "the return turn re-lifts every previously-stale region: S drops to zero"
        )
        assert(
            Default.driftDecision(aside, config, noInfos, Chunk.empty) == Default.DriftDecision.Idle,
            "a re-lifted aside decides Idle, not Fire"
        )
        // the seam's Idle branch disarms a stale pending and fires no boundary.
        TestCompletionServer.run { server =>
            server.enqueueBody(genBody("5")).andThen {
                LLM.run(config.apiUrl(server.baseUrl)) {
                    AI.enable(Compactor.init) {
                        AI.init.map { ai =>
                            ai.setContext(aside).andThen(ai.gen[Int]).andThen(ai.context)
                        }
                    }
                }.map { after =>
                    assert(
                        !after.compactionState.driftPendingConfirm,
                        "the Idle branch disarms the stale pending (the aside returned below the line)"
                    )
                    assert(after.compactionState.boundaryCounter == 0, "a one-turn aside fires no boundary")
                }
            }
        }
    }

end CompactorDriftTest
