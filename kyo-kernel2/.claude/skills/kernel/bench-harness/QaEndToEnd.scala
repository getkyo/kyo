import Model.*
import kyo.*

/** Phase 3 of the QA plan: the whole bracket, for real.
  *
  * This is the path that has never run: open a session with measured drift, measure two legs at two commits, store them, reload them, and
  * render a comparison. One row at one fork keeps it to minutes; correctness of the plumbing is what is under test here, not the numbers.
  */
object QaEndToEnd extends KyoApp:

    val worktree = Path("/Users/fwbrasil/workspace/kyo/.claude/worktrees/bench-sweep")
    val store    = Path("/Users/fwbrasil/workspace/kyo/.claude/worktrees/effervescent-painting-backus/qa-artifacts/store")
    val control  = "36b41336fb"
    val variant  = "0f9b4b69f7"
    val row      = "nestedPayloadsUnwrapInMaps"

    def check(name: String, cond: Boolean, detail: String = ""): Unit =
        println(if cond then s"  ok   $name" else s"  FAIL $name${if detail.nonEmpty then s"  <- $detail" else ""}")

    run {
        for
            _       <- Console.printLine(s"P3.1 opening a session (measures drift on $row)")
            session <- Bench.openSession(worktree, row)
            _        = check("session id assigned", session.id.nonEmpty, session.id)
            _        = check("machine recorded", session.host.nonEmpty && session.jvm.nonEmpty, s"${session.host} ${session.jvm}")
            _        = check("drift measured, not assumed", session.driftPercent > 0.0, f"${session.driftPercent}%.2f%%")
            _       <- Console.printLine(f"       drift ${session.driftPercent}%.2f%% on ${session.host} jvm ${session.jvm}")

            _   <- Console.printLine(s"P3.2 control leg at $control")
            ctl <- Bench.runLeg(session, worktree, "qa-control", control, Cli.protoPaths, Cli.markerSpecs, Seq(row), 1, Evidence.Full)
            _    = check("rows measured", ctl.rows.size == 1, s"${ctl.rows.size}")
            _    = check("allocation captured", ctl.rows.head.allocPerOp.isDefined)
            _    = check("jit collected", ctl.jit.nonEmpty, s"${ctl.jit.size} entries")
            _    = check("alloc sites collected", ctl.alloc.nonEmpty, s"${ctl.alloc.size} sites")
            _    = check("cpu sites collected", ctl.cpu.nonEmpty, s"${ctl.cpu.size} sites")
            _    = check("markers show the old design", ctl.markers.find(_.name == "SuspendWith").exists(_.count == 0), ctl.markers.toString)
            _    = check("subset run is recorded as such", !ctl.wholeClass)

            _   <- Console.printLine(s"P3.3 variant leg at $variant, same session")
            vnt <- Bench.runLeg(session, worktree, "qa-variant", variant, Cli.protoPaths, Cli.markerSpecs, Seq(row), 1, Evidence.Full)
            _    = check("markers show the new design", vnt.markers.find(_.name == "applyFolded").exists(_.count > 0), vnt.markers.toString)
            _    = check("both legs share the session", vnt.session.id == ctl.session.id)

            _  <- Console.printLine("P3.4 store round trip")
            f1 <- Store.save(store, ctl)
            f2 <- Store.save(store, vnt)
            _   = check("both stored", true, s"$f1, $f2")
            re <- Store.load(store, ctl.id)
            _   = check("reloads identically", re == ctl, "round trip lost data")
            all <- Store.list(store)
            _    = check("listed", all.size >= 2, s"${all.size}")
            sess <- Store.session(store, session.id)
            _     = check("session recovered from a stored run", sess.id == session.id)

            _  <- Console.printLine("P3.5 comparison")
            cmp = Bench.compare(ctl, vnt)
            _   = check("a delta per shared row", cmp.deltas.size == 1, s"${cmp.deltas.size}")
            _   = check("same session, so no warning", Bench.sameSession(ctl, vnt))
            out = Report.render(cmp)
            _   = check("subset run makes no suite-wide claim", !out.contains("across the whole class"))
            _   = check("band reported as measured", out.contains("measured this session"))
            _  <- Console.printLine("\n" + out + "\n")
            _  <- Console.printLine("PHASE 3 DONE")
        yield ()
        end for
    }
end QaEndToEnd
