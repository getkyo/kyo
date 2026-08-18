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

    // a QA main whose checks only print cannot fail: "FAIL" scrolled past and the process exited 0,
    // which is the shape of failure the harness exists to refuse. Same contract as BenchTest.check
    def check(name: String, cond: Boolean, detail: String = ""): Unit =
        println(if cond then s"  ok   $name" else s"  FAIL $name${if detail.nonEmpty then s"  <- $detail" else ""}")
        if !cond then throw new AssertionError(name)

    run {
        for
            _       <- Console.printLine("P3.1 opening a session")
            session <- Bench.openSession(worktree)
            _        = check("session id assigned", session.id.nonEmpty, session.id)
            _        = check("machine recorded", session.host.nonEmpty && session.jvm.nonEmpty, s"${session.host} ${session.jvm}")
            // the spread comes from a bracket's own replicate legs; a session claims no drift of its
            // own, and the previous check here ("drift measured, not assumed") was false by construction
            _        = check("no session-level drift is claimed", session.driftPercent == 0.0, f"${session.driftPercent}%.2f%%")
            _       <- Console.printLine(s"       on ${session.host} jvm ${session.jvm}")

            _   <- Console.printLine(s"P3.2 control leg at $control")
            ctl <- Bench.runLeg(session, worktree, "qa-control", control, Cli.protoPaths, Cli.markerSpecs, Seq(row), 1, Evidence.Full)
            _    = check("rows measured", ctl.rows.size == 1, s"${ctl.rows.size}")
            _    = check("allocation captured", ctl.rows.head.allocPerOp.isDefined)
            _    = check("jit collected", ctl.jit.nonEmpty, s"${ctl.jit.size} entries")
            _    = check("alloc sites collected", ctl.alloc.nonEmpty, s"${ctl.alloc.size} sites")
            _    = check("cpu sites collected", ctl.cpu.nonEmpty, s"${ctl.cpu.size} sites")
            _    = check("compiler time captured by -prof comp", ctl.rows.head.compilerMsProfiled.isDefined, s"${ctl.rows.head.compilerMsProfiled}")
            _    = check("compilation log collected", ctl.jit_metrics.isDefined, s"${ctl.jit_metrics}")
            _    = check("deopts collected", ctl.deopts.nonEmpty, s"${ctl.deopts.size} kinds")
            _    = check("morphism collected", ctl.morphism.nonEmpty, s"${ctl.morphism.size} sites")
            _    = check("warmup recorded", ctl.warmup == Bench.WarmupIterations, s"${ctl.warmup}")
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
            _   = check("jit cost table rendered", out.contains("JIT cost"), "no jit table")
            _   = ctl.jit_metrics.foreach(m =>
                    println(f"       control jit: ${m.msInWindow}%.0fms in window, ${m.msTotal}%.0fms total, ${m.tasks} tasks, ${m.c2Tasks} C2, ${m.recompiled} recompiled, ${m.runtimeDeopts} runtime deopts, ${m.osrTasks} OSR, last at ${m.lastCompileAt}%.2fs")
                  )
            _   = vnt.jit_metrics.foreach(m =>
                    println(f"       variant jit: ${m.msInWindow}%.0fms in window, ${m.msTotal}%.0fms total, ${m.tasks} tasks, ${m.c2Tasks} C2, ${m.recompiled} recompiled, ${m.runtimeDeopts} runtime deopts, ${m.osrTasks} OSR, last at ${m.lastCompileAt}%.2fs")
                  )
            // this was `isEmpty || out.contains(...)`, which passes whenever nothing is flagged, so
            // it asserted nothing on every run that has ever been made. The claim worth making is the
            // biconditional: the report says it exactly when the guard found it.
            _ = check(
                "the report says NOT STEADY STATE exactly when the guard found something",
                Bench.stillCompiling(ctl).nonEmpty == out.contains("NOT STEADY STATE"),
                s"guard: ${Bench.stillCompiling(ctl)}, report says it: ${out.contains("NOT STEADY STATE")}"
            )
            _  <- Console.printLine("\n" + out + "\n")
            _  <- Console.printLine("PHASE 3 DONE")
        yield ()
        end for
    }
end QaEndToEnd
