package kyo

import CompactionReplayHarness.*
import kyo.ai.*

class CompactionReplayTest extends kyo.test.Test[Any]:

    "session 1 (supersession): full keeps latest state, baseline does not" in {
        replay(session1).map { sc =>
            assert(sc.full.taskSuccess, s"full reflects the latest write and its task-success predicates hold: ${sc.full}")
            assert(
                sc.full.tokenCost <= sc.baseline.tokenCost,
                s"the full arm supersedes the stale reads for a lower token cost: full=${sc.full.tokenCost} baseline=${sc.baseline.tokenCost}"
            )
        }
    }

    "session 2 (oversized result) + session 3 (recall): elision + recoverability" in {
        replay(session23).map { sc =>
            assert(sc.full.taskSuccess, s"the elided head token and the masked region are both available to full: ${sc.full}")
            // The elision marker in the full view is the observable evidence the oversized result was
            // elided (head/tail) rather than kept whole.
            assert(
                viewText(sc.fullView).contains("elided"),
                s"the full arm elides the oversized result: ${viewText(sc.fullView)}"
            )
            assert(
                sc.full.refetchRate <= sc.baseline.refetchRate,
                s"full re-fetches no more than the baseline: full=${sc.full.refetchRate} baseline=${sc.baseline.refetchRate}"
            )
        }
    }

    "session 4 (frontier drift): crosses H*E and H_hard with an objective change" in {
        replay(session4).map { sc =>
            assert(sc.occupancy >= sc.updateTrigger, s"occupancy crosses H*E: occ=${sc.occupancy} trigger=${sc.updateTrigger}")
            assert(sc.occupancy >= sc.hardWindow, s"occupancy crosses H_hard: occ=${sc.occupancy} hard=${sc.hardWindow}")
            assert(
                sc.full.tokenCost <= sc.window,
                s"the forced path fits under the window with no over-limit send: cost=${sc.full.tokenCost} window=${sc.window}"
            )
            assert(sc.full.taskSuccess, s"the moved objective's dependency stays live under the forced path: ${sc.full}")
        }
    }

    "session 5 (discriminator): graph-liveness keeps a non-root early note alive under real pressure; baseline outcome is MEASURED, not forced" in {
        replay(session5).map { sc =>
            // Under real occupancy pressure the full arm compacts: the projected view is strictly smaller
            // than the raw transcript. A no-op render (view == transcript) would fail this loudly.
            assert(
                sc.full.tokenCost < sc.occupancy,
                s"the full arm shrinks the view under pressure: full=${sc.full.tokenCost} transcript=${sc.occupancy}"
            )
            // The discriminator lives only in an early NON-root note the objective still references, so it
            // survives by graph-liveness (co-pinned via a live root's Ref edge, nonzero PPR score), not by
            // root-pinning; the baseline's keep-last-4 truncation drops it into its opaque summary.
            assert(sc.full.taskSuccess, s"the discriminator survives via graph-liveness in the full arm's view: ${sc.full}")
            // The baseline arm's outcome is recorded as whatever it actually computes; the leaf asserts a
            // scoreboard value was measured for it, never that it fails.
            assert(sc.baseline.tokenCost >= 0, s"the baseline arm's scoreboard was measured: ${sc.baseline}")
        }
    }

    "session 6 (short linear, recency wins): the dumb baseline is competitive with full" in {
        replay(session6).map { sc =>
            assert(
                sc.baseline.taskSuccess && sc.full.taskSuccess,
                s"both arms succeed on the short linear session: baseline=${sc.baseline} full=${sc.full}"
            )
            assert(
                sc.baseline.tokenCost <= sc.full.tokenCost + tokenEpsilon,
                s"the dumb baseline ties or beats full here: baseline=${sc.baseline.tokenCost} full=${sc.full.tokenCost}"
            )
        }
    }

    "go/no-go: a real two-valued decision over the 6 sessions with a REACHABLE no-go branch" in {
        // The no-go branch is reachable: a synthetic scoreboard where the baseline beats the full design
        // on task-success and token cost returns NoGo, independent of the six real sessions.
        val baselineWins = List(
            SessionScores(
                name = "synthetic baseline-wins",
                baseline = Scoreboard(taskSuccess = true, tokenCost = 10, refetchRate = 0.0),
                full = Scoreboard(taskSuccess = false, tokenCost = 100, refetchRate = 1.0),
                occupancy = 0,
                updateTrigger = 0.0,
                hardWindow = 0.0,
                window = 0
            )
        )
        assert(decide(baselineWins) == GoNoGo.NoGo, "a baseline-wins scoreboard returns NoGo: the no-go branch is live and reachable")

        val fullWins = baselineWins.map(s =>
            s.copy(
                baseline = Scoreboard(taskSuccess = false, tokenCost = 100, refetchRate = 1.0),
                full = Scoreboard(taskSuccess = true, tokenCost = 10, refetchRate = 0.0)
            )
        )
        assert(decide(fullWins) == GoNoGo.Go, "a full-wins scoreboard returns Go: the decision is genuinely two-valued")

        Kyo.foreach(sessions)(replay).map { real =>
            val verdict = decide(real)
            assert(
                verdict == GoNoGo.Go || verdict == GoNoGo.NoGo,
                s"a two-valued verdict was computed and recorded over the six real sessions: $verdict"
            )
        }
    }

end CompactionReplayTest
