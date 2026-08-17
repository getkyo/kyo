import Model.*
import kyo.*

/** Persists runs so a comparison never re-runs a benchmark.
  *
  * Storing the whole ladder rather than a summary is what makes a later question answerable: what the JIT decided about a method, or which
  * classes an allocation profile attributed bytes to, stays available months after the run without repeating it.
  */
object Store:

    def dir(root: Path): Path = root / "runs"

    def save(root: Path, run: Run)(using Frame): Path < (Sync & Abort[FileWriteException]) =
        val file = dir(root) / s"${run.id}.json"
        file.write(Json.encode(run)).andThen(file)

    def load(root: Path, id: String)(using Frame): Run < (Sync & Abort[FileReadException | Bench.BracketFailed]) =
        val file = dir(root) / s"$id.json"
        file.read.map { raw =>
            Json.decode[Run](raw) match
                case Result.Success(r) => r
                case other             => Abort.fail(Bench.BracketFailed(s"run $id is unreadable: $other"))
        }
    end load

    def list(root: Path)(using Frame): Chunk[Run] < (Sync & Abort[FileFsException | FileReadException | Bench.BracketFailed]) =
        dir(root).exists.map {
            case false => Chunk.empty[Run]: Chunk[Run] < Any
            case true =>
                dir(root).list.map { files =>
                    Kyo.foreach(files.filter(_.name.exists(_.endsWith(".json")))) { f =>
                        f.read.map { raw =>
                            Json.decode[Run](raw) match
                                case Result.Success(r) => r
                                case other             => Abort.fail(Bench.BracketFailed(s"${f.name} is unreadable: $other"))
                        }
                    }
                }
        }

    /** Recovers a session from any run that recorded it, so later legs join the same one. */
    def session(root: Path, id: String)(using Frame): Session < (Sync & Abort[FileFsException | FileReadException | Bench.BracketFailed]) =
        list(root).map { runs =>
            runs.find(_.session.id == id) match
                case Some(r) => r.session
                case None    => Abort.fail(Bench.BracketFailed(s"no stored run belongs to session $id"))
        }

end Store

/** Renders a comparison.
  *
  * The rules the skill states are enforced here rather than remembered: a verdict about the whole class is unreachable from a subset run, a
  * movement with nothing in the evidence behind it is labelled unexplained rather than narrated, and a run that skipped the ladder cannot
  * present itself as attributed.
  */
object Report:

    def icon(v: Verdict): String =
        v match
            case Verdict.Faster          => "🟢"
            case Verdict.Flat            => "⚪"
            case Verdict.Regressed       => "🔴"
            case Verdict.BelowResolution => "🔵"

    def render(c: Comparison): String =
        val control = c.control
        val variant = c.variant
        val scope =
            if control.wholeClass && variant.wholeClass then s"all ${control.declaredRows} rows"
            else s"${c.deltas.size} selected rows of ${control.declaredRows}, so this says nothing about the rest"
        val evidence =
            if control.evidence == Evidence.Full && variant.evidence == Evidence.Full then "full ladder"
            else "timing only, so no movement here is attributed"
        val forksNote = if variant.forks < 3 then s", -f ${variant.forks} is diagnostic and not a claim" else ""
        val drift     = Bench.band(control, variant)
        val bandNote =
            if control.session.driftPercent > 0 then f"drift $drift%.1f%% measured this session"
            else f"drift $drift%.1f%% assumed, not measured"

        val header =
            s"""|Control `${control.sha.take(10)}` (${control.label}) against variant `${variant.sha.take(10)}` (${variant.label}).
                |JMH -f ${variant.forks}$forksNote, $scope, $evidence, $bandNote.
                |Markers control ${control.markers.map(m => s"${m.name}=${m.count}").mkString(" ")} | variant ${variant.markers.map(m => s"${m.name}=${m.count}").mkString(" ")}
                |
                || | row | mode | cnt | control | variant | delta | B/op delta | mechanism |
                ||---|---|---|---|---|---|---|---|---|""".stripMargin

        val resolutionNote =
            val unbounded = c.deltas.count(d => d.flatButUnbounded || d.verdict == Verdict.BelowResolution)
            val bounded   = c.deltas.flatMap(_.resolution)
            if bounded.nonEmpty then
                val worst = bounded.map(_.percent).max
                f"\nEvery flat row below is flat to within its own resolution, at worst +-${worst}%.2f%% " +
                    f"(alpha ${bounded.head.alpha}%.5f after correcting for ${c.deltas.size} rows, df ${bounded.head.df})."
            else if unbounded > 0 then
                s"\n⚠️  $unbounded flat rows carry no resolution: these legs were not replicated, so " +
                    "'flat' here means the harness cannot say how small an effect it would have missed, not that nothing changed."
            else ""

        val body = c.deltas.map { d =>
            val delta = if d.verdict == Verdict.BelowResolution then "below resolution" else f"${d.percent}%+.1f%%"
            val alloc = d.allocDelta.map(a => f"$a%+.0f").getOrElse("-")
            val mech =
                if d.mechanism.nonEmpty then d.mechanism.mkString("; ")
                else if d.unexplained then "**none found**"
                else "-"
            f"| ${icon(d.verdict)} | `${d.row}` | ${d.control.mode} | ${d.variant.count} | " +
                f"${d.control.score}%.2f ± ${d.control.error}%.2f | ${d.variant.score}%.2f ± ${d.variant.error}%.2f | $delta | $alloc | $mech |"
        }.mkString("\n")

        val jit =
            if c.jitChanges.isEmpty then ""
            else "\nInlining changed:\n" + c.jitChanges.map(s => s"  - $s").mkString("\n")

        val deoptShift =
            val cd = control.deopts.map(d => d.reason -> d.count).toMap
            val moved = variant.deopts.filter(d => Math.abs(d.count - cd.getOrElse(d.reason, 0)) > cd.getOrElse(d.reason, 0) / 4 + 5)
            if moved.isEmpty then ""
            else
                "\nDeoptimization changed, which no timing or allocation figure explains:\n" +
                    moved.map(d => s"  - ${d.reason}: ${cd.getOrElse(d.reason, 0)} -> ${d.count}").mkString("\n")

        val steadyState =
            val cs = Bench.stillCompiling(control)
            val vs = Bench.stillCompiling(variant)
            if cs.isEmpty && vs.isEmpty then ""
            else
                "\n\U0001f6d1 NOT STEADY STATE. These rows were still being compiled while they were measured, so the scores mix compiled " +
                    "and compiling code. Do not read the deltas above until this is diagnosed. Warming longer would hide it: a benchmark " +
                    "that needs unusual warmup is reporting something about the code under it.\n" +
                    (cs.map((r, p) => f"  - control $r%s spent ${p}%.1f%% of its window compiling") ++
                        vs.map((r, p) => f"  - variant $r%s spent ${p}%.1f%% of its window compiling")).mkString("\n") +
                    "\n  Start from the JIT metrics below: more tasks, more recompilation or more deopts on one leg is the cause, not noise."

        val jitTable =
            (control.jit_metrics, variant.jit_metrics) match
                case (Maybe.Present(c), Maybe.Present(v)) =>
                    def row(n: String, a: Double, b: Double, unit: String = "") =
                        val d = if a == 0.0 then "" else f" (${(b - a) / a * 100}%+.0f%%)"
                        f"| $n%-24s | $a%10.0f$unit | $b%10.0f$unit |$d |"
                    "\n\nJIT cost, which is a property of the design and not only of the run:\n" +
                        "| metric | control | variant | |\n|---|---|---|---|\n" +
                        Seq(
                            row("compiling in window", c.msInWindow, v.msInWindow, "ms"),
                            row("compiling total", c.msTotal, v.msTotal, "ms"),
                            row("compilation tasks", c.tasks.toDouble, v.tasks.toDouble),
                            row("C2 tasks", c.c2Tasks.toDouble, v.c2Tasks.toDouble),
                            row("OSR tasks", c.osrTasks.toDouble, v.osrTasks.toDouble),
                            row("methods recompiled", c.recompiled.toDouble, v.recompiled.toDouble),
                            // runtime events only. The planted-guard census used to sit in this row
                            // and swamped it: 633 guards against 6 real deoptimizations.
                            row("runtime deopts", c.runtimeDeopts.toDouble, v.runtimeDeopts.toDouble),
                            row("made not entrant", c.madeNotEntrant.toDouble, v.madeNotEntrant.toDouble),
                            row("last compile at", c.lastCompileAt, v.lastCompileAt, "s")
                        ).mkString("\n")
                case _ => ""

        val polymorphic =
            // only sites the JIT actually profiled a receiver for. The previous version filtered on
            // `!monomorphic`, which included every unprofiled site, and printed 46 of 47 of them
            // under a heading promising measured receiver counts while each had a receiver count of
            // zero. Absence of a profile is not evidence of polymorphism.
            val poly = variant.morphism.filter(_.monomorphic.contains(false)).take(3)
            if poly.isEmpty then ""
            else
                "\nPolymorphic call sites, among the few the JIT profiled a receiver for:\n" +
                    poly.map(m => s"  - ${m.callee} ${m.count} calls, ${m.receiverCount} to the top receiver").mkString("\n")

        val reds        = c.deltas.filter(_.verdict == Verdict.Regressed)
        val wins        = c.deltas.filter(_.verdict == Verdict.Faster)
        val unexplained = c.deltas.filter(_.unexplained)

        val sessionWarning =
            if Bench.sameSession(control, variant) then ""
            else
                // comparing across sessions is what made a parity row look like a regression
                "\u274c These runs are from different sessions, so the deltas below are not comparable. " +
                    "Re-measure the control beside the variant.\n\n"

        val noiseNote =
            val n = Bench.noiseShare(variant)
            if n < 25.0 then ""
            else f"\n\u2139\ufe0f  $n%.0f%% of sampled time is in classes no kernel change can move, so kernel-attributable movement is a fraction of each delta above."

        val bothWays =
            if wins.isEmpty || reds.isEmpty then ""
            else
                "\n\u26a0\ufe0f  This change both wins and loses. Those are two diagnoses, not one tradeoff: the loss usually turns out " +
                    "removable, and accepting it early ships a defect the same afternoon's work would have deleted."

        val resolved = c.deltas.count(d => d.verdict != Verdict.BelowResolution)

        val verdictLine =
            // every row unresolvable is not a clean result. The branch order previously fell through
            // to the green all-clear whenever no row regressed, including when no row could be read
            // at all, and the resolution note stayed silent because it counted only Flat rows.
            if resolved == 0 && c.deltas.nonEmpty then
                s"\n\u26a0\ufe0f  Nothing was resolvable: all ${c.deltas.size} rows fell below the measurement's own error. " +
                    "This is not a clean run, it is an unreadable one; re-measure with more forks before drawing any conclusion."
            else if reds.nonEmpty then
                "\n🔴 Regressed, so the work is unfinished until each is diagnosed or ruled on:\n" +
                    reds.map(d => f"  - ${d.row} ${d.percent}%+.1f%%").mkString("\n")
            else if !(control.wholeClass && variant.wholeClass) then
                // the claim this refuses to make is the exact false one a subset run invited before
                "\n⚪ No selected row regressed. This was a subset run, so it is not a statement about the suite."
            else "\n🟢 No row regressed beyond the drift band, across the whole class."

        val ladder =
            if unexplained.isEmpty then ""
            else
                "\n⚠️  Moved with nothing in the evidence behind it, so the cause is not known yet:\n" +
                    unexplained.map(d => s"  - ${d.row}: check allocation sites and the inlining log before proposing a mechanism").mkString("\n")

        s"$sessionWarning$header\n$body$resolutionNote$jit$deoptShift$polymorphic$verdictLine$ladder$steadyState$jitTable$bothWays$noiseNote"
    end render

end Report
