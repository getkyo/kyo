package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Context.*

/** The validity gate and adoption: the pinning-partition agreement test (both invalidation directions
  * render fresh at zero new model calls), the adoption splice shape, write-once first-writer-wins across a
  * fiber/boundary race (and the absence case), and the terse-REAL render of a landed summary blob. The gate
  * and the write-once slot are pure structural data, so these are deterministic in-memory assertions; the
  * zero-new-calls property is witnessed through TestCompletionServer's capture log.
  */
class CompactorGateTest extends kyo.test.Test[Any]:

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))

    def cfg(window: Int = 16384): Config =
        Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    def pinnedCfg(server: TestCompletionServer): Config =
        cfg().compaction(_.summarizer(cfg().apiUrl(server.baseUrl)))

    // ==== the validity gate (pinning partition) ====

    "adopt on pinning-partition agreement (depth may differ, the partition is equal)" in {
        val spans = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)), Span(2, 3, Chunk(2)))
        // Both assign the SAME pinned set {0} and the SAME demoted set {1,2}; only the pass-2 depth differs.
        val aPrep  = Dict[Int, Level]((1, Level.Summary), (2, Level.Summary))
        val aFresh = Dict[Int, Level]((1, Level.Summary), (2, Level.Pointer))
        assert(
            Default.validityGate(aPrep, aFresh, spans),
            "agreement holds on the pinned-vs-demoted partition (span 0 pinned in both; 1,2 demoted in both); a summary-vs-pointer depth difference never gates"
        )
        // A depth-only difference across every demoted span still agrees.
        val allTerse = Dict[Int, Level]((1, Level.Terse), (2, Level.Terse))
        assert(Default.validityGate(aPrep, allTerse, spans), "the gate compares definedness (pinned vs demoted), not the Level")
    }

    "both directions render fresh, and a false invalidation makes ZERO new model calls" in {
        val spans = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)))
        // (soundness) A_prep demotes span (1,2) but A_fresh PINS it (absent from the assignment).
        val aPrepSound  = Dict[Int, Level]((1, Level.Summary))
        val aFreshSound = Dict.empty[Int, Level]
        assert(
            !Default.validityGate(aPrepSound, aFreshSound, spans),
            "soundness: prepared demotes but fresh pins -> invalidate (adopting would demote live content)"
        )
        // (completeness) A_prep pins span (1,2) but A_fresh DEMOTES it.
        val aPrepComplete  = Dict.empty[Int, Level]
        val aFreshComplete = Dict[Int, Level]((1, Level.Summary))
        assert(
            !Default.validityGate(aPrepComplete, aFreshComplete, spans),
            "completeness: prepared pins but fresh demotes -> invalidate (adopting would strand below-keep mass)"
        )
        // ZERO new model calls: the prepared summary already sits write-once in staging, so the boundary
        // reuses it and issues no completion even though the assignment it derived is discarded.
        val raw = Chunk[Message](am("r0"), am("r1"))
        TestCompletionServer.run { server =>
            Preparation.init.map { prep =>
                prep.staged.set(Staged().withSummary(SpanKey(1, 2), "prepared")).andThen {
                    Default.joinPreparation(Context(raw), pinnedCfg(server), prep, Chunk(Span(1, 2, Chunk(1))), Chunk.empty).map { staged =>
                        server.captured.map { cap =>
                            assert(cap.isEmpty, "the write-once staged bytes are reused; a false invalidation issues zero completions")
                            assert(staged.summaryOf(SpanKey(1, 2)) == Present("prepared"), "the reused summary is the already-staged blob")
                        }
                    }
                }
            }
        }
    }

    "the adoption splice is prepared-prefix ++ fresh-remainder ++ verbatim-tail" in {
        val raw   = Chunk.from((0 until 6).map(i => am(s"region $i CONTENT")))
        val units = Default.group(raw)
        // spans 0..3 are the closed prefix (demoted); regions 4,5 are the verbatim tail (no span demotes them).
        val spans     = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)), Span(2, 3, Chunk(2)), Span(3, 4, Chunk(3)))
        val demotions = Dict[Int, Level]((0, Level.Summary), (1, Level.Summary), (2, Level.Summary), (3, Level.Summary))
        // the prepared prefix (0..2) carries staged summaries; the newly-closed remainder (3,4) is empty (fresh).
        val state = CompactionState().withSummary(0, 1, "S0").withSummary(1, 2, "S1").withSummary(2, 3, "S2")
        val view  = Default.project(raw, units, spans, demotions, raw.size, Dict.empty, state)
        val text  = view.map(_.content).mkString("\n")
        assert(
            text.contains("S0") && text.contains("S1") && text.contains("S2"),
            "the prepared prefix renders its write-once summary bytes"
        )
        val i0         = text.indexOf("S0")
        val i2         = text.indexOf("S2")
        val iRemainder = text.indexOf("summary unavailable") // the fresh, unfilled remainder span (3,4)
        val iTail      = text.indexOf("region 4 CONTENT")    // the verbatim tail
        assert(i0 >= 0 && i2 > i0, "the prepared-prefix summaries render in order")
        assert(iRemainder > i2, "the fresh remainder (empty slot -> substitute elision) renders after the prepared prefix")
        assert(iTail > iRemainder, "the verbatim tail renders last, after the fresh remainder")
        assert(text.contains("region 5 CONTENT"), "the verbatim tail is preserved (no message dropped across the splice)")
    }

    // ==== write-once first-writer-wins ====

    "write-once first-writer-wins across a fiber/boundary race (either order)" in {
        // background stages "bg" first, boundary "fg" second -> bg wins.
        val bgFirst = CompactionState().withSummary(3, 7, "bg").withSummary(3, 7, "fg")
        // boundary stages "fg" first, background "bg" second -> fg wins.
        val fgFirst = CompactionState().withSummary(3, 7, "fg").withSummary(3, 7, "bg")
        assert(bgFirst.summaryOf(3, 7) == Present("bg"), "whichever bytes reach the slot first are permanent (bg-first)")
        assert(fgFirst.summaryOf(3, 7) == Present("fg"), "whichever bytes reach the slot first are permanent (fg-first)")
        // the staging cell mirrors the same first-writer-wins discipline.
        val staged = Staged().withSummary(SpanKey(3, 7), "bg").withSummary(SpanKey(3, 7), "fg")
        assert(
            staged.summaryOf(SpanKey(3, 7)) == Present("bg"),
            "Staged.withSummary is first-writer-wins, so the race is idempotent (no lock needed)"
        )
    }

    "a second write / re-emitted artifact to a filled slot is discarded" in {
        val filled = CompactionState().withSummary(3, 7, "first")
        val second = filled.withSummary(3, 7, "second")
        assert(second.summaryOf(3, 7) == Present("first"), "a second write to a filled slot is discarded (state)")
        val staged = Staged().withSummary(SpanKey(3, 7), "first").withSummary(SpanKey(3, 7), "second")
        assert(staged.summaryOf(SpanKey(3, 7)) == Present("first"), "a re-emitted artifact to a filled slot is discarded (staging cell)")
        // the loser never renders.
        val raw = Chunk.from((0 until 8).map(i => am(s"r$i")))
        val marker =
            Default.summaryMarker(Span(3, 7, Chunk(3, 4, 5, 6)), raw, Default.group(raw), Level.Summary, raw.size, Dict.empty, second)
        assert(
            marker.content.contains("first") && !marker.content.contains("second"),
            "the served view carries the first bytes and never the discarded loser"
        )
    }

    // ==== terse-REAL ====

    "terse-REAL a landed summary blob renders terse as marker + code-point-safe prefix of the REAL bytes" in {
        val sp    = Span(3, 7, Chunk(3, 4, 5, 6))
        val raw   = Chunk.from((0 until 8).map(i => am(s"r$i " + ("y" * 20))))
        val units = Default.group(raw)
        val real  = "R" * (tersePrefixChars + 100)
        val state = CompactionState().withSummary(3, 7, real)
        val terse = Default.summaryMarker(sp, raw, units, Level.Terse, raw.size, Dict.empty, state)
        assert(terse.content.contains("R" * tersePrefixChars), "terse carries a real prefix of the landed fill bytes")
        assert(!terse.content.contains("R" * (tersePrefixChars + 1)), "the terse prefix truncates at tersePrefixChars")
        assert(
            !terse.content.contains("summary unavailable"),
            "with a real slot filled, terse is a real prefix, not the blob-less substitute"
        )
        assert(terse.content.contains("recall(3)"), "terse carries the same recall id as the summary render")
        // the blob-less path is unchanged: an EMPTY slot at the summary level is still the substitute elision.
        val empty = Default.summaryMarker(sp, raw, units, Level.Summary, raw.size, Dict.empty, CompactionState())
        assert(empty.content.contains("summary unavailable"), "an empty slot renders the fixed-size substitute elision (P2 unchanged)")
    }

end CompactorGateTest
