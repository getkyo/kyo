import kyo.*

/** What a run captured, and what a comparison of two runs concluded.
  *
  * Records are persisted as json and every comparison is a pure function over them, so re-reading what the JIT decided about a method, or
  * re-deriving a verdict, never re-runs a benchmark and never varies between readings.
  */
object Model:

    /** How much of the evidence ladder a run collected. A comparison built from anything below `Full` cannot attribute a movement to a
      * mechanism, and says so rather than leaving the reader to assume one was checked.
      */
    enum Evidence derives Schema, CanEqual:
        case Timing, Full

    /** One benchmark row: the score with its error, plus the allocation JMH's gc profiler attributes to it. */
    case class Row(
        name: String,
        mode: String,
        count: Int,
        score: Double,
        error: Double,
        unit: String,
        /** The per-iteration series JMH recorded, kept rather than reduced to a count.
          *
          * A summary score hides the shape that produced it. A real control leg read
          * `[86.4, 80.8, 81.4, 78.5, 78.6]`: a warmup ramp whose first iteration inflated both the
          * mean and the error, and which the harness could not see at all because it kept only the
          * count. That leg then carried a -9.8% "win" that was substantially its own unsettled start.
          */
        iterations: Chunk[Double],
        allocPerOp: Maybe[Double],
        /** Milliseconds the JIT spent compiling *during the measured window*. Non-trivial values mean the JVM had not reached steady state
          * and the score describes a mixture of compiled and compiling code.
          */
        compilerMsProfiled: Maybe[Double],
        compilerMsTotal: Maybe[Double]
    ) derives Schema:
        /** Compilation during measurement as a fraction of the measured wall time. */
        def compilingShare(measuredMs: Double): Maybe[Double] = compilerMsProfiled.map(_ / measuredMs * 100)

        /** This row's own relative uncertainty, as a fraction of its score. */
        def relativeError: Double = if score <= 0.0 then 0.0 else error / score

        /** How far the first measured iteration sits from the median of the rest, as a fraction.
          *
          * A leg still warming up shows it here and nowhere else: the score and the error absorb the
          * ramp without revealing it. This is the only steady-state signal available to a run ingested
          * from outside the harness, which carries no `compiler.time.profiled`.
          */
        def warmupRamp: Maybe[Double] =
            if iterations.size < 3 then Maybe.empty
            else
                val rest   = iterations.tail.sorted
                val median = rest(rest.size / 2)
                if median <= 0.0 then Maybe.empty else Maybe(Math.abs(iterations.head - median) / median)

        /** Whether the first iteration is an outlier against the spread of the remaining ones.
          *
          * A bare percentage cannot decide this: 5% is ordinary jitter on a noisy row and a serious
          * ramp on a stable one. The remaining iterations already say how much this row varies when
          * nothing is warming up, so the question is whether the first sits outside that, by a margin.
          *
          * The margin is a factor of two over the widest deviation among the rest, with a small
          * absolute floor so a perfectly stable row does not trip on rounding.
          */
        def startBias: Maybe[Double] =
            if iterations.size < 4 then Maybe.empty
            else
                val rest     = iterations.tail
                val restMean = rest.sum / rest.size
                // measured against this row's OWN iterations, never against `score`. A delta from a
                // replicated bracket carries the mean across legs in `score`, so comparing it to one
                // leg's iterations measures the gap between legs and calls it a warmup ramp. That
                // misfired on 26.5, 27.3, 27.2, 27.3, 27.2, a series whose own mean is stable.
                val allMean = iterations.sum / iterations.size
                if restMean <= 0.0 then Maybe.empty
                else Maybe((allMean - restMean) / restMean)

        /** Whether the first iteration both stands out from the rest and moves the reported score.
          *
          * Two earlier criteria were tried against real data and both failed. "First iteration more
          * than N% from the median" cannot work with a fixed N: 5% is jitter on a noisy row and a
          * serious ramp on a stable one. "First iteration is an outlier against the rest's spread"
          * fails differently: two real rows tripped it at 2.43x and 2.53x, one a genuine ramp and one
          * a 0.53us row whose absolute jitter is negligible.
          *
          * What separates them is the quantity with consequences: how far the first iteration drags
          * the mean the verdict is computed from. On those two rows that is 1.64% against 0.59%.
          * Outlier status still has to hold, so ordinary noise in a single direction does not qualify.
          */
        def unsettledStart: Boolean =
            if iterations.size < 4 then false
            else
                val rest   = iterations.tail
                val sorted = rest.sorted
                val median = sorted(sorted.size / 2)
                if median <= 0.0 then false
                else
                    val spread   = rest.map(x => Math.abs(x - median)).max
                    val first    = Math.abs(iterations.head - median)
                    val isOutlier = first > 2.0 * spread
                    isOutlier && startBias.exists(b => Math.abs(b) > 0.01)
    end Row

    /** One inlining decision at one call site, as the JIT reported it. The byte count is the part that matters: it is what turns a delivery
      * path from always-inlined into never-inlined, and it is invisible to every other tool.
      *
      * This is a *site* record, never a method's verdict. The distinction is load-bearing: see `InlineSites`.
      */
    case class JitEntry(method: String, bytes: Int, inlined: Boolean, reason: String) derives Schema

    /** Every inlining decision the JIT made about one method, kept per site.
      *
      * Folding a method's sites to a single worst verdict is what let one warmup-era refusal, at one site out of six, decide the method's
      * verdict for a whole leg. Two runs of an identical comparison named disjoint mechanisms that way, one of them a 2-byte method
      * "refused" for size. A verdict is only meaningful with its denominator, so the denominator is carried.
      */
    case class InlineSites(method: String, bytes: Int, inlined: Int, refused: Int, reasons: Chunk[String]) derives Schema:
        def sites: Int = inlined + refused

        /** True when the JIT refused at every site. Anything short of that is a mixed verdict and is reported as a fraction. */
        def alwaysRefused: Boolean = inlined == 0 && refused > 0

        /** True when the JIT inlined at every site. */
        def alwaysInlined: Boolean = refused == 0 && inlined > 0

        def show: String =
            if alwaysInlined then s"${bytes}B inlined"
            else if alwaysRefused then s"${bytes}B refused (${reasons.headMaybe.getOrElse("no reason")})"
            else s"${bytes}B refused at $refused/$sites sites (${reasons.headMaybe.getOrElse("no reason")})"

        /** Whether this method is refused for size while sitting close enough to a budget that shrinking it could plausibly flip the verdict.
          *
          * The distinction a bare refusal list cannot make: a 379-byte body refused against a 325-byte budget is one edit away from
          * inlining, and a 4000-byte one is furniture. That 379-byte case was the whole mechanism behind a regression this harness had failed
          * to explain for two sessions, and finding it took a manual read of the log because nothing here ranked refusals this way.
          *
          * Within twice the budget is the window: far enough to catch a method that needs real work, near enough to exclude the hopeless.
          */
        def nearBudget: Maybe[String] =
            val sizeRefusal = reasons.exists(r => r.contains("too big") || r.contains("too large"))
            if !sizeRefusal || refused == 0 then Maybe.empty
            else
                val budgets = Seq(35 -> "MaxInlineSize", 325 -> "FreqInlineSize")
                Maybe.fromOption(
                    budgets.collectFirst {
                        case (limit, name) if bytes > limit && bytes <= limit * 2 =>
                            f"${bytes}B against $name ($limit), ${bytes.toDouble / limit}%.2fx over"
                    }
                )
    end InlineSites

    /** One method's inlining verdict moving between two runs of the same sources under different conditions.
      *
      * Proving that a JVM flag actually took effect requires exactly this and nothing more. Without it the efficacy gate, which is the step
      * that separates a measured mechanism from a confident story, is a manual diff of two logs and therefore in practice optional.
      */
    case class VerdictChange(method: String, bytes: Int, before: InlineSites, after: InlineSites) derives Schema:
        def show: String = s"$method: ${before.inlined}ok/${before.refused}fail -> ${after.inlined}ok/${after.refused}fail"

    /** How much of an artifact a parser actually consumed.
      *
      * A parser that reads 637 of 5093 elements and reports what it found looks identical to one that read all of them. Carrying the
      * fraction turns a silent partial parse into a visible one, and lets a test fail when the fraction drops.
      */
    case class ParseCoverage(what: String, seen: Int, parsed: Int) derives Schema:
        def complete: Boolean = seen == parsed
        def show: String      = if complete then s"$what $parsed/$seen" else s"$what $parsed/$seen (INCOMPLETE)"

    /** Bytes allocated by one class, as the allocation profiler attributed them.
      *
      * `samples` is carried alongside `bytes` because it is the only currency the two allocation
      * views share: the flat table reports both, the collapsed view reports samples only, and a
      * conservation check between them has to compare like with like.
      */
    case class AllocSite(cls: String, bytes: Long, samples: Long = 0L) derives Schema

    /** Which method allocated a class, from the collapsed (FlameGraph folded) view.
      *
      * The flat table answers *what* was allocated and never *by whom*: four classes and four byte
      * totals say nothing about which of the twenty call sites produced them. The collapsed view
      * carries the whole stack per sample, so the allocating frame is recoverable, and that is the
      * difference between "Nested is half the allocation" and "half the allocation is Nested, minted
      * at this one site".
      *
      * Its value is a sample count, never bytes: the collapsed dump JMH issues carries no `total`
      * option. `bytes` here is therefore apportioned from the flat table's byte figure for the same
      * class, in proportion to samples, and is labelled an estimate everywhere it is shown.
      *
      * The confound that cannot be removed: with inlining, the frame the profiler names is where the
      * JIT *placed* the allocation, not where the source wrote it. An inlining change relocates this
      * attribution without anything about the allocation changing, so a per-method allocation
      * mechanism is never independent of the inlining mechanism, and the report says so.
      */
    case class AllocByMethod(cls: String, method: String, samples: Long, bytes: Maybe[Double] = Maybe.empty) derives Schema:
        def show: String =
            val b = bytes.map(x => f", ~${x}%.0f B").getOrElse("")
            s"$cls minted at $method ($samples samples$b)"

    /** A sampled method and the nanoseconds attributed to it. */
    case class CpuSite(method: String, nanos: Long) derives Schema

    /** What compiling this leg cost, and whether it finished in time.
      *
      * Collected because a design that takes materially longer to reach steady state is telling you something: bigger methods, more
      * recompilation, or an unstable profile that keeps deoptimising. Treating that as noise to warm past would discard the finding.
      */
    case class JitMetrics(
        /** Milliseconds compiling during the measured window. Above the threshold the score is a mixture, not a steady state. */
        msInWindow: Double,
        /** Milliseconds compiling over the whole fork. */
        msTotal: Double,
        tasks: Int,
        c2Tasks: Int,
        /** Tasks compiling a loop that was already running. The JMH stub loop is one of these, and it is where the measured code actually
          * runs, so folding them into `tasks` hides the compilation that matters most.
          */
        osrTasks: Int,
        recompiled: Int,
        /** Deoptimizations that actually happened: `<uncommon_trap thread=...>`, emitted when a running method falls back to the
          * interpreter. In a captured run there were 6 of these.
          */
        runtimeDeopts: Int,
        /** Guards the compiler planted while compiling (`bci=`), a property of the code shape rather than an event. There were 633 in the
          * same run, and summing them with the above produced a "deoptimizations 642 vs 645" row that compared guard censuses.
          */
        plantedTraps: Int,
        /** Compiled methods invalidated and scheduled for recompilation. The real recompilation signal, and previously unparsed. */
        madeNotEntrant: Int,
        /** Seconds from JVM start to the last compilation. Compare against when measurement began. */
        lastCompileAt: Double
    ) derives Schema

    /** A deoptimization reason and how often it fired.
      *
      * Invisible to every other tool here. A method whose profile went unstable falls back to the interpreter and is recompiled, which is a
      * real cost that no timing or allocation figure explains.
      */
    case class Deopt(reason: String, count: Int) derives Schema

    /** A call site's receiver profile, aggregated by callee.
      *
      * `monomorphic` is `Absent` when the log carries no receiver profile for the site, which is the common case by a wide margin: 12 of
      * 5093 call elements in a captured run have one. Absence means the JIT never profiled the site as a virtual call, typically because it
      * was statically bound or already devirtualized. It emphatically does not mean megamorphic.
      *
      * Encoding that as `Maybe` rather than `false` is the whole point. With a boolean, "no data" and "many receivers" are the same value,
      * and a report printed 46 of 47 sites under a heading promising measured receiver counts when every one of them had a receiver count of
      * zero. A site with no profile is now unrepresentable as a classification.
      */
    case class CallMorphism(callee: String, count: Long, receiverCount: Long, monomorphic: Maybe[Boolean]) derives Schema:
        /** Sites the JIT actually profiled, the only ones anything may be said about. */
        def profiled: Boolean = monomorphic.isDefined

    /** A source pattern whose occurrence count identifies which design a tree held. */
    case class Marker(name: String, count: Int) derives Schema

    /** The machine and session a run belongs to.
      *
      * Comparing runs across sessions is the mistake that made a row look 3.6% slower when back-to-back it was parity, so the session is
      * carried on every run and checked at comparison time. `driftPercent` is measured by running the control twice at session start rather
      * than assumed, which is what lets a verdict be classified against this machine's actual noise floor.
      */
    case class Session(id: String, host: String, jvm: String, driftPercent: Double) derives Schema

    /** Everything one leg produced, with the provenance needed to trust it. */
    case class Run(
        id: String,
        session: Session,
        label: String,
        sha: String,
        /** Hash of the measured sources, sampled before and after the leg: a change mid-run invalidates it. */
        treeHash: String,
        forks: Int,
        evidence: Evidence,
        wholeClass: Boolean,
        declaredRows: Int,
        markers: Chunk[Marker],
        /** False when the measured window still contained meaningful compilation. A comparison built from such a leg cannot be trusted, so
          * the fact travels with the record rather than living only in a printed line.
          */
        /** Warmup iterations used. Fixed per session so legs are comparable, never silently escalated. */
        warmup: Int,
        jit_metrics: Maybe[JitMetrics],
        rows: Chunk[Row],
        /** Inlining decisions per method, with every site kept. Sourced from the compilation log, which supersedes `PrintInlining`
          * entirely: that tool interleaves output across compiler threads and reports no denominator.
          */
        jit: Chunk[InlineSites],
        /** How much of each parsed artifact was actually consumed, so a silent partial parse is visible in the record. */
        coverage: Chunk[ParseCoverage],
        alloc: Chunk[AllocSite],
        /** Who allocated each class, from the collapsed view of the same recording.
          *
          * Defaulted, and that default is load-bearing rather than a convenience: adding this field
          * without one made every run already in the store undecodable, all 34 of them, with the
          * whole campaign's data behind them. A stored run is the durable record; a field added to
          * `Run` must leave the old ones readable, and `StoreSchemaTest` decodes a run recorded before
          * this field existed to keep that true.
          */
        allocByMethod: Chunk[AllocByMethod] = Chunk.empty,
        cpu: Chunk[CpuSite],
        deopts: Chunk[Deopt],
        morphism: Chunk[CallMorphism],
        recordedAt: String
    ) derives Schema:
        def row(name: String): Maybe[Row]              = Maybe.fromOption(rows.find(_.name == name))
        def jitFor(method: String): Maybe[InlineSites] = Maybe.fromOption(jit.find(_.method == method))
    end Run

    enum Verdict derives Schema, CanEqual:
        case Faster, Flat, Regressed, BelowResolution

    /** How small an effect this row could have detected, and on what basis.
      *
      * Carried on every delta, including flat ones. "Flat" without a resolution is not a result: it is indistinguishable from an instrument
      * that cannot see anything, and a harness unable to say how small an effect it would have caught is not entitled to call a row
      * unchanged.
      */
    case class Resolution(percent: Double, absolute: Double, df: Int, alpha: Double) derives Schema:
        def show: String = f"detectable at +-${percent}%.2f%% (df=$df, alpha=$alpha%.5f)"

    /** A row's movement between two runs, together with whether anything in the evidence moved with it.
      *
      * `mechanism` is the point of the whole harness: a timing change with no allocation change, no inlining change and no shift in the
      * sampled profile is either noise or an unexplained mechanism. Saying which is not the tool's job, but refusing to let the reader
      * assume one was found is.
      */
    /** Whether a row's remaining time is mostly the kernel's or mostly the benchmark's own overhead.
      *
      * On the suspension rows roughly 60% of samples are the benchmark boxing its own `Int`s, so a percentage on those rows is a percentage
      * of something the kernel cannot change, and reading it as kernel work overstates the effect.
      */
    case class NoiseShare(row: String, fraction: Double) derives Schema

    case class Delta(
        row: String,
        control: Row,
        variant: Row,
        percent: Double,
        verdict: Verdict,
        allocDelta: Maybe[Double],
        mechanism: Chunk[String],
        /** Present when the session replicated its legs. Absent means the verdict rests on a single pair, which cannot support a threshold. */
        resolution: Maybe[Resolution] = Maybe.empty
    ) derives Schema:
        def unexplained: Boolean = verdict != Verdict.Flat && verdict != Verdict.BelowResolution && mechanism.isEmpty

        /** A flat row with no resolution is not evidence of no change; it is evidence of nothing. */
        def flatButUnbounded: Boolean = verdict == Verdict.Flat && resolution.isEmpty
    end Delta

    case class Comparison(
        control: Run,
        variant: Run,
        deltas: Chunk[Delta],
        jitChanges: Chunk[String]
    ) derives Schema

end Model
