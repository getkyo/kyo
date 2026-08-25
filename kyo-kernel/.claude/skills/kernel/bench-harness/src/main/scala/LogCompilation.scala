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
    // boundary-anchored: an unanchored `count='` also matches `receiver_count='`, which reads
    // correctly today only because count always precedes it. On a site where it did not, the parser
    // would take the receiver count as the call count and then call the site monomorphic, since
    // `recvd == count` becomes trivially true.
    private val AttrCount  = """(?<![\w_])count='(\d+)'""".r
    private val AttrRecv   = """receiver='(\d+)'""".r
    private val AttrRecvN  = """receiver_count='(\d+)'""".r

    // by shape, for the same reason as the rest: fixing `id` before `name` is one reordering away
    // from silently dropping the symbol table
    private val Klass = """<klass [^>]*>""".r
    // `bytes` and `iicount` are absent on the `unloaded='1'` form, so requiring both dropped 89 of 4466 declarations and left 118 call
    // sites resolving to raw ids downstream.
    private val Method     = """<method [^>]*>""".r
    private val AttrId     = """(?<![\w_])id='(\d+)'""".r
    private val AttrHolder = """holder='(\d+)'""".r
    /** The log is XML, so its attribute values are escaped, and a constructor arrives as `&lt;init&gt;`.
      *
      * Every other tool in this ladder prints `<init>`: javap, async-profiler, the JVM's own
      * `PrintInlining`. A name that agrees with none of them cannot be cross-referenced against any of
      * them, and the failure is silent, a lookup that simply finds nothing.
      */
    def unescape(s: String): String =
        if s.indexOf('&') < 0 then s
        else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private val AttrName   = """name='([^']+)'""".r
    private val AttrBytes  = """bytes='(\d+)'""".r

    // matched by shape, then classified by attribute. Anchoring on `bci=` saw only 512 of the 633
    // planted traps: 121 of them lead with `method=` instead and carry `bci` further in. That is the
    // same defect as the old `<call>` and `<method>` patterns, which is why nothing here anchors on
    // attribute order any more.
    private val Trap       = """<uncommon_trap [^>]*>""".r
    private val AttrThread = """thread='(\d+)'""".r
    private val AttrReason = """reason='([^']+)'""".r
    private val AttrAction = """action='([^']+)'""".r
    private val NotEntrant = """<make_not_entrant""".r

    private val Inlined    = """<inline_success [^>]*>""".r
    private val NotInlined = """<inline_fail [^>]*>""".r

    // matched by shape for the same reason as everything else here: OSR tasks carry
    // `compile_kind='osr'` between `compile_id` and `method`, so a pattern fixing that order missed
    // all 5 of them, reporting 0 OSR tasks and 3 fewer C2 tasks than the fork performed.
    private val TaskOpen  = """<task [^>]*>""".r
    private val AttrCompileId = """compile_id='(\d+)'""".r
    private val AttrName2 = """method='([^']+)'""".r
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
        var trapsParsed  = 0
        var callsParsed  = 0
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
            Maybe.fromOption(re.findFirstMatchIn(s).map(m => unescape(m.group(1))))

        // counted by a deliberately loose scan that shares no pattern with the matchers below.
        // Incrementing `seen` inside the parse loop made coverage self-referential: narrowing a
        // matcher shrank numerator and denominator together, so reintroducing the historical
        // 4456-element drop reported 637/637 and 100% coverage with every check green.
        def occurrences(needle: String): Int =
            var n = 0
            var i = raw.indexOf(needle)
            while i >= 0 do
                n += 1
                i = raw.indexOf(needle, i + needle.length)
            n
        val callsSeenLoose   = occurrences("<call ")
        val methodsSeenLoose = occurrences("<method ")
        val trapsSeenLoose   = occurrences("<uncommon_trap ")

        raw.linesIterator.foreach { line =>
            TaskOpen.findFirstMatchIn(line).map(_.matched).foreach { el =>
                flush()
                // ids are per-task, so the symbol table starts over with every compilation
                klasses = Map.empty
                methods = Map.empty
                // read from the task element itself, never from the line. A `<method>` element
                // sharing the line carries its own `level='3'`, and scanning the whole line picked
                // that up instead: both stored production runs recorded 0 C2 tasks for a fork that
                // actually performed 88 of them.
                val lvl = TaskLevel.findFirstMatchIn(el).map(_.group(1).toInt).getOrElse(4)
                val st  = TaskStamp.findFirstMatchIn(el).map(_.group(1).toDouble).getOrElse(0.0)
                val id  = attr(AttrCompileId, el).map(_.toInt).getOrElse(-1)
                val mth = attr(AttrName2, el).getOrElse("unknown")
                current = Maybe((id, mth, lvl, TaskOsr.findFirstMatchIn(el).isDefined, st))
            }

            Klass.findAllMatchIn(line).map(_.matched).foreach { el =>
                for
                    id <- attr(AttrId, el)
                    nm <- attr(AttrName, el)
                yield klasses += id -> nm
            }

            Method.findAllMatchIn(line).map(_.matched).foreach { el =>
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

            Trap.findAllMatchIn(line).map(_.matched).foreach { el =>
                val kind =
                    for
                        reason <- attr(AttrReason, el)
                        action <- attr(AttrAction, el)
                    yield s"$reason/$action"
                kind.foreach { k =>
                    trapsParsed += 1
                    // `thread=` means a running method fell back to the interpreter. Anything else is
                    // a guard planted during compilation, which is a property of the code, not an event.
                    if attr(AttrThread, el).isDefined then runtime = runtime.append(k)
                    else planted = planted.append(k)
                }
            }
            notEntrant += NotEntrant.findAllMatchIn(line).size

            Call.findAllMatchIn(line).map(_.matched).foreach { el =>
                attr(AttrMethod, el).foreach { id =>
                    callsParsed += 1
                    pending = Maybe(id)
                    // `count='-1'` is HotSpot's no-profile marker; 27 C2 sites carry it. Reading it
                    // as a count would make a site look profiled-with-negative-calls.
                    val count = attr(AttrCount, el).map(_.toLong).filter(_ >= 0).getOrElse(0L)
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
            Inlined.findFirstMatchIn(line).map(_.matched).foreach(el => verdict(attr(AttrReason, el).getOrElse(""), true))
            NotInlined.findFirstMatchIn(line).map(_.matched).foreach(el => verdict(attr(AttrReason, el).getOrElse(""), false))
        }
        flush()
        Parsed(
            out,
            runtime,
            notEntrant,
            Chunk(
                ParseCoverage("call sites", callsSeenLoose, callsParsed),
                ParseCoverage("method declarations", methodsSeenLoose, methodsKnown),
                ParseCoverage("uncommon traps", trapsSeenLoose, trapsParsed)
            )
        )
    end parse

    /** Inlining decisions per method, keeping every site, **from C2 compilations only**.
      *
      * The tier filter is not a refinement, it is the difference between a signal and noise. In a captured run, of 1969 refusals 1929 come
      * from C1 tier 3 and 40 from C2, and the split by reason is total:
      *
      *   - `callee is too large`: 1166 in C1, **0** in C2
      *   - `no static binding`: 205 in C1, **0** in C2
      *   - `callee uses too much stack`: 173 in C1, **0** in C2
      *
      * C1 runs for roughly the first two warmup iterations (7.59 us/op, then 5.96, against a measured 5.84-5.90), so those refusals are worth
      * about 29% of one warmup iteration and nothing at all of the reported score. C1 also has no frequency tier, only a 35-byte gate, which
      * is why a 37-byte method is refused there and inlined hot by C2. Reporting C1 verdicts as a method's inlining behaviour would point
      * every optimization at code the measured score never executes.
      *
      * Deliberately not folded to one verdict either: 11 of 85 kyo methods carry both, and for two the refusal rests on a single site of six.
      */
    def inlining(p: Parsed, prefix: String = "kyo."): Chunk[InlineSites] =
        Chunk.from(
            p.tasks.filter(_.level >= 4).flatMap(_.inlines).filter(_.method.startsWith(prefix))
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
            // recompilation at the SAME tier. Counting every method with more than one task counted
            // ordinary tier escalation: of 84 such methods in a capture, 74 are a level-3 compile
            // followed by a level-4 one, which tiered compilation performs on every hot method by
            // design and which says nothing about an unstable profile.
            recompiled = byMethod.count((_, ts) => ts.groupBy(_.level).exists(_._2.size > 1)),
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

    /** Methods whose C2 inlining verdict differs between two logs.
      *
      * The efficacy gate. When an isolation run changes a JVM flag, this answers the only question that licenses reading its score: did the
      * flag do anything? A harness whose flags silently fail to take would refute every hypothesis it tested and pass its own acceptance,
      * which is the most dangerous failure available to it.
      *
      * It also names *what* moved, which is how a budget experiment turns into a mechanism: raising `FreqInlineSize` from 325 to 600 moved 57
      * methods, and the one that mattered was a 379-byte continuation body going from refused-at-every-site to inlined.
      */
    // takes the aggregated per-method sites, which is what a stored `Run` carries in `jit`, rather than the raw `Parsed` a stored run does
    // not keep. That is the difference between a function reachable only from a fresh parse and one a comparison of two stored runs can call
    def diffVerdicts(before: Chunk[InlineSites], after: Chunk[InlineSites]): Chunk[VerdictChange] =
        val b = before.map(v => v.method -> v).toMap
        val a = after.map(v => v.method -> v).toMap
        Chunk.from(
            (b.keySet ++ a.keySet).toSeq.sorted.flatMap { m =>
                val bs = b.getOrElse(m, InlineSites(m, 0, 0, 0, Chunk.empty))
                val as = a.getOrElse(m, InlineSites(m, 0, 0, 0, Chunk.empty))
                if bs.inlined == as.inlined && bs.refused == as.refused then None
                else Some(VerdictChange(m, Math.max(bs.bytes, as.bytes), bs, as))
            }
        )

    /** Whether a configuration change had any effect on inlining at all.
      *
      * Reported as a count and a size-refusal delta rather than a boolean, because "the flag took" is a matter of degree: one verdict moving
      * is noise, fifty-seven moving with size refusals falling from 31 to 20 is a flag doing its job.
      */
    def efficacy(before: Parsed, after: Parsed, prefix: String = "kyo."): (Int, Int, Int) =
        val changed = diffVerdicts(inlining(before, prefix), inlining(after, prefix)).size
        // counted per site, not per method: a method that went from ten refusals to six still has
        // some, so a method-level count reports no change where more than a third of the refusals
        // actually went away
        def sizeRefusals(p: Parsed) =
            inlining(p, prefix).filter(_.reasons.exists(r => r.contains("too big") || r.contains("too large"))).map(_.refused).sum
        (changed, sizeRefusals(before), sizeRefusals(after))

    /** Methods refused for size while close enough to a budget that shrinking them could flip the verdict, worst first. */
    def budgetCandidates(p: Parsed, prefix: String = "kyo."): Chunk[InlineSites] =
        Chunk.from(inlining(p, prefix).filter(_.nearBudget.isDefined).sortBy(-_.refused))

    /** Sites the log carries no receiver profile for. Reported as a count, never as a classification. */
    def unprofiledSites(p: Parsed, prefix: String = "kyo."): Int =
        p.tasks.flatMap(_.calls).count(c => !c.profiled && c.callee.startsWith(prefix))

end LogCompilation
