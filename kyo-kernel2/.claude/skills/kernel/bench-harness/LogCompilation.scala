import Model.*
import kyo.*

/** Parses HotSpot's `-XX:+LogCompilation` XML.
  *
  * This is the sole source of inlining decisions. `PrintInlining` reports a verdict and a byte count and nothing reliable beyond that: its
  * output interleaves across compiler threads, so its tree cannot be trusted. Here every decision sits beside the `<method>` element that
  * declares the callee's size, in structure, and the log additionally carries what that one cannot:
  *
  *   - deoptimizations that actually happened, which are invisible everywhere else
  *   - receiver counts per call site, so monomorphic against megamorphic is measured rather than asserted
  *   - OSR compilations, which is where a benchmark's loop body actually lives
  *
  * Ids are scoped to a compilation task and reused across tasks, so the symbol table is rebuilt per `<task>`.
  *
  * Three distinctions here were each responsible for a wrong conclusion, and are preserved deliberately:
  *
  *   1. A call site with no `receiver` attribute is *unprofiled*, not megamorphic.
  *   2. An `<uncommon_trap>` with `thread=` is an event; one with `bci=` is a guard the compiler planted.
  *   3. A method's inlining verdict is per site. It has a denominator, and reporting it without one is a coin flip.
  */
object LogCompilation:

    // `<call>` appears in two forms: the C2 form carrying `count` (and sometimes `receiver`), and the C1 form carrying only `instr`.
    // Matching just the first silently dropped 4456 of 5093 elements, so the shape is matched first and the attributes read after.
    private val Call       = """<call [^>]*>""".r
    private val AttrMethod = """method='(\d+)'""".r
    private val AttrCount  = """count='(\d+)'""".r
    private val AttrRecv   = """receiver='(\d+)'""".r
    private val AttrRecvN  = """receiver_count='(\d+)'""".r

    private val Klass = """<klass id='(\d+)' name='([^']+)'""".r
    // `bytes` and `iicount` are absent on the `unloaded='1'` form, so requiring both dropped 89 of 4466 declarations and left 118 call
    // sites resolving to raw ids downstream.
    private val Method     = """<method [^>]*>""".r
    private val AttrId     = """id='(\d+)'""".r
    private val AttrHolder = """holder='(\d+)'""".r
    private val AttrName   = """name='([^']+)'""".r
    private val AttrBytes  = """bytes='(\d+)'""".r

    private val TrapRuntime = """<uncommon_trap thread='[^']*'[^>]*reason='([^']+)' action='([^']+)'""".r
    private val TrapPlanted = """<uncommon_trap bci=[^>]*reason='([^']+)' action='([^']+)'""".r
    private val NotEntrant  = """<make_not_entrant""".r

    private val Inlined    = """<inline_success reason='([^']*)'""".r
    private val NotInlined = """<inline_fail reason='([^']*)'""".r

    private val TaskOpen  = """<task compile_id='(\d+)' method='([^']+)'""".r
    private val TaskLevel = """level='(\d+)'""".r
    private val TaskStamp = """stamp='([\d.]+)'""".r
    private val TaskOsr   = """osr_bci=""".r

    /** One compilation's worth of facts. */
    case class Task(
        compileId: Int,
        method: String,
        level: Int,
        osr: Boolean,
        stamp: Double,
        /** Guards the compiler planted while compiling this method. Task-scoped because that is what they are: a property of the code the
          * compiler saw.
          */
        plantedTraps: Chunk[String],
        calls: Chunk[CallMorphism],
        inlines: Chunk[JitEntry]
    )

    /** Everything one log yielded, including how much of it was consumed.
      *
      * `runtimeDeopts` lives here rather than on `Task` because a deoptimization is not task-scoped: it happens while compiled code runs. In
      * a captured log all 6 of them precede the first `<task>` element entirely, so modelling them as task children dropped every one while
      * the parser looked correct.
      */
    case class Parsed(
        tasks: Chunk[Task],
        runtimeDeopts: Chunk[String],
        madeNotEntrant: Int,
        coverage: Chunk[ParseCoverage]
    )

    def parse(raw: String): Parsed =
        var klasses = Map.empty[String, String]
        var methods = Map.empty[String, (String, String, Int)] // id -> (holder klass id, name, bytes)
        var current = Maybe.empty[(Int, String, Int, Boolean, Double)]
        // run-scoped: a deoptimization happens while compiled code runs, not while a task compiles
        var runtime = Chunk.empty[String]
        var planted = Chunk.empty[String]
        var calls   = Chunk.empty[CallMorphism]
        var inlines = Chunk.empty[JitEntry]
        // `<call>` names the callee; the verdict follows on the next inline element
        var pending      = Maybe.empty[String]
        var out          = Chunk.empty[Task]
        var notEntrant   = 0
        var callsSeen    = 0
        var callsParsed  = 0
        var methodsSeen  = 0
        var methodsKnown = 0

        def flush(): Unit =
            current.foreach { (id, m, lvl, osr, st) =>
                out = out.append(Task(id, m, lvl, osr, st, planted, calls, inlines))
            }
            planted = Chunk.empty
            calls = Chunk.empty
            inlines = Chunk.empty
            pending = Maybe.empty

        def name(methodId: String): String =
            methods.get(methodId) match
                case Some((holder, n, _)) => s"${klasses.getOrElse(holder, holder)}::$n"
                case None                 => s"method#$methodId"

        def attr(re: scala.util.matching.Regex, s: String): Maybe[String] =
            Maybe.fromOption(re.findFirstMatchIn(s).map(_.group(1)))

        raw.linesIterator.foreach { line =>
            TaskOpen.findFirstMatchIn(line).foreach { m =>
                flush()
                // ids are per-task, so the symbol table starts over with every compilation
                klasses = Map.empty
                methods = Map.empty
                // HotSpot emits level only for the tiered C1 levels; the top tier carries no
                // attribute at all, so an absent level means C2 rather than unknown
                val lvl = TaskLevel.findFirstMatchIn(line).map(_.group(1).toInt).getOrElse(4)
                val st  = TaskStamp.findFirstMatchIn(line).map(_.group(1).toDouble).getOrElse(0.0)
                current = Maybe((m.group(1).toInt, m.group(2), lvl, TaskOsr.findFirstMatchIn(line).isDefined, st))
            }

            Klass.findAllMatchIn(line).foreach(m => klasses += m.group(1) -> m.group(2))

            Method.findAllMatchIn(line).map(_.matched).foreach { el =>
                methodsSeen += 1
                val parsedMethod =
                    for
                        id     <- attr(AttrId, el)
                        holder <- attr(AttrHolder, el)
                        n      <- attr(AttrName, el)
                    yield
                        // the unloaded form declares no size; 0 is the honest reading, and keeping
                        // the entry is what stops the id going unresolved later
                        methods += id -> (holder, n, attr(AttrBytes, el).map(_.toInt).getOrElse(0))
                        ()
                if parsedMethod.isDefined then methodsKnown += 1
            }

            TrapRuntime.findAllMatchIn(line).foreach(m => runtime = runtime.append(s"${m.group(1)}/${m.group(2)}"))
            TrapPlanted.findAllMatchIn(line).foreach(m => planted = planted.append(s"${m.group(1)}/${m.group(2)}"))
            notEntrant += NotEntrant.findAllMatchIn(line).size

            Call.findAllMatchIn(line).map(_.matched).foreach { el =>
                callsSeen += 1
                attr(AttrMethod, el).foreach { id =>
                    callsParsed += 1
                    pending = Maybe(id)
                    val count = attr(AttrCount, el).map(_.toLong).getOrElse(0L)
                    val recvd = attr(AttrRecvN, el).map(_.toLong)
                    calls = calls.append(
                        CallMorphism(
                            callee = name(id),
                            count = count,
                            receiverCount = recvd.getOrElse(0L),
                            // classified only where the JIT actually profiled a receiver. Absence is
                            // not evidence of polymorphism, and encoding it as `false` is what put
                            // unprofiled sites under a heading promising measured receiver counts.
                            monomorphic = if attr(AttrRecv, el).isEmpty then Maybe.empty else Maybe(recvd.exists(_ == count))
                        )
                    )
                }
            }

            def verdict(reason: String, ok: Boolean): Unit =
                pending.foreach { id =>
                    inlines = inlines.append(JitEntry(name(id), methods.get(id).map(_._3).getOrElse(0), ok, reason))
                }
                pending = Maybe.empty
            Inlined.findFirstMatchIn(line).foreach(m => verdict(m.group(1), true))
            NotInlined.findFirstMatchIn(line).foreach(m => verdict(m.group(1), false))
        }
        flush()
        Parsed(
            out,
            runtime,
            notEntrant,
            Chunk(ParseCoverage("call sites", callsSeen, callsParsed), ParseCoverage("method declarations", methodsSeen, methodsKnown))
        )
    end parse

    /** Inlining decisions per method, keeping every site.
      *
      * Deliberately not folded to one verdict. 11 of 85 kyo methods in a captured run carry both verdicts, and for two of them the refusal
      * rests on a single site out of six. Folding turns that into a coin flip that reads as a mechanism.
      */
    def inlining(p: Parsed, prefix: String = "kyo."): Chunk[InlineSites] =
        Chunk.from(
            p.tasks.flatMap(_.inlines).filter(_.method.startsWith(prefix))
                .groupBy(_.method).toSeq
                .map { (method, es) =>
                    InlineSites(
                        method = method,
                        // sites disagree on size when a method is recompiled; the largest is the one that matters for a budget verdict
                        bytes = es.map(_.bytes).maxOption.getOrElse(0),
                        inlined = es.count(_.inlined),
                        refused = es.count(!_.inlined),
                        reasons = Chunk.from(es.filter(!_.inlined).map(_.reason).distinct)
                    )
                }
                .sortBy(v => (-v.refused, v.method))
        )

    /** Everything the compilation log says about what compiling this run cost. */
    def metrics(p: Parsed, profiledMs: Double, totalMs: Double): JitMetrics =
        val byMethod = p.tasks.groupBy(_.method)
        JitMetrics(
            msInWindow = profiledMs,
            msTotal = totalMs,
            tasks = p.tasks.size,
            c2Tasks = p.tasks.count(_.level >= 4),
            osrTasks = p.tasks.count(_.osr),
            // a method compiled more than once was recompiled after its profile changed
            recompiled = byMethod.count(_._2.size > 1),
            runtimeDeopts = p.runtimeDeopts.size,
            plantedTraps = p.tasks.map(_.plantedTraps.size).sum,
            madeNotEntrant = p.madeNotEntrant,
            lastCompileAt = if p.tasks.isEmpty then 0.0 else p.tasks.map(_.stamp).max
        )
    end metrics

    /** Runtime deoptimization reasons and how often each fired, worst first.
      *
      * Runtime events only. Compiler-planted guards are a property of the code the compiler saw, not of the run, and comparing their counts
      * between legs compares guard censuses while calling the difference a deoptimization change.
      */
    def deoptSummary(p: Parsed): Chunk[Deopt] =
        Chunk.from(
            p.runtimeDeopts.groupBy(identity).toSeq
                .map((reason, hits) => Deopt(reason, hits.size))
                .sortBy(-_.count)
        )

    /** Call sites the JIT profiled a receiver for, aggregated by callee, worst-polymorphism first.
      *
      * Only profiled sites appear. In a captured run that is 12 of 5093 call elements, so this answers a narrow question about a handful of
      * sites and cannot speak for the rest. Reporting the unprofiled majority as polymorphic is the failure this exists to prevent.
      */
    def morphism(p: Parsed, prefix: String = "kyo."): Chunk[CallMorphism] =
        Chunk.from(
            p.tasks.flatMap(_.calls).filter(c => c.profiled && c.callee.startsWith(prefix))
                .groupBy(_.callee).toSeq
                .map { (callee, cs) =>
                    CallMorphism(callee, cs.map(_.count).sum, cs.map(_.receiverCount).sum, Maybe(cs.forall(_.monomorphic.contains(true))))
                }
                .sortBy(c => (c.monomorphic.contains(true), -c.count))
        )

    /** Sites the log carries no receiver profile for. Reported as a count, never as a classification. */
    def unprofiledSites(p: Parsed, prefix: String = "kyo."): Int =
        p.tasks.flatMap(_.calls).count(c => !c.profiled && c.callee.startsWith(prefix))

end LogCompilation
