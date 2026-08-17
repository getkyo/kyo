import Model.*
import kyo.*

/** Phase 1 of the QA plan: the parsers against real tool output.
  *
  * These are the only components whose input is not under our control, so they are validated against files captured from live runs rather
  * than against synthetic text.
  */
object QaParsers:

    def check(name: String, cond: Boolean, detail: String = ""): Boolean =
        println(if cond then s"  ok   $name" else s"  FAIL $name${if detail.nonEmpty then s"  <- $detail" else ""}")
        cond

    def main(args: Array[String]): Unit =
        import kyo.Frame.internal
        val dir  = args.headOption.getOrElse("../../../../../qa-artifacts")
        def read(f: String) = scala.io.Source.fromFile(s"$dir/$f").mkString
        var ok = true

        println("P1.1 jmh json with gc profiler")
        val rows = Abort.run(Bench.parseJmh(read("qa-jmh.json"))).eval.getOrThrow
        ok &= check("rows parsed", rows.nonEmpty, s"got ${rows.size}")
        rows.headMaybe.foreach { r =>
            ok &= check("score is positive", r.score > 0, s"${r.score}")
            ok &= check("unit captured", r.unit.nonEmpty, r.unit)
            ok &= check("iteration count captured", r.count > 0, s"${r.count}")
            ok &= check("gc.alloc.rate.norm present", r.allocPerOp.isDefined, s"${r.allocPerOp}")
            println(s"       ${r.name} ${r.score} ± ${r.error} ${r.unit}, ${r.allocPerOp} B/op, n=${r.count}")
        }

        println("P1.2 PrintInlining")
        val jit = Bench.parseJit(read("qa-jit.txt"))
        ok &= check("entries parsed", jit.nonEmpty, s"got ${jit.size}")
        ok &= check("kernel methods present", jit.exists(_.method.contains("kyo.kernel.proto")), jit.take(3).map(_.method).mkString(","))
        // 0 bytes is legitimate: abstract methods report it, and those are exactly the megamorphic sites
        ok &= check("byte counts captured", jit.exists(_.bytes > 0) && jit.forall(_.bytes >= 0))
        ok &= check("warmup artifacts are not treated as mechanisms", !jit.filter(Bench.actionableJit).exists(_.reason.contains("not linked")))
        ok &= check("both verdicts observed", jit.exists(_.inlined) && jit.exists(!_.inlined))
        ok &= check("actionable filter keeps something", jit.exists(Bench.actionableJit))
        jit.filter(e => e.method.contains("Suspend") || e.method.contains("Arrow$")).take(4).foreach(e =>
            println(s"       ${e.method} ${e.bytes}B ${if e.inlined then "inlined" else e.reason}")
        )

        println("P1.3 async-profiler alloc")
        val alloc = Bench.parseAlloc(read("qa-alloc.txt"))
        ok &= check("sites parsed", alloc.nonEmpty, s"got ${alloc.size}")
        ok &= check("class names look like classes", alloc.take(5).forall(_.cls.contains(".")), alloc.take(3).map(_.cls).mkString(","))
        ok &= check("bytes positive", alloc.forall(_.bytes > 0))
        alloc.take(4).foreach(a => println(s"       ${a.cls} ${a.bytes}"))

        println("P1.4 async-profiler itimer")
        val cpu = Bench.parseCpu(read("qa-cpu.txt"))
        ok &= check("sites parsed", cpu.nonEmpty, s"got ${cpu.size}")
        ok &= check("method names captured", cpu.take(5).forall(_.method.nonEmpty))
        cpu.take(4).foreach(c => println(s"       ${c.method} ${c.nanos}"))
        println(f"       noise share would be ${Bench.noiseShare(Run("x", Session("s", "h", "j", 1.0), "l", "t", "h", 1, Evidence.Full, true, 15, Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty, cpu, "now"))}%.0f%%")

        println(if ok then "\nPHASE 1 PASS" else "\nPHASE 1 FAIL")
        if !ok then sys.exit(1)
    end main
end QaParsers
