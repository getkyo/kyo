import Model.*
import kyo.*

/** Phase 1.5: the compilation-log parser against a real 3.2MB capture. */
object QaLogc:
    def check(n: String, c: Boolean, d: String = ""): Boolean =
        println(if c then s"  ok   $n" else s"  FAIL $n${if d.nonEmpty then s"  <- $d" else ""}")
        c

    def main(args: Array[String]): Unit =
        val raw   = scala.io.Source.fromFile(args.head).mkString
        val tasks = LogCompilation.parse(raw)
        var ok    = true
        println("P1.5 LogCompilation xml")
        ok &= check("tasks parsed", tasks.nonEmpty, s"${tasks.size}")
        ok &= check("task methods named", tasks.exists(_.method.contains("kyo")), tasks.take(2).map(_.method).mkString(","))

        val m = LogCompilation.metrics(tasks, 0.0, 0.0)
        ok &= check("C2 compilations counted", m.c2Tasks > 0, s"${m.c2Tasks} of ${m.tasks}")
        ok &= check("C1 tiers counted too", m.tasks - m.c2Tasks > 0, s"${m.tasks - m.c2Tasks}")
        println(f"       ${m.tasks} tasks, ${m.c2Tasks} C2, ${m.recompiled} recompiled, last at ${m.lastCompileAt}%.2fs")

        val inl = LogCompilation.inlining(tasks)
        ok &= check("inlining derived from the compilation log", inl.nonEmpty, s"${inl.size}")
        ok &= check("byte sizes attached", inl.exists(_.bytes > 0))
        ok &= check("both verdicts present", inl.exists(_.inlined) && inl.exists(!_.inlined))
        ok &= check("more complete than PrintInlining gave", inl.size > 30, s"${inl.size}")
        inl.filter(e => e.method.contains("Suspend") || e.method.contains("Eval")).take(4).foreach(e =>
            println(s"       ${e.method} ${e.bytes}B ${if e.inlined then "inlined" else e.reason}"))

        val deopts = LogCompilation.deoptSummary(tasks)
        ok &= check("deoptimizations found", deopts.nonEmpty, s"${deopts.size} kinds")
        deopts.take(4).foreach(d => println(s"       ${d.reason} x${d.count}"))

        val morph = LogCompilation.morphism(tasks)
        ok &= check("kernel call sites resolved to names", morph.nonEmpty, s"${morph.size}")
        ok &= check("names are real classes, not raw ids", morph.forall(!_.callee.startsWith("method#")), morph.take(2).map(_.callee).mkString(","))
        ok &= check("both monomorphic and polymorphic observed", morph.exists(_.monomorphic) && morph.exists(!_.monomorphic))
        println("       most-called kernel sites:")
        morph.sortBy(-_.count).take(5).foreach(m =>
            println(f"       ${if m.monomorphic then "mono " else "POLY "} ${m.callee}%-58s ${m.count}%9d calls")
        )
        println(if ok then "\nPHASE 1.5 PASS" else "\nPHASE 1.5 FAIL")
        if !ok then sys.exit(1)
end QaLogc
