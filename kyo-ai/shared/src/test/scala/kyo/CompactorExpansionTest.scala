package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*
import kyo.ai.compactor.Model

/** Automatic recall (`Mechanism.Expansion`): the framework re-presenting demoted content on its own when
  * the newest user turn turns out to be about it.
  *
  * This file covers the retained index and the persisted records it will be consumed through. The index is
  * built two ways, once as a filter of a fired boundary's own token index and once by reading the served
  * markers after a snapshot restore, and holding those two constructions equal is what stops them drifting.
  */
class CompactorExpansionTest extends kyo.test.Test[Any]:

    private val tuning      = Compactor.Tuning()
    private val calibration = Compactor.internal.Calibration()
    import calibration.*

    def um(s: String): UserMessage                       = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                     = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage    = AssistantMessage(s, Chunk.from(calls))
    def tm(id: String, s: String): ToolMessage           = ToolMessage(CallId(id), s)
    def call(id: String, fn: String, args: String): Call = Call(CallId(id), fn, args)
    def tok(m: Message, n: Int): Message                 = Compactor.internal.stamp(m, TokenStamp("t", n))

    // A window small enough that the tail band does not swallow the whole fixture. At 16384 the trigger
    // sits at 8192, the render-down target at 4915, and the tail band at a quarter of that, so a
    // transcript stamped at a few hundred tokens a message leaves most of its regions in the closed
    // prefix where spans can form. An unstamped fixture under the default window forms NO spans at all,
    // demotes nothing, and would let every assertion here pass vacuously.
    def cfg: Config = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", 16384)

    // The compactor arm these tests exercise. The index is derived bookkeeping in the ephemeral session
    // cell with no behavioural effect of its own, so it needs no mechanism gate yet: the gate arrives
    // with the delivery it would gate, and `Mechanism.all` really being every member is an invariant this
    // module asserts (CompactorTuningTest), so an entry lands there only when it ships enabled.
    private val armed = Default(Compactor.Tuning(), calibration)

    // A transcript whose regions each carry one distinctive identifier nothing else mentions, so a probe
    // for that identifier can only resolve to its own region.
    // Each step is an assistant CALL fused with its answering result, which is what makes it one region:
    // an assistant message with no call and a tool result with no owner would be two unrelated regions,
    // and every region id below would be off by the amount of that split.
    private def transcript(n: Int): Chunk[Message] =
        Chunk.from((0 until n).flatMap { i =>
            List[Message](
                tok(am(s"working on widget_$i now", call(s"c$i", "lookup", "{}")), 200),
                tok(tm(s"c$i", s"result for widget_$i: ok"), 200)
            )
        })

    // A region's id is its FIRST raw ordinal, and each step above occupies two ordinals.
    private def regionOf(step: Int): Int = step * 2

    private def spansOf(raw: Chunk[Message]) = armed.formSpans(armed.group(raw), raw, cfg)

    // Demote every span the given transcript forms, to one level, and render the view the boundary would
    // have served. This is the fixture the index is filtered out of.
    private def demoteAll(raw: Chunk[Message], level: Level, state: Compaction.State = Compaction.State()) =
        val units      = armed.group(raw)
        val spans      = spansOf(raw)
        val demotions  = spans.foldLeft(Dict.empty[Int, Level])((d, sp) => d.update(sp.start, level))
        val prevLevels = Dict.empty[Int, Context.Origin]
        val view       = armed.project(raw, units, spans, demotions, raw.size, prevLevels, state)
        (units, spans, demotions, view)
    end demoteAll

    private def factsFor(raw: Chunk[Message], view: Chunk[Message], state: Compaction.State)(using Frame) =
        val ctx = Context(raw, view).withCompaction(state)
        Tool.internal.infos.map(infos => (ctx, armed.boundaryFacts(ctx, cfg, infos), infos))

    "the index holds exactly the demoted regions, at the level the boundary assigned them" in {
        val raw                          = transcript(6)
        val (units, spans, demotions, v) = demoteAll(raw, Level.Pointer)
        LLM.run(cfg) {
            factsFor(raw, v, Compaction.State()).map { (ctx, facts, _) =>
                val index    = armed.buildExpansionIndex(ctx, facts, demotions, 3)
                val expected = spans.flatMap(_.regionIds).toList.sorted
                assert(expected.nonEmpty, "the fixture must actually form spans and demote them, or every assertion below is vacuous")
                assert(
                    index.entries.toMap.keys.toList.sorted == expected,
                    s"every member region of every demoted span gets an entry; expected $expected, " +
                        s"got ${index.entries.toMap.keys.toList.sorted}"
                )
                assert(
                    index.entries.toMap.values.forall(_.level == Level.Pointer),
                    "each entry carries the boundary's own assignment for its span"
                )
                assert(index.boundaryStamp == 3 && index.rawWatermark == raw.size, "the interval identity is stamped")
                assert(
                    index.source == Model.ExpansionIndex.Source.Boundary,
                    "a boundary-built index says so, which is what makes a drift between the two constructions visible in the field"
                )
                assert(units.size > expected.size, "the tail band stays verbatim, so not every region is demoted")
            }
        }
    }

    "an identifier introduced by a demoted region probes back to exactly that region" in {
        val raw                  = transcript(6)
        val (_, _, demotions, v) = demoteAll(raw, Level.Pointer)
        LLM.run(cfg) {
            factsFor(raw, v, Compaction.State()).map { (ctx, facts, _) =>
                val index   = armed.buildExpansionIndex(ctx, facts, demotions, 1)
                val posting = index.byTerm.get("widget_0").getOrElse(Chunk.empty)
                assert(posting == Chunk(regionOf(0)), s"widget_0 is introduced by one region and nothing else mentions it, got $posting")
                assert(
                    index.entries.get(regionOf(0)).map(_.identifiers.contains("widget_0")).getOrElse(false),
                    "the entry also carries the identifier, which is what classifies a hit rather than what finds it"
                )
            }
        }
    }

    "a term present across more of the demoted set than the document-frequency ceiling allows is dropped" in {
        // Every region mentions the same identifier, so it discriminates nothing: a probe for it would
        // return the whole demoted set, and every one of those would target verbatim.
        val raw = Chunk.from((0 until 8).flatMap { i =>
            List[Message](
                tok(am(s"touching shared_key_a in step $i", call(s"c$i", "lookup", "{}")), 200),
                tok(tm(s"c$i", s"shared_key_a still fine"), 200)
            )
        })
        val units     = armed.group(raw)
        val spans     = armed.formSpans(units, raw, cfg)
        val demotions = spans.foldLeft(Dict.empty[Int, Level])((d, sp) => d.update(sp.start, Level.Pointer))
        val view      = armed.project(raw, units, spans, demotions, raw.size, Dict.empty[Int, Context.Origin])
        LLM.run(cfg) {
            factsFor(raw, view, Compaction.State()).map { (ctx, facts, _) =>
                val index = armed.buildExpansionIndex(ctx, facts, demotions, 1)
                assert(index.entries.toMap.nonEmpty, "the fixture must actually demote something")
                assert(
                    index.byTerm.get("shared_key_a").isEmpty,
                    s"a term in most of the demoted set identifies nothing and must not be probeable, " +
                        s"got ${index.byTerm.get("shared_key_a")}"
                )
            }
        }
    }

    "the index is built after eviction, so no entry points at forgotten content" in {
        // A tight retention cap forces eviction at the same boundary that demotes, which is exactly the
        // race the build ordering exists for: an index built before eviction would hold entries whose
        // bytes the same boundary destroys.
        val raw                  = transcript(24)
        val (_, _, demotions, v) = demoteAll(raw, Level.Pointer)
        val tight                = Compactor.Tuning(rawRetentionCap = Present(400))
        val arm                  = Default(tight, calibration)
        LLM.run(cfg) {
            Tool.internal.infos.map { infos =>
                val ctx     = Context(raw, v)
                val facts   = arm.boundaryFacts(ctx, cfg, infos)
                val evicted = arm.evict(ctx, cfg)
                assert(
                    evicted.raw.exists(_.origin.isDefined),
                    "the fixture must actually force eviction, or this test proves nothing"
                )
                val index = arm.buildExpansionIndex(evicted, facts, demotions, 1)
                assert(
                    index.entries.toMap.values.forall(e => e.indices.headOption.forall(i => evicted.raw(i).origin.isEmpty)),
                    "no entry may name a region whose raw bytes eviction has already replaced with a band marker"
                )
            }
        }
    }

    "the two index constructions agree, including where a fresh cut would not" in {
        // The re-derivation must READ THE SERVED MARKERS, not re-run the pipeline. Those two are
        // indistinguishable on a quiet fixture, so this one makes them differ: the view is rendered at
        // Pointer, and a recall record is then adopted that a fresh cut would answer by reinstating region
        // 0 verbatim. An implementation that re-cuts sees Verbatim and drops the entry; one that reads the
        // markers still sees Pointer, which is what the model can actually read.
        val raw                  = transcript(8)
        val (_, _, demotions, v) = demoteAll(raw, Level.Pointer)
        val state                = Compaction.State(boundaryCounter = 2).withRecall(0)
        LLM.run(cfg) {
            factsFor(raw, v, state).map { (ctx, facts, infos) =>
                val built     = armed.buildExpansionIndex(ctx, facts, demotions, 2)
                val rederived = armed.rederiveExpansionIndex(ctx, cfg, infos, 2)
                val a         = built.entries.toMap
                val b         = rederived.entries.toMap
                assert(a.nonEmpty, "both constructions must actually find demoted regions, or agreement is vacuous")
                assert(
                    a.keys.toList.sorted == b.keys.toList.sorted,
                    s"same demoted set; built ${a.keys.toList.sorted}, rederived ${b.keys.toList.sorted}"
                )
                // WHOLE entries, not a chosen subset. An earlier form compared keys, level, indices and
                // tokens and skipped the span range, which is exactly the field the two constructions were
                // found drifting on: the test had been shaped around the drift it exists to prevent.
                assert(
                    a.keys.forall(k => a(k) == b(k)),
                    s"every field must agree, not a chosen subset: ${a.keys.filter(k => a(k) != b(k)).map(k => (k, a(k), b(k)))}"
                )
                assert(built.byTerm.toMap == rederived.byTerm.toMap, "same probe map")
                assert(
                    rederived.source == Model.ExpansionIndex.Source.Restored,
                    "and the re-derived one says which path produced it"
                )
            }
        }
    }

    "a summary marker's level is recovered from its bytes, and a terse one is told apart from it" in {
        val raw   = transcript(8)
        val units = armed.group(raw)
        val spans = spansOf(raw)
        val first = spans.head
        val bytes = "a summary long enough that its terse prefix is a proper prefix of it, " * 8
        val state = Compaction.State().withSummary(first.start, first.end, bytes)
        val summaryV =
            armed.project(raw, units, spans, Dict.empty[Int, Level].update(first.start, Level.Summary), raw.size, Dict.empty, state)
        val terseV = armed.project(raw, units, spans, Dict.empty[Int, Level].update(first.start, Level.Terse), raw.size, Dict.empty, state)
        val summaryM = summaryV.filter(_.origin.isDefined).head
        val terseM   = terseV.filter(_.origin.isDefined).head
        val pointerV =
            armed.project(raw, units, spans, Dict.empty[Int, Level].update(first.start, Level.Pointer), raw.size, Dict.empty, state)
        val pointerM = pointerV.filter(_.origin.isDefined).head
        val originOf = (m: Message) => m.origin.get
        assert(armed.markerLevel(summaryM, originOf(summaryM), state) == Level.Summary, "a body ending in the slot's own bytes is Summary")
        assert(armed.markerLevel(terseM, originOf(terseM), state) == Level.Terse, "a body ending in a proper prefix of them is Terse")
        assert(
            armed.markerLevel(pointerM, originOf(pointerM), state) == Level.Pointer,
            "and a single-line body is a pointer, which is the only discriminator stable under re-stamping"
        )
    }

    "a summary span with no fill recovers as Summary rather than as a pointer" in {
        // The substitute-elision case: no slot exists, so a naive reading has nothing to compare against.
        // It is still Summary, because the cut never assigns Terse to a span with no bytes to prefix.
        val raw   = transcript(8)
        val units = armed.group(raw)
        val spans = spansOf(raw)
        val first = spans.head
        val view  = armed.project(raw, units, spans, Dict.empty[Int, Level].update(first.start, Level.Summary), raw.size, Dict.empty)
        val m     = view.filter(_.origin.isDefined).head
        assert(
            armed.markerLevel(m, m.origin.get, Compaction.State()) == Level.Summary,
            "an empty slot renders the substitute elision, which is summary-tier and multi-line"
        )
    }

    "an expansion record survives a Schema round trip, and a state written before the field existed still decodes" in {
        val r =
            ExpansionRecord(region = 12, boundaryStamp = 4, turnStamp = 91, evidence = Evidence.Identifier, delivered = Delivered.Verbatim)
        val state = Compaction.State(boundaryCounter = 4).withExpansion(r)
        val json  = Json.encode(state)
        assert(
            Json.decode[Compaction.State](json) == Result.Success(state),
            s"round trip must be exact, got ${Json.decode[Compaction.State](json)}"
        )
        // The wire shape from before this field existed: no `expansions` key at all. It must decode to the empty default
        // rather than failing, or every snapshot taken before this field existed becomes unreadable.
        val legacy = """{"boundaryCounter":4,"lastUsage":null,"lastUsageRawSize":0,"recalls":[],"summaries":[],"analyses":[]}"""
        assert(
            Json.decode[Compaction.State](legacy).map(_.expansions) == Result.Success(Chunk.empty),
            s"a state serialized before the field existed must still decode, got ${Json.decode[Compaction.State](legacy)}"
        )
    }

    "a second fire on the same region in the same interval replaces its record rather than adding one" in {
        // A region contributes at most ONE seed term per interval, which is the assumption the damper's
        // accumulation ceiling rests on. Escalation updates; it does not accumulate.
        val s0 = Compaction.State(boundaryCounter = 7)
        val s1 = s0.withExpansion(ExpansionRecord(3, 7, 40, Evidence.Content, Delivered.Summary))
        val s2 = s1.withExpansion(ExpansionRecord(3, 7, 52, Evidence.Identifier, Delivered.Verbatim))
        assert(s2.expansions.size == 1, s"one record per (region, interval), got ${s2.expansions}")
        assert(s2.expansions.head.delivered == Delivered.Verbatim, "the escalation is what survives")
        val s3 = s2.withExpansion(ExpansionRecord(3, 8, 60, Evidence.Content, Delivered.Summary))
        assert(s3.expansions.size == 2, "but a fire in the NEXT interval is a separate record, since it carries its own decay clock")
        assert(
            s3.expansionsIn(7).size == 1 && s3.expansionsIn(8).size == 1,
            "and the two are separable by interval, which is the suppression scope"
        )
    }

    "records past the prune horizon are dropped, and recall records are left alone" in {
        val old    = ExpansionRecord(1, 0, 0, Evidence.Content, Delivered.Summary)
        val recent = ExpansionRecord(2, 9, 30, Evidence.Identifier, Delivered.Verbatim)
        val state  = Compaction.State(boundaryCounter = 9, recalls = Chunk(RecallRecord(5, 0))).withExpansion(old).withExpansion(recent)
        val pruned = state.pruneExpansions(expansionPruneHorizon)
        assert(
            pruned.expansions.map(_.region) == Chunk(2),
            s"a record ${9 - 0} boundaries old is past the horizon of $expansionPruneHorizon, got ${pruned.expansions}"
        )
        assert(
            pruned.recalls == state.recalls,
            "recall records have no horizon today, and giving them one is a behaviour change to the tool rather than a side effect of this"
        )
    }

    "merge unions expansion records by region, with the parent winning" in {
        val parent = Compaction.State(boundaryCounter = 3).withExpansion(ExpansionRecord(1, 3, 10, Evidence.Identifier, Delivered.Verbatim))
        val forked = Compaction.State(boundaryCounter = 3)
            .withExpansion(ExpansionRecord(1, 3, 99, Evidence.Content, Delivered.Summary))
            .withExpansion(ExpansionRecord(4, 3, 99, Evidence.Content, Delivered.Summary))
        val merged = Compaction.merge(Present(parent), Present(forked)).get
        assert(merged.expansions.size == 2, s"the fork's new region joins, got ${merged.expansions}")
        assert(
            merged.expansions.filter(_.region == 1).head.turnStamp == 10,
            "and the parent's own record wins the collision, so a fork's speculative fire cannot override established evidence"
        )
    }

    "forgetting a region drops its expansion record along with its summary and its recall" in {
        val state = Compaction.State(boundaryCounter = 2)
            .withExpansion(ExpansionRecord(6, 2, 20, Evidence.Identifier, Delivered.Verbatim))
            .withExpansion(ExpansionRecord(9, 2, 20, Evidence.Content, Delivered.Summary))
        val dropped = armed.dropForgottenState(state, Set(6))
        assert(
            dropped.expansions.map(_.region) == Chunk(9),
            s"a record that outlived its content would seed liveness into bytes that no longer exist, got ${dropped.expansions}"
        )
    }

    // ==== detection: the selection function, falsifiable with no provider ====

    // The fixture every detection test probes: a demoted transcript plus its index, and a helper that
    // runs one user turn against it. The turn never enters `raw` here; that is P4's concern.
    private def detectOn(turn: String, state: Compaction.State = Compaction.State(), raw: Chunk[Message] = transcript(8))(using Frame) =
        val (_, _, demotions, v) = demoteAll(raw, Level.Pointer)
        factsFor(raw, v, state).map { (ctx, facts, infos) =>
            val index = armed.buildExpansionIndex(ctx, facts, demotions, state.boundaryCounter)
            (index, armed.detect(turn, index, raw, state, infos).admitted)
        }
    end detectOn

    "HIT LEG: a turn naming a demoted region by an identifier it introduced selects exactly that region" in {
        LLM.run(cfg) {
            detectOn("can you remind me what widget_1 returned?").map { (index, cands) =>
                assert(index.entries.contains(regionOf(1)), "the named region must actually be demoted, or this proves nothing")
                assert(
                    cands.map(_.entry.region) == Chunk(regionOf(1)),
                    s"exactly the named region is selected, got ${cands.map(_.entry.region)}"
                )
                assert(cands.head.identifierHit, "and it is classified as an identifier hit, which is what targets verbatim")
                assert(cands.head.matched.contains("widget_1"), s"the matched term is recorded, got ${cands.head.matched}")
            }
        }
    }

    "NULL LEG: a turn with no distinctive term selects nothing" in {
        LLM.run(cfg) {
            detectOn("what do you think about that? is it fine?").map { (_, cands) =>
                assert(
                    cands.isEmpty,
                    s"a generic turn must not fire; a detector that fires here fires on everything, got ${cands.map(_.entry.region)}"
                )
            }
        }
    }

    "PARAPHRASE LEG, expected to fail: naming the same content in different words selects nothing" in {
        // This is the documented gap rather than a defect. A lexical detector cannot reach a referent the
        // turn describes without naming, which is exactly the surface the `recall` tool still covers, and
        // it is why the tool is additive rather than replaced. The assertion pins the gap so that closing
        // it later is a visible change rather than a silent one.
        LLM.run(cfg) {
            detectOn("what came back from that second gadget lookup earlier?").map { (_, cands) =>
                assert(
                    cands.isEmpty,
                    s"the paraphrase leg documents what this mechanism does NOT reach; if it now fires, the detector gained " +
                        s"semantic coverage and this test should be rewritten rather than deleted. Got ${cands.map(_.entry.region)}"
                )
            }
        }
    }

    "an identifier hit is admitted with no threshold, while content-only evidence must clear one" in {
        LLM.run(cfg) {
            detectOn("widget_2").map { (_, idOnly) =>
                assert(
                    idOnly.map(_.entry.region) == Chunk(regionOf(2)),
                    s"one bare identifier admits outright, got ${idOnly.map(_.entry.region)}"
                )
                assert(
                    idOnly.head.score >= referenceWeight * expansionRarityMin,
                    s"and it scores at the identifier weight, not the content one, got ${idOnly.head.score}"
                )
            }
        }
    }

    "ADMISSIBILITY: a content match on a span already at Summary is suppressed, and the same match at Pointer is not" in {
        // Pass 1 of the cut leaves most demoted spans at Summary, so this is the common case rather than a
        // corner: without the guard a fire slot goes to re-presenting bytes the marker already carries.
        // The shared phrase sits in TWO regions only. A term every region mentions is rejected by the
        // document-frequency ceiling before admissibility is ever consulted, which would make this test
        // pass for the wrong reason.
        val raw = Chunk.from((0 until 8).flatMap { i =>
            val body = if i == 1 || i == 2 then "the gizmokey warmup path" else s"unrelated bookkeeping step $i"
            List[Message](
                tok(am(body, call(s"c$i", "lookup", "{}")), 200),
                tok(tm(s"c$i", s"step $i finished"), 200)
            )
        })
        LLM.run(cfg) {
            val (_, _, pointerDem, pointerV) = demoteAll(raw, Level.Pointer)
            val (_, _, summaryDem, summaryV) = demoteAll(raw, Level.Summary)
            factsFor(raw, pointerV, Compaction.State()).map { (ctx, facts, infos) =>
                val atPointer =
                    armed.detect(
                        "gizmokey warmup",
                        armed.buildExpansionIndex(ctx, facts, pointerDem, 0),
                        raw,
                        Compaction.State(),
                        infos
                    ).admitted
                factsFor(raw, summaryV, Compaction.State()).map { (ctx2, facts2, infos2) =>
                    val atSummary =
                        armed.detect(
                            "quorum threshold",
                            armed.buildExpansionIndex(ctx2, facts2, summaryDem, 0),
                            raw,
                            Compaction.State(),
                            infos2
                        ).admitted
                    assert(
                        atPointer.nonEmpty,
                        s"a content match on pointer-level content is admissible: Summary is above Pointer, got $atPointer"
                    )
                    assert(
                        atSummary.isEmpty,
                        s"the same match on summary-level content is not: the target equals what the model already reads, got ${atSummary.map(_.entry.region)}"
                    )
                }
            }
        }
    }

    "ADMISSIBILITY: a region already delivered verbatim this interval is suppressed, and one delivered nothing is not" in {
        val delivered = Compaction.State().withExpansion(ExpansionRecord(regionOf(1), 0, 99, Evidence.Identifier, Delivered.Verbatim))
        val seedOnly  = Compaction.State().withExpansion(ExpansionRecord(regionOf(1), 0, 99, Evidence.Identifier, Delivered.SeedOnly))
        LLM.run(cfg) {
            detectOn("about widget_1", delivered).map { (_, after) =>
                detectOn("about widget_1", seedOnly).map { (_, afterNothing) =>
                    assert(
                        after.isEmpty,
                        s"verbatim is the top of the ladder, so nothing is above it to deliver, got ${after.map(_.entry.region)}"
                    )
                    assert(
                        afterNothing.map(_.entry.region) == Chunk(regionOf(1)),
                        "but a fire that delivered nothing put no bytes in view, so it must not suppress the delivery a later turn justifies"
                    )
                }
            }
        }
    }

    "ADMISSIBILITY: a record from an EARLIER interval does not suppress, it lowers the bar" in {
        // The two halves of the same scoping rule. Suppression reads only this interval's records, because
        // records outlive their deliveries in order to seed; the recall prior reads only earlier ones,
        // because an unscoped read would lower the bar on exactly the regions whose bytes are resident now.
        val stale =
            Compaction.State(boundaryCounter =
                4
            ).withExpansion(ExpansionRecord(regionOf(1), 1, 20, Evidence.Identifier, Delivered.Verbatim))
        LLM.run(cfg) {
            detectOn("about widget_1", stale).map { (_, cands) =>
                assert(
                    cands.map(_.entry.region) == Chunk(regionOf(1)),
                    s"a delivery three intervals ago is long gone from the view; suppressing on it would go dark on exactly the regions " +
                        s"the mechanism had fired on, got ${cands.map(_.entry.region)}"
                )
            }
        }
    }

    "a region the model itself recalled in an EARLIER interval is admitted on weaker evidence" in {
        // Sized so the verdict actually flips: ONE content term of middling rarity scores between the two
        // bars, so the same turn is rejected without a prior and admitted with one. Asserting only that a
        // prior "cannot admit fewer" would pass against a prior that does nothing at all.
        val raw = Chunk.from((0 until 8).flatMap { i =>
            val body = if i == 1 || i == 2 then "the quorum decision" else s"unrelated bookkeeping step $i"
            List[Message](tok(am(body, call(s"c$i", "lookup", "{}")), 200), tok(tm(s"c$i", s"step $i finished"), 200))
        })
        val noPrior   = Compaction.State(boundaryCounter = 2)
        val withPrior = Compaction.State(boundaryCounter = 2, recalls = Chunk(RecallRecord(regionOf(1), 1)))
        LLM.run(cfg) {
            val (_, _, dem, v) = demoteAll(raw, Level.Pointer)
            factsFor(raw, v, noPrior).map { (ctx, facts, infos) =>
                val index = armed.buildExpansionIndex(ctx, facts, dem, 2)
                val a     = armed.detect("quorum", index, raw, noPrior, infos).admitted
                val b     = armed.detect("quorum", index, raw, withPrior, infos).admitted
                assert(expansionPriorThreshold < expansionThreshold, "the prior bar is the lower of the two")
                assert(a.isEmpty, s"one middling content term is below the ordinary bar of $expansionThreshold, got ${a.map(_.score)}")
                assert(
                    b.map(_.entry.region) == Chunk(regionOf(1)),
                    s"but a region the model already asked for needs less fresh evidence, got ${b.map(_.entry.region)}"
                )
                assert(
                    b.head.score >= expansionPriorThreshold && b.head.score < expansionThreshold,
                    s"and the score really does sit between the two bars, which is what makes the flip decisive, got ${b.head.score}"
                )
            }
        }
    }

    "a tool call superseding the region's key since the boundary suppresses it" in {
        // The gate scans only the interval suffix past the index watermark, so its cost is the interval's
        // rather than history's; everything before the watermark is already stamped on the entry.
        case class FileArg(path: String) derives Schema
        val writer = Tool.initSuperseding[FileArg](
            name = "writeFile",
            supersession = Tool.Supersession(Tool.Kind.Write, (i: FileArg) => Present(i.path))
        )(_ => ())
        val reader = Tool.initSuperseding[FileArg](
            name = "readFile",
            supersession = Tool.Supersession(Tool.Kind.Read, (i: FileArg) => Present(i.path))
        )(_ => ())
        val infos     = writer.infos.concat(reader.infos).asInstanceOf[Chunk[Tool.internal.Info[?, ?, LLM]]]
        val readKey   = Present(("/p", Tool.Kind.Read)): Maybe[(String, Tool.Kind)]
        val writeKey  = Present(("/p", Tool.Kind.Write)): Maybe[(String, Tool.Kind)]
        val wrote     = Chunk[Message](am("rewriting it", call("z1", "writeFile", """{"path":"/p"}""")))
        val readAgain = Chunk[Message](am("reading it again", call("z2", "readFile", """{"path":"/p"}""")))
        val elsewhere = Chunk[Message](am("other file", call("z3", "writeFile", """{"path":"/other"}""")))
        assert(armed.supersededSince(readKey, wrote, infos), "a later WRITE on the same key supersedes what a read had returned")
        assert(armed.supersededSince(readKey, readAgain, infos), "and so does a later read, because the earlier one merely READ")
        assert(
            !armed.supersededSince(writeKey, readAgain, infos),
            "but a later READ does not supersede an earlier WRITE: the write is still the current content"
        )
        assert(!armed.supersededSince(readKey, elsewhere, infos), "a call on a different key supersedes nothing")
        assert(!armed.supersededSince(readKey, Chunk.empty, infos), "an empty suffix supersedes nothing")
        assert(!armed.supersededSince(Absent, wrote, infos), "and a keyless region cannot be superseded by key")
    }

    "detection is a per-turn guard: the trailing run must be user messages" in {
        val raw    = transcript(4)
        val ending = raw.append(tok(um("first half"), 10)).append(tok(um("and the rest"), 10))
        assert(armed.trailingUserRun(raw).isEmpty, "a transcript ending in a tool result is mid-loop and must not fire")
        assert(
            armed.trailingUserRun(ending) == Chunk(raw.size, raw.size + 1),
            s"two user messages with no generation between them are ONE turn, so the whole run is the turn, got ${armed.trailingUserRun(ending)}"
        )
        assert(armed.trailingUserRun(Chunk.empty).isEmpty, "and an empty transcript has no turn")
    }

    "rarity is bounded at both ends and rises with BPE rank" in {
        // Unbounded, one exotic token would dominate a score and the admission thresholds would have no
        // stable units; with no floor a merely-uncommon word would contribute nothing and the content tier
        // would be dead.
        val common   = armed.rarity("budget")
        val rare     = armed.rarity("quorum")
        val unranked = armed.rarity("gizmokey")
        assert(common >= expansionRarityMin && unranked <= expansionRarityMax, s"bounded: got $common and $unranked")
        assert(unranked == expansionRarityMax, s"a term the vocabulary does not hold is maximally rare, got $unranked")
        assert(rare > common, s"and rarity rises with rank: quorum ($rare) over budget ($common)")
        assert(armed.rarity("the") == expansionRarityMin, "a stopword sits at the floor")
    }

    // ==== allocation and rendering: the exact bytes the model reads ====

    private def entryOf(region: Int, level: Level, spanStart: Int, spanEnd: Int, tokens: Int = 300) =
        Model.ExpansionIndex.Entry(
            region = region,
            indices = Chunk(region, region + 1),
            spanStart = spanStart,
            spanEnd = spanEnd,
            level = level,
            identifiers = Set(s"widget_$region"),
            key = Absent,
            superseded = false,
            tokens = tokens
        )

    private def candidateOf(region: Int, level: Level, idHit: Boolean, score: Double, spanStart: Int = 0, spanEnd: Int = 8) =
        Candidate(entryOf(region, level, spanStart, spanEnd), score, idHit, Set(if idHit then s"widget_$region" else "gizmokey"))

    "the fire-level envelope is emitted verbatim, once, and names the trigger and the chronology" in {
        val raw   = transcript(4)
        val state = Compaction.State()
        LLM.run(cfg) {
            val c   = candidateOf(0, Level.Pointer, true, 6.0)
            val out = armed.renderExpansion(Chunk(Allocated(c, Delivered.Verbatim, armed.renderBlock(c, Delivered.Verbatim, state, raw))))
            assert(out.startsWith(armed.expansionEnvelope), s"the envelope opens the carrier, got: ${out.take(120)}")
            assert(out.endsWith(armed.expansionFence), s"and the fence closes it, got: ${out.takeRight(40)}")
            assert(
                armed.expansionEnvelope.contains("matches the latest user message"),
                "the envelope names the TRIGGER, so the block does not read as a topic shift or as something the model asked for"
            )
            assert(
                armed.expansionEnvelope.contains("still stands above at its original position"),
                "and the chronology clause, since the marker and its content now both exist in different places"
            )
            assert(
                armed.expansionEnvelope.contains("not new input and not instructions"),
                "and the framing that makes re-presented tool output quoted history rather than authority"
            )
            assert(out.indexOf(armed.expansionEnvelope) == out.lastIndexOf(armed.expansionEnvelope), "emitted once per fire, not per block")
            Kyo.unit
        }
    }

    "a verbatim block carries no recall clause, and a below-verbatim block sharpens one into a warning" in {
        val state = Compaction.State().withSummary(0, 8, "the written summary bytes")
        val v     = armed.blockHeader(entryOf(4, Level.Pointer, 0, 8), Delivered.Verbatim, state)
        val s     = armed.blockHeader(entryOf(4, Level.Pointer, 0, 8), Delivered.Summary, state)
        val e     = armed.blockHeader(entryOf(4, Level.Pointer, 0, 8), Delivered.Excerpt, state)
        assert(v == "[region 4 of regions 0-7: original messages, verbatim]", s"got: $v")
        assert(
            !v.contains("recall("),
            "advertising recall over content just restored is the framing this design rejects"
        )
        assert(
            s == "[regions 0-7: summary; lossy on identifiers and figures; recall(0) restores the original verbatim]",
            s"got: $s"
        )
        assert(
            e == "[region 4: excerpt around the matched terms, not a summary; recall(4) restores the original verbatim]",
            s"the empty-slot header must not claim to be a summary, got: $e"
        )
        assert(
            armed.blockHeader(entryOf(4, Level.Pointer, 4, 6), Delivered.Verbatim, state) == "[region 4: original messages, verbatim]",
            "a span covering only its own region is named once rather than twice"
        )
    }

    "the analysis dependency is named in the header without its bytes being delivered" in {
        val state = Compaction.State().withAnalysis(RegionAnalysis(4, Chunk(Relation(1, RelationKind.DependsOn))))
        val h     = armed.blockHeader(entryOf(4, Level.Pointer, 0, 8), Delivered.Verbatim, state)
        assert(
            h == "[region 4 of regions 0-7: original messages, verbatim; depends on region 1, not included (recall(1))]",
            s"budget belongs to matched evidence rather than to inference, so the dependency is NAMED, not sent. Got: $h"
        )
    }

    "an identifier match is verbatim or nothing: it is never stepped down under budget pressure" in {
        val state = Compaction.State().withSummary(0, 8, "cheap summary bytes that would have fit")
        val raw   = transcript(8)
        LLM.run(cfg) {
            val expensive = candidateOf(0, Level.Pointer, true, 6.0)
            val allocated = armed.allocate(Chunk(expensive), 50, state, raw)
            assert(
                allocated.isEmpty,
                s"a stepped-down identifier match IS the plausible substitute this mechanism exists to prevent, so it skips instead. Got $allocated"
            )
            val afforded = armed.allocate(Chunk(expensive), 100000, state, raw)
            assert(
                afforded.map(_.delivered) == Chunk(Delivered.Verbatim),
                s"and with room it goes out verbatim, got ${afforded.map(_.delivered)}"
            )
            Kyo.unit
        }
    }

    "content candidates sharing one summary slot deliver once, while empty-slot ones do not coalesce" in {
        val raw      = transcript(8)
        val withSlot = Compaction.State().withSummary(0, 8, "one summary standing for the whole span")
        val a        = candidateOf(0, Level.Pointer, false, 4.0)
        val b        = candidateOf(2, Level.Pointer, false, 3.5)
        LLM.run(cfg) {
            val coalesced = armed.allocate(Chunk(a, b), 100000, withSlot, raw)
            assert(
                coalesced.size == 1 && coalesced.head.candidate.entry.region == 0,
                s"both would deliver the SAME slot bytes, so one span delivers once and the stronger evidence wins. Got ${coalesced.map(_.candidate.entry.region)}"
            )
            val separate = armed.allocate(Chunk(a, b), 100000, Compaction.State(), raw)
            assert(
                separate.map(_.candidate.entry.region).toSet == Set(0, 2),
                s"with no slot each renders an excerpt from its OWN region, so they carry different evidence and coalescing would discard a real match. " +
                    s"Got ${separate.map(_.candidate.entry.region)}"
            )
            assert(separate.map(_.delivered).toSet == Set(Delivered.Excerpt), s"and both are excerpts, got ${separate.map(_.delivered)}")
            Kyo.unit
        }
    }

    "at most the per-turn cap of regions goes out in one fire" in {
        val raw   = transcript(12)
        val cands = Chunk.from((0 until 5).map(i => candidateOf(i * 2, Level.Pointer, true, 6.0 - i)))
        LLM.run(cfg) {
            val allocated = armed.allocate(cands, 100000, Compaction.State(), raw)
            assert(
                allocated.size == expansionPerTurn,
                s"the caps bound how many times the mechanism may GUESS, which a token budget alone would not: a budget permits " +
                    s"an unbounded number of cheap wrong guesses. Got ${allocated.size} against a cap of $expansionPerTurn"
            )
            assert(
                allocated.map(_.candidate.score) == Chunk(6.0, 5.0),
                s"and the strongest evidence goes first, got ${allocated.map(_.candidate.score)}"
            )
            Kyo.unit
        }
    }

    "the excerpt is centred on the matched terms rather than on the region's edges" in {
        // The defect this renderer exists to fix: the substitute elision cuts by POSITION, so a matched
        // term in the middle of a long region never appears in what it produces.
        val filler = "irrelevant padding text. " * 200
        val body   = s"$filler the gizmokey warmup path was reconfigured $filler"
        val raw    = Chunk[Message](tok(am(body, call("c0", "lookup", "{}")), 400), tok(tm("c0", "ok"), 10))
        val e      = entryOf(0, Level.Pointer, 0, 2)
        LLM.run(cfg) {
            val excerpt = armed.matchCentredExcerpt(e, raw, Set("gizmokey", "warmup"), substituteElisionChars)
            val elided  = armed.substituteElision(body, substituteElisionChars)
            assert(excerpt.contains("gizmokey"), s"the excerpt must carry the evidence that triggered the fire, got: ${excerpt.take(200)}")
            assert(excerpt.contains("warmup"), "and every matched term when they fit one window")
            assert(
                !elided.contains("gizmokey"),
                "while the positional elision does NOT, which is the whole reason this renderer exists rather than reusing it"
            )
            assert(excerpt.length <= substituteElisionChars + 40, s"and it stays in the elision's budget class, got ${excerpt.length}")
            Kyo.unit
        }
    }

    "the envelope is charged once, before any candidate is priced" in {
        val raw = transcript(8)
        LLM.run(cfg) {
            val c        = candidateOf(0, Level.Pointer, true, 6.0)
            val envelope = armed.envelopeCost
            val block    = armed.blockCost(armed.renderBlock(c, Delivered.Verbatim, Compaction.State(), raw))
            assert(
                armed.allocate(Chunk(c), envelope + block, Compaction.State(), raw).size == 1,
                "a budget covering envelope plus block fits it"
            )
            assert(
                armed.allocate(Chunk(c), envelope + block - 1, Compaction.State(), raw).isEmpty,
                "and one token less does not, so the envelope really is deducted rather than ignored"
            )
            Kyo.unit
        }
    }

    "the budget is a share of headroom to the TRIGGER, clamped by the ceiling and the hard limit" in {
        val axis = armed.ax(cfg)
        // Asserted as EQUALITIES rather than upper bounds. A constant-zero budget satisfies every "at
        // most" claim, so bounds alone would pass against an implementation that never funds a fire.
        val roomy = armed.expansionBudget(cfg, axis.high - 40000)
        assert(roomy == expansionFireCeiling, s"far from the trigger the absolute ceiling binds, got $roomy")
        val near = armed.expansionBudget(cfg, axis.high - 1000)
        assert(
            near == (1000 * expansionShare).toInt,
            s"nearer it, the budget is exactly the configured share of the REMAINING headroom, which is what bounds " +
                s"the mechanism's own contribution geometrically. Got $near"
        )
        assert(near > 0, "and it is positive, or no fire could ever be funded")
        assert(armed.expansionBudget(cfg, axis.hard + 1000) == 0, "past the hard limit nothing is affordable at all")
    }

    "a fire after a boundary reads the RENDERED view, not the stale usage anchor" in {
        // The defect this guards: occupancy is anchored on the provider's last reported total plus the
        // suffix past the size that total covered. A boundary rewrites the served list, so the suffix is
        // empty and the anchored reading returns the PRE-boundary total, which sits at the trigger by
        // construction because that is what fired the boundary. Every boundary-turn fire would then see no
        // headroom and degenerate to a seed-only record, on exactly the turns the design wires the
        // boundary path for.
        val raw   = transcript(8)
        val big   = Compaction.State(lastUsage = Present(armed.ax(cfg).high), lastUsageRawSize = 40)
        val small = Context(raw, Chunk(tok(sm("a small rendered view"), 50))).withCompaction(big)
        val stale = armed.expansionBudget(cfg, armed.servedOccupancy(small, rewritten = false))
        val fresh = armed.expansionBudget(cfg, armed.servedOccupancy(small, rewritten = true))
        assert(stale == 0, s"reading the stale anchor leaves nothing to spend, which is the bug, got $stale")
        assert(
            fresh > 0,
            s"reading the view the boundary just rendered leaves real headroom, got $fresh"
        )
    }

    // ==== delivery: the one point in the turn where the mechanism acts ====

    // A session mid-interval: a demoted view, a seated index, and a transcript ending in a user turn that
    // names one demoted region by an identifier it introduced.
    private def fireFixture(turn: String = "remind me what widget_1 returned", steps: Int = 8)(using Frame) =
        val base               = transcript(steps)
        val raw                = base.append(tok(um(turn), 20))
        val (_, _, dem, view0) = demoteAll(base, Level.Pointer)
        val view               = view0.append(tok(um(turn), 20))
        val ctx                = Context(raw, view)
        Preparation.init.map { prep =>
            Tool.internal.infos.map { infos =>
                val facts = armed.boundaryFacts(Context(base, view0), cfg, infos)
                val index = armed.buildExpansionIndex(Context(base, view0), facts, dem, 0)
                prep.expansion.set(Present(index)).andThen((ctx, prep, index))
            }
        }
    end fireFixture

    "a fire appends exactly one origin-free carrier and leaves raw by reference" in {
        LLM.run(cfg) {
            fireFixture().map { (ctx, prep, _) =>
                armed.expandForTurn(ctx, cfg, prep).map {
                    case Compactor.Decision.Unchanged => fail("the fixture must fire, or nothing below is exercised")
                    case Compactor.Decision.Compacted(out) =>
                        assert(out.raw eq ctx.raw, "raw is handed back by reference: an expansion never touches the transcript of record")
                        assert(
                            out.compacted.size == ctx.compacted.size + 1,
                            s"exactly one coalesced carrier per fire, not one per delivered region, got ${out.compacted.size - ctx.compacted.size}"
                        )
                        assert(
                            out.compacted.dropRight(1) == ctx.compacted,
                            "and everything before it is untouched, which is what keeps the provider's prefix cache alive"
                        )
                        val carrier = out.compacted.last
                        assert(
                            carrier.origin.isEmpty,
                            "the carrier carries NO origin: an origin would alias the consumers that assume one view entry per origin"
                        )
                        assert(
                            carrier.isInstanceOf[SystemMessage],
                            "and it is a SystemMessage, the register every framework byte already uses"
                        )
                        assert(
                            carrier.content.startsWith(armed.expansionEnvelope),
                            "opening with the framing line rather than with bare quoted history"
                        )
                }
            }
        }
    }

    "a fire writes its records into the returned context, so record and delivery commit together" in {
        LLM.run(cfg) {
            fireFixture().map { (ctx, prep, _) =>
                armed.expandForTurn(ctx, cfg, prep).map {
                    case Compactor.Decision.Unchanged => fail("the fixture must fire")
                    case Compactor.Decision.Compacted(out) =>
                        val recs = out.compactionState.expansions
                        assert(recs.nonEmpty, "a fire records what it did, or the next boundary's seed never learns of it")
                        assert(
                            recs.forall(_.turnStamp == ctx.raw.size - 1),
                            s"the turn stamp is the trailing user run's first raw ordinal, so no counter is stored, got ${recs.map(_.turnStamp)}"
                        )
                        assert(
                            recs.exists(r => r.evidence == Evidence.Identifier && r.delivered == Delivered.Verbatim),
                            s"an identifier fire is recorded as such, since the damper is keyed on evidence and not on what went out, got $recs"
                        )
                }
            }
        }
    }

    "the same turn fires once: a retry finds its own record and answers Unchanged" in {
        LLM.run(cfg) {
            fireFixture().map { (ctx, prep, _) =>
                armed.expandForTurn(ctx, cfg, prep).map {
                    case Compactor.Decision.Unchanged => fail("the fixture must fire")
                    case Compactor.Decision.Compacted(first) =>
                        armed.expandForTurn(first, cfg, prep).map { second =>
                            assert(
                                second == Compactor.Decision.Unchanged,
                                s"a retried turn carries the same trailing run and so the same stamp, and its first carrier is already " +
                                    s"in the served list, so firing again would append a second. Got $second"
                            )
                        }
                }
            }
        }
    }

    "the interval cap bounds how many times the mechanism may guess, whatever the budget allows" in {
        // The budget bounds the tokens a fire may spend; the caps bound the guesses. A budget alone would
        // permit an unbounded number of cheap wrong ones, which is the failure a guessing mechanism most
        // needs bounded, and it is what a hostile turn or a pasted log reaches every interval.
        LLM.run(cfg) {
            fireFixture().map { (ctx, prep, index) =>
                // Fires, not records: a record is replaced by an escalation, so counting surviving
                // records undercounts and let a session escalate its way past the cap.
                val capped = (0 until expansionPerInterval).foldLeft(ctx.compactionState)((st, i) => st.withExpansionTurn(900 + i))
                armed.expandForTurn(ctx.withCompaction(capped), cfg, prep).map { d =>
                    assert(
                        d == Compactor.Decision.Unchanged,
                        s"$expansionPerInterval turns have already fired in this interval, so the next is refused before detection even runs. Got $d"
                    )
                }
            }
        }
    }

    "a turn that detects nothing answers Unchanged, so the default path stays byte-identical" in {
        LLM.run(cfg) {
            fireFixture("what do you think about that?").map { (ctx, prep, _) =>
                armed.expandForTurn(ctx, cfg, prep).map { d =>
                    assert(d == Compactor.Decision.Unchanged, s"no detection, no answer, no bytes: got $d")
                }
            }
        }
    }

    "a detected need that nothing can afford still records, so the need reaches the next boundary" in {
        LLM.run(cfg) {
            fireFixture().map { (ctx, prep, _) =>
                // Occupancy pinned just under the trigger leaves a budget too small for any block.
                val axis    = armed.ax(cfg)
                val starved = ctx.withCompaction(ctx.compactionState.withUsage(axis.high - 1, ctx.compacted.size))
                armed.expandForTurn(starved, cfg, prep).map {
                    case Compactor.Decision.Unchanged => fail("a detected candidate must still record, even when nothing is affordable")
                    case Compactor.Decision.Compacted(out) =>
                        assert(
                            out.compacted == starved.compacted,
                            "nothing goes out, so the served list is untouched and the answer is prefix-stable trivially"
                        )
                        assert(
                            out.compactionState.expansions.forall(_.delivered == Delivered.SeedOnly),
                            s"and the record says SeedOnly, which suppresses nothing and so cannot block a later delivery. Got ${out.compactionState.expansions}"
                        )
                }
            }
        }
    }

    "with the mechanism off nothing fires, and the below-trigger arm is untouched" in {
        val off = Default(Compactor.Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Expansion), calibration)
        LLM.run(cfg) {
            fireFixture().map { (ctx, prep, _) =>
                off.expandForTurn(ctx, cfg, prep).map { d =>
                    assert(d == Compactor.Decision.Unchanged, s"the off switch is total: no detection, no carrier, no record. Got $d")
                }
            }
        }
    }

    "a restore that lost the session cell re-derives the index rather than going dark for an interval" in {
        // The trap this covers: an Absent cell means BOTH "no boundary has fired yet", where there is
        // genuinely nothing to expand, and "a restore lost it", where there is plenty. Reading it as the
        // first alone compiles and passes every other test while permanently disabling the restore path.
        LLM.run(cfg) {
            fireFixture().map { (ctx, _, _) =>
                Preparation.init.map { fresh =>
                    Tool.internal.infos.map { infos =>
                        armed.expansionIndexFor(ctx, cfg, fresh, infos).map {
                            case Absent => fail("the served view carries demoted markers, so there is something to expand")
                            case Present(rebuilt) =>
                                assert(
                                    rebuilt.source == Model.ExpansionIndex.Source.Restored,
                                    "and the fire can name which construction it read, which is what makes a drift between the two visible"
                                )
                                armed.expandForTurn(ctx, cfg, fresh).map { d =>
                                    assert(
                                        d != Compactor.Decision.Unchanged,
                                        s"a restored session fires on the same turn a live one would, got $d"
                                    )
                                }
                        }
                    }
                }
            }
        }
    }

    "before the first boundary an absent cell correctly means nothing to expand" in {
        LLM.run(cfg) {
            val raw = transcript(4).append(tok(um("remind me what widget_1 returned"), 20))
            Preparation.init.map { prep =>
                armed.expandForTurn(Context(raw, raw), cfg, prep).map { d =>
                    assert(
                        d == Compactor.Decision.Unchanged,
                        s"nothing is demoted, so the same Absent cell must NOT trigger a re-derivation, got $d"
                    )
                }
            }
        }
    }

    "the append-only assertion is a predicate, and it refuses a rewritten prefix" in {
        // Asserted directly rather than through `compact`, because the Default compactor never constructs
        // a bad answer: a test that could only reach the check through the compactor could never fire it.
        val served = Chunk[Message](sm("a"), sm("b"))
        assert(armed.servedPrefixStable(served, served.append(sm("c"))), "an append extends the prefix")
        assert(armed.servedPrefixStable(served, served), "and an unchanged list trivially does")
        assert(!armed.servedPrefixStable(served, Chunk(sm("a"), sm("CHANGED"), sm("c"))), "but a rewritten entry is not an append")
        assert(!armed.servedPrefixStable(served, Chunk(sm("a"))), "and neither is a truncation")
        LLM.run(cfg) {
            val ctx = Context(Chunk(sm("a")), served)
            Abort.run[AIGenException](armed.answerAppendOnly(ctx, ctx.copy(compacted = Chunk(sm("a"))))).map { r =>
                assert(
                    r match
                        case Result.Failure(_: AICompactionException) => true
                        case _                                        => false
                    ,
                    s"a rewritten prefix fails the turn rather than silently invalidating the cache from the edit onward, got $r"
                )
            }
        }
    }

    "a span-grain delivery records every matched member, so the same bytes never go out twice" in {
        val state = Compaction.State().withSummary(0, 8, "one summary standing for the whole span")
        val a     = candidateOf(0, Level.Pointer, false, 4.0)
        val b     = candidateOf(2, Level.Pointer, false, 3.5)
        LLM.run(cfg) {
            val raw       = transcript(8)
            val allocated = armed.allocate(Chunk(a, b), 100000, state, raw)
            val recorded  = armed.recordFire(state, Chunk(a, b), allocated, 3, 40)
            assert(allocated.size == 1, "one span, one delivery")
            assert(
                recorded.expansions.map(_.region).toSet == Set(0, 2),
                s"but a record for EVERY matched member, or the second region matched on the next turn carries no record of the " +
                    s"first delivery and the identical bytes go out again. Got ${recorded.expansions.map(_.region)}"
            )
            assert(
                recorded.expansions.forall(_.delivered == Delivered.Summary),
                s"and both say what actually went out, got ${recorded.expansions.map(_.delivered)}"
            )
            Kyo.unit
        }
    }

    "the delivery drops at the next boundary with nothing having to retire it" in {
        LLM.run(cfg) {
            fireFixture().map { (ctx, prep, _) =>
                armed.expandForTurn(ctx, cfg, prep).map {
                    case Compactor.Decision.Unchanged => fail("the fixture must fire")
                    case Compactor.Decision.Compacted(withCarrier) =>
                        assert(withCarrier.compacted.exists(_.content.startsWith(armed.expansionEnvelope)), "the carrier is resident")
                        Tool.internal.infos.map { infos =>
                            val facts = armed.boundaryFacts(withCarrier, cfg, infos)
                            armed.renderView(withCarrier, cfg, facts).map { (rebuilt, _) =>
                                assert(
                                    !rebuilt.exists(_.content.startsWith(armed.expansionEnvelope)),
                                    "and the next boundary rebuilds the view from raw, where the carrier never was, so residency ends " +
                                        "with no retirement step to forget"
                                )
                            }
                        }
                }
            }
        }
    }

    // ==== the consumers: the damper, eviction's ordering, and the recall tool ====

    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    "an expansion seed enters at a fraction of a recall's, keyed on evidence and decaying on the same clock" in {
        val raw   = Chunk.from((0 until 16).map(i => am(s"region $i")))
        val units = armed.group(raw)
        val base  = Compaction.State(boundaryCounter = 5)
        def seedAt(state: Compaction.State) =
            armed.seedVector(units, raw, state).get(14).getOrElse(0.0) - armed.seedVector(units, raw, base).get(14).getOrElse(0.0)
        val ask     = seedAt(base.withRecall(14))
        val idFire  = seedAt(base.withExpansion(ExpansionRecord(14, 5, 1, Evidence.Identifier, Delivered.Verbatim)))
        val cntFire = seedAt(base.withExpansion(ExpansionRecord(14, 5, 1, Evidence.Content, Delivered.Summary)))
        assert(eps(ask, recallSeedWeight), s"the tool's own ask is the full weight, got $ask")
        assert(eps(idFire, expansionSeedFractionIdentifier * recallSeedWeight), s"an identifier fire is damped to a half, got $idFire")
        assert(eps(cntFire, expansionSeedFractionContent * recallSeedWeight), s"a content fire to a quarter, got $cntFire")
        // Damping a geometrically decaying seed has an exact meaning: PRE-AGING. A record at fraction f is
        // indistinguishable in seed space from a tool record log2(1/f) boundaries older, which is what
        // positions the guess in the same channel and the same space as the ask it is weaker than.
        val agedOne = seedAt(base.withRecall(14).copy(recalls = Chunk(RecallRecord(14, 4))))
        assert(eps(idFire, agedOne), s"an identifier fire IS an ask one boundary old: $idFire against $agedOne")
        val agedTwo = seedAt(base.copy(recalls = Chunk(RecallRecord(14, 3))))
        assert(eps(cntFire, agedTwo), s"and a content fire is one two boundaries old: $cntFire against $agedTwo")
    }

    "in SCORE space an expansion seed moves its own region by exactly restartWeight times its weight" in {
        // The one topology-independent quantity in the ranking, and the reason the seed is the right
        // carrier for a guarantee: every edge points at an earlier region, so the graph is a backward DAG
        // and injected mass flows away and never returns.
        val raw       = Chunk.from((0 until 16).map(i => am(s"region $i")))
        val units     = armed.group(raw)
        val base      = Compaction.State(boundaryCounter = 5)
        val fired     = base.withExpansion(ExpansionRecord(14, 5, 1, Evidence.Identifier, Delivered.Verbatim))
        val graph     = armed.deriveGraph(units, raw, Dict.empty[Int, Int])
        val before    = armed.score(units, graph, Dict.empty[Int, Int], armed.seedVector(units, raw, base)).get(14).getOrElse(0.0)
        val after     = armed.score(units, graph, Dict.empty[Int, Int], armed.seedVector(units, raw, fired)).get(14).getOrElse(0.0)
        val seedDelta = expansionSeedFractionIdentifier * recallSeedWeight
        assert(
            eps(after - before, restartWeight * seedDelta, tol = 1e-6),
            s"a seed of $seedDelta moves its own SCORE by exactly restartWeight times that, got ${after - before}"
        )
        assert(
            after - before < Compactor.internal.keepFloor(units.size, Compactor.Tuning()),
            s"and that is BELOW the keep floor at ${units.size} regions, so a single framework guess can never reinstate on its own, " +
                s"which is the whole point of damping it: ${after - before} against ${Compactor.internal.keepFloor(units.size, Compactor.Tuning())}"
        )
    }

    "sustained guessing saturates at exactly one fresh explicit ask" in {
        // One record per region per interval, halving each boundary, converges to f*w/(1-decay). At a half
        // that ceiling is exactly recallSeedWeight, so persistent framework guessing can never outweigh a
        // single fresh ask from the model, and anything beyond that must come from the conversation's own
        // minted edges.
        val ceiling = expansionSeedFractionIdentifier * recallSeedWeight / (1.0 - recallDecay)
        assert(eps(ceiling, recallSeedWeight), s"the identifier tier's accumulation ceiling is one fresh ask, got $ceiling")
        assert(
            expansionSeedFractionIdentifier <= 0.5 && expansionSeedFractionContent >= 0.15,
            s"and both fractions sit inside the derived safe range [0.15, 0.5]: below it the record is dead weight under any " +
                s"realistic floor, above it the weaker-evidence premise has no arithmetic expression at the boundary that decides"
        )
    }

    "the damped seed decays on recall's own clock and is pruned before it can mislead" in {
        val raw   = Chunk.from((0 until 16).map(i => am(s"region $i")))
        val units = armed.group(raw)
        def seedAtAge(age: Int) =
            val st =
                Compaction.State(boundaryCounter = age).withExpansion(ExpansionRecord(14, 0, 1, Evidence.Identifier, Delivered.Verbatim))
            armed.seedVector(units, raw, st).get(14).getOrElse(0.0) -
                armed.seedVector(units, raw, Compaction.State(boundaryCounter = age)).get(14).getOrElse(0.0)
        end seedAtAge
        assert(eps(seedAtAge(1), seedAtAge(0) * recallDecay), "one boundary halves it")
        assert(eps(seedAtAge(2), seedAtAge(0) * recallDecay * recallDecay), "and two halve it again")
    }

    "an expansion seeds even with the recall tool disabled" in {
        // The reason this is its own mechanism entry rather than riding Recall: a deployment that removes
        // the tool is precisely the one that needs the framework's own path.
        val noTool = Default(Compactor.Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Recall), calibration)
        val raw    = Chunk.from((0 until 16).map(i => am(s"region $i")))
        val units  = noTool.group(raw)
        val state  = Compaction.State(boundaryCounter = 2).withExpansion(ExpansionRecord(14, 2, 1, Evidence.Identifier, Delivered.Verbatim))
        val delta =
            noTool.seedVector(units, raw, state).get(14).getOrElse(0.0) -
                noTool.seedVector(units, raw, Compaction.State(boundaryCounter = 2)).get(14).getOrElse(0.0)
        assert(delta > 0.0, s"the framework's seed survives the tool being switched off, got $delta")
        val offBoth = Default(Compactor.Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Expansion), calibration)
        val offDelta =
            offBoth.seedVector(units, raw, state).get(14).getOrElse(0.0) -
                offBoth.seedVector(units, raw, Compaction.State(boundaryCounter = 2)).get(14).getOrElse(0.0)
        assert(offDelta == 0.0, s"but switching the mechanism itself off silences a leftover record, got $offDelta")
    }

    "eviction prefers victims nothing has asked for, and falls through when they cannot free enough" in {
        val raw               = transcript(24)
        val (_, _, dem, view) = demoteAll(raw, Level.Pointer)
        // Sized so eviction frees LESS than the evictable set holds. It always frees roughly (high - low)
        // of raw, so the cap is chosen to put that below the evictable total; at a tighter cap the whole
        // evictable set goes either way and there is no ordering left to observe.
        val tight = Compactor.Tuning(rawRetentionCap = Present(9600))
        val arm   = Default(tight, calibration)
        LLM.run(cfg) {
            // Evidence on the OLDEST demoted region, which ordinal order would otherwise take first.
            val units      = arm.group(raw)
            val demotedIds = view.flatMap(_.origin.toList.map(_.start)).toList.sorted
            val oldest     = demotedIds.head
            val guarded = Context(raw, view).withCompaction(
                Compaction.State().withExpansion(ExpansionRecord(oldest, 0, 1, Evidence.Identifier, Delivered.Verbatim))
            )
            val plain                 = Context(raw, view)
            val afterGuarded          = arm.evict(guarded, cfg)
            val afterPlain            = arm.evict(plain, cfg)
            def forgotten(c: Context) = c.raw.zipWithIndex.collect { case (m, i) if m.origin.isDefined => i }.toSet
            assert(forgotten(afterPlain).nonEmpty, "the fixture must actually force eviction")
            val evictable = view.flatMap(_.origin.toList).foldLeft(0)((n, o) => n + (o.end - o.start))
            assert(
                forgotten(afterPlain).size < evictable,
                s"and must stop PART WAY THROUGH THE EVICTABLE SET, or there is no ordering left to observe: it forgot " +
                    s"${forgotten(afterPlain).size} ordinals of $evictable evictable"
            )
            // DIRECTION-SENSITIVE. Asserting only that the two sets differ would pass against an
            // implementation that evicts evidenced ranges FIRST, which is the exact opposite of the rule.
            val guardedOrdinals = units.filter(_.id == oldest).flatMap(_.indices).toSet
            assert(
                guardedOrdinals.subsetOf(forgotten(afterPlain)),
                s"with no evidence the oldest range goes first, or the fixture proves nothing: " +
                    s"$guardedOrdinals against ${forgotten(afterPlain)}"
            )
            assert(
                guardedOrdinals.intersect(forgotten(afterGuarded)).isEmpty,
                s"and with a record on it, eviction spends the cap elsewhere first: $guardedOrdinals still in ${forgotten(afterGuarded)}"
            )
            assert(
                forgotten(afterGuarded).nonEmpty,
                "but it is ordering rather than a veto: the retention cap stays unconditional, so evidence never stops eviction outright"
            )
            Kyo.unit
        }
    }

    "recall on a verbatim resident copy points at it; on anything less it sends the bytes" in {
        val head = Chunk.from((0 until 14).map(i => am(s"m$i")))
        // Fused into ONE region by the call, so the marker's origin (14, 16) really is the region's own
        // range; without the call these are two unrelated regions and the coverage test correctly refuses.
        val region = Chunk[Message](am("ASSISTANT PAYLOAD", call("c1", "lookup", "{}")), tm("c1", "TOOL PAYLOAD"))
        val raw    = head.concat(region)
        val marker = SystemMessage("[region 14 compacted]", origin = Present(Context.Origin(14, 16, 16)))
        def ctxWith(d: Delivered) =
            Context(raw, Chunk(marker)).withCompaction(
                Compaction.State(boundaryCounter = 3).withExpansion(ExpansionRecord(14, 3, 1, Evidence.Identifier, d))
            )
        LLM.run(cfg) {
            AI.init.map { ai =>
                ai.setContext(ctxWith(Delivered.Verbatim)).andThen {
                    armed.recallTool(ai).infos.head.decodeAndRun("""{"id":14}""").map { pointed =>
                        ai.setContext(ctxWith(Delivered.Summary)).andThen {
                            armed.recallTool(ai).infos.head.decodeAndRun("""{"id":14}""").map { sent =>
                                def out(r: Tool.internal.RunOutcome) = r match
                                    case Tool.internal.RunOutcome.Ran(Result.Success(o), _) => o.toString
                                    case other                                              => s"unexpected: $other"
                                assert(
                                    out(pointed).contains("already present verbatim"),
                                    s"a verbatim copy covering the tool's whole range needs no second copy, got: ${out(pointed)}"
                                )
                                assert(
                                    out(sent).contains("assistant: ASSISTANT PAYLOAD"),
                                    s"but a summary-tier resident copy is LESS than the tool's published contract, so pointing at it " +
                                        s"would be a silent violation at the one moment the model has demonstrated it wants the original. " +
                                        s"Got: ${out(sent)}"
                                )
                                ai.context.map { after =>
                                    assert(
                                        after.compactionState.recalls.exists(_.region == 14),
                                        "and either way the ask is recorded, because the model asking is need evidence regardless of what the framework guessed"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a delivery is priced by the bytes it actually emits, not by the region's stamp" in {
        // The defect this guards, found by CompactorRecallTest rather than by reasoning: a verbatim block
        // was priced at the region's stamped token count, which is an apportioned share of the provider's
        // count for the ORIGINAL messages, while what goes out is those messages role-tagged under a
        // header. One fire went out at roughly 1278 tokens against a budget of 779, so the clamp meant to
        // stop a between-boundaries append crossing the hard limit was not clamping.
        val raw = Chunk[Message](
            tok(am("a long assistant turn " * 40, call("c0", "lookup", "{}")), 20),
            tok(tm("c0", "and a long tool result " * 40), 20)
        )
        val c = candidateOf(0, Level.Pointer, true, 6.0, spanStart = 0, spanEnd = 2)
        LLM.run(cfg) {
            val block = armed.renderBlock(c, Delivered.Verbatim, Compaction.State(), raw)
            assert(
                armed.blockCost(block) > c.entry.tokens,
                s"the fixture must make the two prices differ, or it proves nothing: rendered ${armed.blockCost(block)} " +
                    s"against a stamp of ${c.entry.tokens}"
            )
            val budget    = armed.envelopeCost + armed.blockCost(block) - 1
            val allocated = armed.allocate(Chunk(c), budget, Compaction.State(), raw)
            assert(
                allocated.isEmpty,
                s"a budget one token short of the RENDERED price must refuse; pricing by the stamp would have admitted it"
            )
            val ok = armed.allocate(Chunk(c), budget + 1, Compaction.State(), raw)
            assert(ok.size == 1, "and one token more admits it")
            assert(
                armed.blockCost(ok.head.block) == armed.blockCost(block),
                "the block carried out is the block that was priced, which is what makes the two unable to disagree"
            )
            Kyo.unit
        }
    }

    "the mechanism's total seed voice is bounded at one fresh ask, however many regions it guesses about" in {
        // The design derived a per-region ceiling and stopped, which left the AGGREGATE unbounded: the fire
        // caps admit a few regions per turn and records live for a prune horizon of boundaries, so dozens
        // can hold a live record at once and their sum can rival the whole designed seed budget, which sums
        // to 1.0. Measured on a long tool-heavy session, that kept enough regions above the keep floor to
        // hold the served view near the trigger indefinitely.
        val raw   = Chunk.from((0 until 40).map(i => am(s"region $i")))
        val units = armed.group(raw)
        val many = (0 until 30).foldLeft(Compaction.State(boundaryCounter = 4)) { (st, i) =>
            st.withExpansion(ExpansionRecord(i, 4, i, Evidence.Identifier, Delivered.Verbatim))
        }
        val base  = armed.seedVector(units, raw, Compaction.State(boundaryCounter = 4))
        val fired = armed.seedVector(units, raw, many)
        val total = (0 until 40).foldLeft(0.0)((a, id) => a + (fired.get(id).getOrElse(0.0) - base.get(id).getOrElse(0.0)))
        assert(
            total <= recallSeedWeight + 1e-9,
            s"thirty live records must together be worth at most one fresh explicit ask, got $total against $recallSeedWeight"
        )
        // Scaled rather than truncated, so the bound changes how loud the mechanism is and never which
        // regions it favours.
        val idOnly = fired.get(0).getOrElse(0.0) - base.get(0).getOrElse(0.0)
        val cntMany = (0 until 30).foldLeft(Compaction.State(boundaryCounter = 4)) { (st, i) =>
            st.withExpansion(ExpansionRecord(i, 4, i, if i == 0 then Evidence.Identifier else Evidence.Content, Delivered.Summary))
        }
        val mixed  = armed.seedVector(units, raw, cntMany)
        val first  = mixed.get(0).getOrElse(0.0) - base.get(0).getOrElse(0.0)
        val second = mixed.get(1).getOrElse(0.0) - base.get(1).getOrElse(0.0)
        assert(
            idOnly > 0.0 && first > second,
            s"an identifier record still outweighs a content one under the bound: $first against $second"
        )
        assert(
            math.abs(first / second - expansionSeedFractionIdentifier / expansionSeedFractionContent) < 1e-9,
            s"and by exactly the ratio the evidence classes establish, got ${first / second}"
        )
    }

    // ==== observability: a guessing layer that cannot degrade silently ====

    "every matched region yields either a candidate or a named reason, never neither" in {
        // The property the closed set exists for, and it is closed by the SIGNATURE rather than by this
        // test: the reason is a required field wherever a suppression is reported, so a new branch cannot
        // omit one. What a test can assert is that nothing falls between the two.
        val raw       = transcript(8)
        val delivered = Compaction.State().withExpansion(ExpansionRecord(regionOf(1), 0, 5, Evidence.Identifier, Delivered.Verbatim))
        LLM.run(cfg) {
            val (_, _, dem, v) = demoteAll(raw, Level.Pointer)
            factsFor(raw, v, delivered).map { (ctx, facts, infos) =>
                val index = armed.buildExpansionIndex(ctx, facts, dem, 0)
                val found = armed.detect("about widget_1 and widget_2", index, raw, delivered, infos)
                val matchedRegions =
                    Set("widget_1", "widget_2").flatMap(t => index.byTerm.get(t).getOrElse(Chunk.empty).toList)
                assert(matchedRegions.nonEmpty, "the fixture must match something")
                assert(
                    (found.admitted.map(_.entry.region).toSet ++ found.suppressed.map(_._1).toSet) == matchedRegions,
                    s"every match is accounted for: admitted ${found.admitted.map(_.entry.region)} plus " +
                        s"suppressed ${found.suppressed} must cover $matchedRegions"
                )
                assert(
                    found.suppressed.exists((r, s) => r == regionOf(1) && s == Suppression.SameLevelDedup),
                    s"and the region already delivered verbatim this interval names WHY, got ${found.suppressed}"
                )
            }
        }
    }

    "the reasons that look alike are told apart, because their fixes are opposite" in {
        val raw = Chunk.from((0 until 8).flatMap { i =>
            val body = if i == 1 || i == 2 then "the gizmokey warmup path" else s"unrelated bookkeeping step $i"
            List[Message](tok(am(body, call(s"c$i", "lookup", "{}")), 200), tok(tm(s"c$i", s"step $i finished"), 200))
        })
        LLM.run(cfg) {
            val (_, _, summaryDem, summaryV) = demoteAll(raw, Level.Summary)
            factsFor(raw, summaryV, Compaction.State()).map { (ctx, facts, infos) =>
                val index = armed.buildExpansionIndex(ctx, facts, summaryDem, 0)
                val found = armed.detect("gizmokey warmup", index, raw, Compaction.State(), infos)
                assert(
                    found.suppressed.exists((_, s) => s == Suppression.AlreadyAtLevel),
                    s"a content match on summary-level content says the BOUNDARY never demoted it far enough, which is a " +
                        s"different signal with a different fix from this interval having already fired. Got ${found.suppressed}"
                )
            }
        }
    }

    "a silent turn still records its top rejected candidate, so under-firing is visible" in {
        // Without this a detector that never fires is indistinguishable from a session that never needed
        // one, and under-asking is the failure the mechanism was built for.
        val raw = Chunk.from((0 until 8).flatMap { i =>
            val body = if i == 1 || i == 2 then "the quorum decision" else s"unrelated bookkeeping step $i"
            List[Message](tok(am(body, call(s"c$i", "lookup", "{}")), 200), tok(tm(s"c$i", s"step $i finished"), 200))
        })
        LLM.run(cfg) {
            val (_, _, dem, v) = demoteAll(raw, Level.Pointer)
            factsFor(raw, v, Compaction.State()).map { (ctx, facts, infos) =>
                val found = armed.detect("quorum", armed.buildExpansionIndex(ctx, facts, dem, 0), raw, Compaction.State(), infos)
                assert(found.admitted.isEmpty, "one middling content term is below the ordinary bar, so this turn is silent")
                assert(
                    found.topRejected.isDefined,
                    s"but the near-miss is recorded, which is the direct under-fire counter. Got ${found.topRejected}"
                )
                val (region, score) = found.topRejected.get
                assert(score > 0.0 && score < expansionThreshold, s"and it carries the score that missed, got $score for region $region")
            }
        }
    }

    "the usage predicate excludes the terms the triggering turn itself supplied" in {
        // A model echoes the user's own words whether or not it used what arrived, so without the
        // exclusion the observable would report near-total usage and mean nothing.
        val raw = transcript(6)
        LLM.run(cfg) {
            val (_, _, dem, v) = demoteAll(raw, Level.Pointer)
            factsFor(raw, v, Compaction.State()).map { (ctx, facts, infos) =>
                val index = armed.buildExpansionIndex(ctx, facts, dem, 0)
                // The turn names widget_1; the reply echoes only that, and nothing the delivery carried.
                val echoOnly      = raw.append(tok(um("tell me about widget_1"), 20)).append(tok(am("widget_1, right"), 20))
                val rec           = ExpansionRecord(regionOf(1), 0, raw.size, Evidence.Identifier, Delivered.Verbatim)
                val (usedEcho, _) = armed.expansionUsed(rec, index, echoOnly)
                assert(
                    !usedEcho,
                    "echoing the user's own term is not evidence the delivery was used, and counting it would make the observable vacuous"
                )
                // A reply carrying a term the delivery supplied and the turn did not IS evidence.
                val carried           = raw.append(tok(um("tell me about that"), 20)).append(tok(am("per widget_1 the answer is yes"), 20))
                val rec2              = ExpansionRecord(regionOf(1), 0, raw.size, Evidence.Identifier, Delivered.Verbatim)
                val (usedReal, terms) = armed.expansionUsed(rec2, index, carried)
                assert(usedReal && terms > 0, s"a term the delivery supplied and the turn did not IS the observable, got $terms")
            }
        }
    }

    "the reply window stops at the next user TURN, and a trailing user run is one turn" in {
        val raw = transcript(6)
        LLM.run(cfg) {
            val (_, _, dem, v) = demoteAll(raw, Level.Pointer)
            factsFor(raw, v, Compaction.State()).map { (ctx, facts, infos) =>
                val index = armed.buildExpansionIndex(ctx, facts, dem, 0)
                val rec   = ExpansionRecord(regionOf(1), 0, raw.size, Evidence.Identifier, Delivered.Verbatim)
                // Two user messages with no generation between them are ONE turn by construction, which is
                // how detection reads them, so the reply that follows the RUN is this fire's reply.
                val oneRun = raw.append(tok(um("tell me about that"), 20))
                    .append(tok(um("and be brief"), 20))
                    .append(tok(am("per widget_1 the answer is yes"), 20))
                val (usedRun, termsRun) = armed.expansionUsed(rec, index, oneRun)
                assert(usedRun && termsRun > 0, s"the reply after the whole run belongs to this fire, got $termsRun")

                // A mention after a genuinely LATER turn does not: the window closes at the next user
                // message that follows a reply.
                val laterTurn = raw.append(tok(um("tell me about that"), 20))
                    .append(tok(am("nothing to add"), 20))
                    .append(tok(um("second turn"), 20))
                    .append(tok(am("per widget_1 the answer is yes"), 20))
                val (usedLater, termsLater) = armed.expansionUsed(rec, index, laterTurn)
                assert(
                    !usedLater && termsLater == 0,
                    s"a mention after the NEXT turn belongs to that turn's reply, not this fire's, got $termsLater"
                )
            }
        }
    }

    // ==== the seam: compact's own boundary arm, end to end ====

    "a fired boundary retains an index, and the same turn can still afford a delivery through it" in {
        // Every other delivery test calls expandForTurn directly, one call away from the seam. That leaves
        // the boundary arm untested: a compact that built the index BEFORE evict, or never retained one,
        // or starved the fire on a stale usage anchor, passes all of them. The last of those was a real
        // defect, and this is the test that catches it.
        val steps = 30
        // The turn names SEVERAL regions spread across the transcript, deliberately. Which spans the cut
        // demotes depends on the turn's own terms (they seed the graph), so a turn naming one region is a
        // fixture that can silently stop testing anything when the cut's verdict shifts. Naming a spread
        // makes at least one demoted region a property of the fixture rather than a coincidence, and the
        // guard below asserts it rather than assuming it.
        val named = List(4, 10, 16, 22, 28)
        val turn  = "remind me what " + named.map(i => s"widget_$i").mkString(" and ") + " returned"
        val raw   = transcript(steps).append(tok(um(turn), 20))
        // Occupancy pinned at the trigger, which is what makes this a BOUNDARY turn, and which is exactly
        // the reading a post-boundary fire must not reuse.
        val axis = armed.ax(cfg)
        val ctx = Context(raw, raw).withCompaction(
            Compaction.State(lastUsage = Present(axis.high + 10), lastUsageRawSize = raw.size)
        )
        LLM.run(cfg) {
            Preparation.init.map { prep =>
                armed.compact(ctx, prep).map {
                    case Compactor.Decision.Unchanged => fail("occupancy is above the trigger, so this turn must take the boundary path")
                    case Compactor.Decision.Compacted(out) =>
                        assert(out.compacted.exists(_.origin.isDefined), "the boundary demoted something, or there is nothing to expand")
                        prep.expansion.get.map {
                            case Absent => fail("a fired boundary must leave an index behind for the interval that follows")
                            case Present(index) =>
                                assert(index.entries.toMap.nonEmpty, "and it must describe the regions the render just demoted")
                                assert(
                                    named.exists(i => index.byTerm.get(s"widget_$i").isDefined),
                                    s"at least one named region must be demoted, or the fire has nothing to find: " +
                                        s"${index.entries.toMap.keys.toList.sorted}"
                                )
                                assert(
                                    index.entries.toMap.values.forall(e => e.indices.headOption.forall(i => out.raw(i).origin.isEmpty)),
                                    "none of which may be content eviction forgot at this same boundary"
                                )
                                assert(
                                    out.compacted.exists(_.content.startsWith(armed.expansionEnvelope)),
                                    "and the turn's own fire must be able to AFFORD a delivery: the render just freed the headroom, " +
                                        "so reading occupancy through the pre-boundary usage anchor would starve it to nothing"
                                )
                                assert(out.raw eq ctx.raw, "raw is still handed back by reference")
                        }
                }
            }
        }
    }

    "a span-mate's summary never records over a region that went out verbatim in the same fire" in {
        // The de-escalation defect: a summary delivery covers every matched member of its span, and a
        // member that just went out VERBATIM in the same carrier is one of them. Recording it as Summary
        // makes the next turn read it as still expandable to verbatim and re-present the identical bytes,
        // which is the duplicate the admissibility guard exists to close, and makes `recall` miss the
        // resident copy.
        val raw   = transcript(8)
        val state = Compaction.State().withSummary(0, 8, "one summary standing for the whole span")
        val ident = candidateOf(0, Level.Pointer, true, 9.0)
        val topic = candidateOf(2, Level.Pointer, false, 4.0)
        LLM.run(cfg) {
            val allocated = armed.allocate(Chunk(ident, topic), 100000, state, raw)
            assert(
                allocated.map(_.delivered).toSet == Set(Delivered.Verbatim, Delivered.Summary),
                s"the fixture must deliver BOTH grains in one fire, got ${allocated.map(_.delivered)}"
            )
            val recorded = armed.recordFire(state, Chunk(ident, topic), allocated, 3, 40)
            assert(
                recorded.expansions.filter(_.region == 0).head.delivered == Delivered.Verbatim,
                s"the verbatim region keeps its verbatim record, got ${recorded.expansions}"
            )
            assert(
                recorded.expansions.filter(_.region == 0).head.evidence == Evidence.Identifier,
                "and its evidence class, which is what the damper is keyed on"
            )
            Kyo.unit
        }
    }

    "a record is monotone within an interval: an escalation updates it, a de-escalation cannot" in {
        val s0 = Compaction.State(boundaryCounter = 2)
        val up = s0.withExpansion(ExpansionRecord(5, 2, 10, Evidence.Content, Delivered.Summary))
            .withExpansion(ExpansionRecord(5, 2, 20, Evidence.Identifier, Delivered.Verbatim))
        assert(
            up.expansions.size == 1 && up.expansions.head.delivered == Delivered.Verbatim,
            s"an escalation updates, got ${up.expansions}"
        )
        val down = up.withExpansion(ExpansionRecord(5, 2, 30, Evidence.Content, Delivered.Summary))
        assert(
            down.expansions.head.delivered == Delivered.Verbatim,
            s"but a de-escalation is refused: a lower record can only ever be a bookkeeping artifact, since admissibility " +
                s"already refuses a delivery at or below what the model can read. Got ${down.expansions}"
        )
        val seedOnly = up.withExpansion(ExpansionRecord(5, 2, 40, Evidence.Content, Delivered.SeedOnly))
        assert(seedOnly.expansions.head.delivered == Delivered.Verbatim, "and a seed-only record never erases a delivery")
    }

    "escalating cannot buy extra fires: the interval cap counts turns, not surviving records" in {
        // Records are keyed by (region, interval) and replaced by an escalation, so the turn stamps
        // surviving ON RECORDS undercount fires. Counting those let a session escalate its way past the
        // cap, which is the bound the design leans on for the adversarial case.
        val fired = (1 to 4).foldLeft(Compaction.State(boundaryCounter = 1)) { (st, t) =>
            st.withExpansion(ExpansionRecord(7, 1, t * 10, Evidence.Identifier, Delivered.Verbatim)).withExpansionTurn(t * 10)
        }
        assert(fired.expansions.size == 1, "one region, one interval, one record: the escalations collapsed")
        assert(
            fired.expansionFireCount == 4,
            s"but four turns fired, and that is what the cap must see. Got ${fired.expansionFireCount}"
        )
        assert(
            fired.firedThisInterval(10) && fired.firedThisInterval(40),
            "and every fired turn stays known, so a retry of any of them is idempotent"
        )
        assert(fired.tickBoundary.expansionFireCount == 0, "the count is interval-scoped and clears at the boundary")
    }

    "the two constructions agree in every configuration where the span-range rule actually does work" in {
        // The pointer-with-no-slot fixture above cannot catch span-range drift: at Pointer with no slots
        // BOTH paths take a region-range fallback that is the same value by construction, so the whole-entry
        // comparison is vacuous for that one field. These are the three configurations where the rule has
        // a decision to make, and the previous drift lived in the first of them.
        val raw   = transcript(8)
        val spans = spansOf(raw)
        val first = spans.head
        val bytes = "summary bytes long enough that a terse prefix is a proper prefix of them, " * 6
        LLM.run(cfg) {
            def agree(level: Level, state: Compaction.State, why: String) =
                val units     = armed.group(raw)
                val demotions = spans.foldLeft(Dict.empty[Int, Level])((d, sp) => d.update(sp.start, level))
                val view      = armed.project(raw, units, spans, demotions, raw.size, Dict.empty, state)
                factsFor(raw, view, state).map { (ctx, facts, infos) =>
                    val a = armed.buildExpansionIndex(ctx, facts, demotions, 0).entries.toMap
                    val b = armed.rederiveExpansionIndex(ctx, cfg, infos, 0).entries.toMap
                    assert(a.nonEmpty, s"$why: the fixture must demote something")
                    assert(a.keys.toList.sorted == b.keys.toList.sorted, s"$why: same demoted set")
                    assert(a.keys.forall(k => a(k) == b(k)), s"$why: ${a.keys.filter(k => a(k) != b(k)).map(k => (k, a(k), b(k)))}")
                }
            end agree

            // (1) A SUMMARY span with no slot. The boundary knows the span; the restore path reads the
            // marker's origin, which for a summary-tier marker IS the span. Falling back to the region's
            // range on the boundary side, as an earlier form did, made exactly this case disagree.
            agree(Level.Summary, Compaction.State(), "slot-less summary span").andThen {
                // (2) A slot whose range does NOT match the current span shape, which is what makes this
                // arm able to fail: with a slot equal to the span, skipping the lookup entirely still
                // yields the right answer, so the arm would pin nothing. Here only the lookup finds it.
                val historic = Compaction.State().withSummary(first.start, first.end + 2, bytes)
                assert(
                    (first.start, first.end) != (first.start, first.end + 2),
                    "the historic slot must differ from the current span, or this arm is vacuous"
                )
                agree(Level.Summary, historic, "slot from an earlier span shape").andThen {
                    // (3) TWO slots covering one region, which re-formed spans across boundaries produce.
                    // Equivalence alone cannot pin WHICH is chosen, since both paths share one
                    // `slotRangeFor` and would agree on any rule; the wide slot is written FIRST so
                    // first-written-wins and narrowest-wins are distinguishable, and the rule is asserted
                    // on the function directly.
                    val twoSlots = Compaction.State()
                        .withSummary(first.start, first.end + 4, "a wider slot written by an earlier span shape")
                        .withSummary(first.start, first.end, bytes)
                    assert(
                        armed.slotRangeFor(first.start, (0, 0), twoSlots) == (first.start, first.end),
                        s"the narrowest covering slot wins, not the first written: " +
                            s"${armed.slotRangeFor(first.start, (0, 0), twoSlots)}"
                    )
                    agree(Level.Terse, twoSlots, "two covering slots")
                }
            }
        }
    }

    "a region covered by a span delivery is never logged as a skip" in {
        // The coverage rule has two consumers, the records and the diagnostic log, and computed separately
        // they drifted: the log spent a release reporting regions whose bytes DID go out as cap
        // starvations, on the very line the calibration replay reads. One shared function now answers both,
        // and this pins that they agree.
        val raw   = transcript(8)
        val state = Compaction.State().withSummary(0, 8, "one summary standing for the whole span")
        val a     = candidateOf(0, Level.Pointer, false, 4.0)
        val b     = candidateOf(2, Level.Pointer, false, 3.5)
        LLM.run(cfg) {
            val allocated = armed.allocate(Chunk(a, b), 100000, state, raw)
            assert(allocated.size == 1, "one span, one carrier")
            val covered  = armed.coverage(Chunk(a, b), allocated).map((o, _) => o.entry.region).toSet
            val recorded = armed.recordFire(state, Chunk(a, b), allocated, 3, 40).expansions.map(_.region).toSet
            assert(
                covered == Set(0, 2),
                s"the coalesced mate's bytes went out inside the span delivery, so it is COVERED, got $covered"
            )
            assert(
                covered == recorded,
                s"and the records describe exactly what the coverage rule says went out: $covered against $recorded"
            )
            Kyo.unit
        }
    }

    "a fork's fresh record survives a merge against an older record for the same region" in {
        // Deduping by region alone aliases across intervals: the parent's stale evidence would swallow the
        // fork's current-interval delivery, so this interval's suppression would lose it and the same bytes
        // would be deliverable again next turn.
        val parent = Compaction.State(boundaryCounter = 5).withExpansion(ExpansionRecord(9, 1, 10, Evidence.Content, Delivered.Summary))
        val forked = Compaction.State(boundaryCounter = 5).withExpansion(ExpansionRecord(9, 5, 80, Evidence.Identifier, Delivered.Verbatim))
        val merged = Compaction.merge(Present(parent), Present(forked)).get
        assert(merged.expansions.size == 2, s"both intervals' records survive, got ${merged.expansions}")
        assert(
            merged.expansionsIn(5).map(_.delivered) == Chunk(Delivered.Verbatim),
            s"and the CURRENT interval still knows what went out, got ${merged.expansionsIn(5)}"
        )
        // Within one interval the parent still wins, which is the rule that was right all along.
        val sameInterval = Compaction.merge(
            Present(Compaction.State(boundaryCounter =
                5
            ).withExpansion(ExpansionRecord(9, 5, 10, Evidence.Identifier, Delivered.Verbatim))),
            Present(Compaction.State(boundaryCounter = 5).withExpansion(ExpansionRecord(9, 5, 80, Evidence.Content, Delivered.Summary)))
        ).get
        assert(
            sameInterval.expansionsIn(5).map(_.turnStamp) == Chunk(10),
            s"a fork's speculative fire cannot override established evidence about the same interval, got ${sameInterval.expansionsIn(5)}"
        )
    }

    // Captures the debug lines a block emits, so a diagnostic can be asserted as the artifact it is
    // rather than through the values feeding it. Two of this mechanism's defects lived in the log alone.
    private def captureDebug[A, S](f: => A < S)(using Frame): (A, List[String]) < (S & Async) =
        import AllowUnsafe.embrace.danger
        val lines = AtomicRef.Unsafe.init(List.empty[String])
        val sink = new Log.Unsafe:
            val level: Log.Level                                                       = Log.Level.debug
            val name: String                                                           = "expansion.capture"
            def withName(n: String): Log.Unsafe                                        = this
            private def rec(msg: => String)(using AllowUnsafe): Unit                   = discard(lines.getAndUpdate(msg :: _))
            def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
            def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = rec(msg)
            def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = rec(msg)
            def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
            def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = ()
            def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = ()
            def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = ()
            def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = ()
        end sink
        Log.let(Log(sink))(f).map(a => Log.flush.andThen(Sync.defer((a, lines.get()))))
    end captureDebug

    "the LOG never calls a covered region a starvation, asserted on the emitted line" in {
        // The defect this reproduces lived in `logDetection` alone: a region whose bytes went out inside a
        // span-grain summary was reported as a turn-cap skip. Asserting the coverage VALUES agree is not
        // enough, and the previous attempt made exactly that mistake: reverting the one line in
        // `logDetection` left every test green. This drives the emitter and reads the bytes it produced.
        val raw   = transcript(8)
        val state = Compaction.State().withSummary(0, 8, "one summary standing for the whole span")
        val a     = candidateOf(0, Level.Pointer, false, 4.0)
        val b     = candidateOf(2, Level.Pointer, false, 3.5)
        LLM.run(cfg) {
            Tool.internal.infos.map { infos =>
                val allocated = armed.allocate(Chunk(a, b), 100000, state, raw)
                val found     = Model.Detection(Chunk(a, b), Chunk.empty, Absent)
                val index     = Model.ExpansionIndex(0, raw.size, Dict.empty, Dict.empty, Model.ExpansionIndex.Source.Boundary)
                captureDebug(armed.logDetection(found, allocated, index, state, raw, 40, 5000)).map { (_, lines) =>
                    assert(allocated.size == 1, "one span, one carrier, and its mate coalesced away")
                    assert(
                        lines.exists(l => l.contains("expansion fire") && l.contains("region=0")),
                        s"the fixture must actually emit a fire, got: $lines"
                    )
                    assert(
                        !lines.exists(l => l.contains("region=2") && l.contains("skip")),
                        s"region 2's bytes went out inside the span delivery, so no skip line may name it. Got: $lines"
                    )
                }
            }
        }
    }

end CompactorExpansionTest
