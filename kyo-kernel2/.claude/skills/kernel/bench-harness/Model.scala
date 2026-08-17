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
        allocPerOp: Maybe[Double]
    ) derives Schema

    /** One inlining decision, as the JIT reported it. The byte count is the part that matters: it is what turns a delivery path from
      * always-inlined into never-inlined, and it is invisible to every other tool.
      */
    case class JitEntry(method: String, bytes: Int, inlined: Boolean, reason: String) derives Schema

    /** Bytes allocated by one class, as the allocation profiler attributed them. */
    case class AllocSite(cls: String, bytes: Long) derives Schema

    /** A sampled method and the nanoseconds attributed to it. */
    case class CpuSite(method: String, nanos: Long) derives Schema

    /** A deoptimization reason and how often it fired.
      *
      * Invisible to every other tool here. A method whose profile went unstable falls back to the interpreter and is recompiled, which is a
      * real cost that no timing or allocation figure explains.
      */
    case class Deopt(reason: String, count: Int) derives Schema

    /** A call site's receiver profile, aggregated by callee.
      *
      * `monomorphic` is measured from the log's receiver counts rather than inferred from the shape of the code, which is the mistake that
      * produced a confident and wrong megamorphism claim.
      */
    case class CallMorphism(callee: String, count: Long, receiverCount: Long, monomorphic: Boolean) derives Schema

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
        rows: Chunk[Row],
        jit: Chunk[JitEntry],
        alloc: Chunk[AllocSite],
        cpu: Chunk[CpuSite],
        deopts: Chunk[Deopt],
        morphism: Chunk[CallMorphism],
        recordedAt: String
    ) derives Schema:
        def row(name: String): Maybe[Row] = Maybe.fromOption(rows.find(_.name == name))
        def jitFor(method: String): Maybe[JitEntry] = Maybe.fromOption(jit.find(_.method == method))
    end Run

    enum Verdict derives Schema, CanEqual:
        case Faster, Flat, Regressed, BelowResolution

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
        mechanism: Chunk[String]
    ) derives Schema:
        def unexplained: Boolean = verdict != Verdict.Flat && verdict != Verdict.BelowResolution && mechanism.isEmpty
    end Delta

    case class Comparison(
        control: Run,
        variant: Run,
        deltas: Chunk[Delta],
        jitChanges: Chunk[String]
    ) derives Schema

end Model
