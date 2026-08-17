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

    /** A source pattern whose occurrence count identifies which design a tree held. */
    case class Marker(name: String, count: Int) derives Schema

    /** Everything one leg produced, with the provenance needed to trust it. */
    case class Run(
        id: String,
        label: String,
        sha: String,
        forks: Int,
        evidence: Evidence,
        wholeClass: Boolean,
        declaredRows: Int,
        markers: Chunk[Marker],
        rows: Chunk[Row],
        jit: Chunk[JitEntry],
        alloc: Chunk[AllocSite],
        cpu: Chunk[CpuSite],
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
