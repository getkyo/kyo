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

        // P1.2 covered the PrintInlining parser, which is gone: the compilation log supersedes it
        // and carries a denominator per method, which PrintInlining never did. Inlining is now
        // checked against the log by LogCompilationTest, with oracles rather than shape assertions.

        println("P1.3 async-profiler alloc")
        val allocRaw = read("qa-alloc.txt")
        val alloc    = Bench.parseAlloc(allocRaw)
        // the oracle counts the table's data rows by a route the parser does not share: everything
        // between the column header and the line naming the summary file
        val tableRows =
            allocRaw.linesIterator.dropWhile(!_.contains("percent  samples")).drop(2)
                .takeWhile(!_.contains("Async profiler results")).count(_.trim.nonEmpty)
        ok &= check("sites parsed", alloc.nonEmpty, s"got ${alloc.size}")
        ok &= check(s"every table row parsed, and no line outside the table", alloc.size == tableRows, s"parsed ${alloc.size}, table has $tableRows")
        ok &= check("class names look like classes", alloc.take(5).forall(_.cls.contains(".")), alloc.take(3).map(_.cls).mkString(","))
        ok &= check("bytes positive", alloc.forall(_.bytes > 0))
        ok &= check("samples captured, the currency the collapsed view shares", alloc.forall(_.samples > 0), alloc.map(_.samples).mkString(","))
        alloc.take(4).foreach(a => println(s"       ${a.cls} ${a.bytes} bytes, ${a.samples} samples"))

        // an array type is the case the old character-class spelling could not express: it stopped
        // at the `[` and reported `java.lang.Object`, so an Object[] allocation and an Object
        // allocation became one row. On the kernel rows the Object[] is the stack.
        val arrays = Bench.parseAlloc(
            "       bytes  percent  samples  top\n  ----------  -------  -------  ---\n" +
                "  9510041893   50.01%    18139  java.lang.Object[]\n" +
                "    16777184    0.09%       32  java.lang.Object\n"
        )
        ok &= check("an array type keeps its brackets", arrays.map(_.cls) == Chunk("java.lang.Object[]", "java.lang.Object"), arrays.map(_.cls).mkString(","))

        // a multi-benchmark alloc dump repeats a class once per section; parseAlloc folds them into one
        // leg-level row in encounter order, or apportion divides one section's bytes by every section's
        // samples (defect 48)
        val multi = Bench.parseAlloc(
            "# Benchmark: A\n  1000   50.0%   100  kyo.Nested\n   200   10.0%    20  kyo.Boxed\n" +
                "# Benchmark: B\n   500   50.0%    50  kyo.Nested\n"
        )
        ok &= check("a class repeated across benchmark sections is one row", multi.count(_.cls == "kyo.Nested") == 1, multi.map(_.cls).mkString(","))
        ok &= check("with its bytes and samples summed", multi.find(_.cls == "kyo.Nested").exists(a => a.bytes == 1500L && a.samples == 150L), multi.map(a => s"${a.cls}:${a.bytes}/${a.samples}").mkString(" | "))
        ok &= check("and encounter order preserved", multi.map(_.cls) == Chunk("kyo.Nested", "kyo.Boxed"), multi.map(_.cls).mkString(","))

        println("P1.4 async-profiler itimer")
        val cpu = Bench.parseCpu(read("qa-cpu.txt"))
        ok &= check("sites parsed", cpu.nonEmpty, s"got ${cpu.size}")
        ok &= check("method names captured", cpu.take(5).forall(_.method.nonEmpty))
        cpu.take(4).foreach(c => println(s"       ${c.method} ${c.nanos}"))
        val probe = Run(
            id = "x", session = Session("s", "h", "j", 1.0), label = "l", sha = "t", treeHash = "h", forks = 1,
            evidence = Evidence.Full, wholeClass = true, declaredRows = 15, markers = Chunk.empty, warmup = 10,
            jit_metrics = Maybe.empty, rows = Chunk.empty, jit = Chunk.empty, coverage = Chunk.empty,
            alloc = Chunk.empty, allocByMethod = Chunk.empty, cpu = cpu, deopts = Chunk.empty, morphism = Chunk.empty, recordedAt = "now"
        )
        println(f"       noise share would be ${Bench.noiseShare(probe)}%.0f%%")

        println("P1.5 allocation attributed to the method that allocated it")
        // acceptance is a planted program, not conservation: `Planted.plantedAllocator` is the only
        // significant allocator in it and is named here before the parser runs. Conservation holds
        // equally for a correct attribution and for one assigning every sample to an arbitrary frame,
        // so it is a coverage guard and never the acceptance.
        val plantedFlat      = Bench.parseAlloc(read("qa-planted-flat.txt"))
        val plantedCollapsed = Bench.parseCollapsed(read("qa-planted-collapsed.txt"))
        ok &= check("collapsed lines parsed", plantedCollapsed.nonEmpty, s"got ${plantedCollapsed.size}")
        val top = plantedCollapsed.head
        ok &= check("the top allocator is the planted one", top.method == "Planted.plantedAllocator", top.show)
        ok &= check("and the class it minted is the planted one", top.cls == "byte[]", top.show)
        // the collapsed format is root-first, so an index from the front names `Planted.main` on
        // every line and attributes the whole program to its entry point
        ok &= check(
            "no entry is attributed to the root frame",
            plantedCollapsed.forall(_.method != "Planted.main"),
            plantedCollapsed.map(_.method).mkString(",")
        )
        ok &= check(
            "a class allocated from two places is split, which the flat table cannot show",
            plantedCollapsed.count(_.cls == "byte[]") == 2,
            plantedCollapsed.filter(_.cls == "byte[]").map(_.show).mkString(" | ")
        )
        val apportioned = Bench.apportion(plantedFlat, plantedCollapsed)
        ok &= check(
            "bytes are apportioned from the flat table for classes it lists",
            apportioned.find(_.method == "Planted.plantedAllocator").exists(_.bytes.exists(_ > 1e11)),
            apportioned.take(2).map(_.show).mkString(" | ")
        )
        plantedCollapsed.foreach(m => println(s"       ${m.show}"))
        // these two captures are separate recordings of the same program, which is exactly the case
        // the conservation gate must refuse: it is bounded by run-to-run variance, not by the parser
        val crossRecording = Bench.allocConservation(plantedFlat, plantedCollapsed)
        ok &= check("conservation refuses two different recordings", crossRecording.nonEmpty, crossRecording.mkString)
        val sameRecording = Bench.allocConservation(
            Chunk(AllocSite("byte[]", 163586456879L, 312719L)),
            plantedCollapsed.filter(_.cls == "byte[]")
        )
        ok &= check("and passes when the samples do come from one", sameRecording.isEmpty, sameRecording.mkString)

        println("P1.6 the same recording, dumped twice, on a real kernel row")
        // The planted program above proves the parser names the right frame. This proves it loses
        // nothing on real input: 1,951 collapsed lines from `nestedPayloadsUnwrapInMaps`, and the
        // flat table from the *same* JMH recording, so conservation here measures the parse and not
        // run-to-run variance. Each stack is stored truncated to its last eight frames, because the
        // untruncated file is 58 MB; the frames that decide the parse are the last two, and the
        // root-first property still holds since frame zero is still not the allocated class.
        val realFlat      = Bench.parseAlloc(read("qa-alloc-flat-real.txt"))
        val realCollapsed = Bench.parseCollapsed(read("qa-alloc-collapsed-tail8.csv"))
        val realCons      = Bench.allocConservation(realFlat, realCollapsed)
        ok &= check("conservation is exact on one recording", realCons.isEmpty, realCons.mkString("; "))
        ok &= check(
            "and it accounts for every sample the profiler counted",
            realCollapsed.map(_.samples).sum == realFlat.map(_.samples).sum,
            s"${realCollapsed.map(_.samples).sum} against ${realFlat.map(_.samples).sum}"
        )
        // the attribution the flat table cannot reach: half of this row's allocation is Nested, and
        // the collapsed view says all of it is minted at one site
        ok &= check(
            "the top allocated class is attributed to a single site",
            Bench.apportion(realFlat, realCollapsed).headOption
                .exists(m => m.cls == "kyo.kernel.proto.Nested" && m.method == "kyo.kernel.proto.Nested$.apply"),
            realCollapsed.take(2).map(_.show).mkString(" | ")
        )
        // the immediate frame is the type's own factory and decides nothing. The frame outside it is
        // the whole answer: on this row every Nested is asked for by the benchmark's own boxing, not
        // by any kernel arm, which is what refutes candidate C1 on its stated field.
        ok &= check(
            "and the caller outside the type's own code is named too",
            realCollapsed.headOption.exists(_.site == "kyo.kernel.bench.ProtoKernelBench$.boxed"),
            realCollapsed.headOption.map(_.show).getOrElse("")
        )
        ok &= check(
            "a site that is already outside the type is left alone",
            plantedCollapsed.head.site == "Planted.plantedAllocator",
            plantedCollapsed.head.show
        )
        Bench.apportion(realFlat, realCollapsed).foreach(m => println(s"       ${m.show}"))

        println(if ok then "\nPHASE 1 PASS" else "\nPHASE 1 FAIL")
        if !ok then sys.exit(1)
    end main
end QaParsers
