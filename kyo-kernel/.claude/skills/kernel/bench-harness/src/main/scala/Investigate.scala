import Model.*
import kyo.*

/** Turns a delta into the next experiment, and adjudicates what that experiment returned.
  *
  * A comparison says a row moved. What to do about it has been, until now, whatever occurred to the
  * operator, which is how a redesign came to be credited to the wrong one of its two changes: the
  * hypothesis was formed from reading the code and never had a falsifier attached. Every entry here
  * is a hypothesis whose falsifier is a JVM flag, so testing it costs one more run of a configuration
  * the harness already knows how to issue, and the answer does not depend on anyone's reading.
  *
  * Two rules make the answers worth having, and both come from the ways this went wrong before:
  *
  *   - **A falsifier that did not take refutes nothing.** `CompileCommand=inline` is a hint; HotSpot
  *     still refuses on `MaxInlineLevel`, on node budget, or because the method is not compilable.
  *     A pipeline with no efficacy gate refutes every hypothesis and passes its own acceptance test,
  *     which is the failure mode this design was reviewed into fixing. So a falsifier whose flag did
  *     not take is `Inconclusive`, never `Refuted`.
  *   - **Forcing a callee changes the caller's remaining budget.** A confirm therefore requires that
  *     the instructed method's verdict moved *and* that nothing else's did. Otherwise the isolation
  *     changed more than one thing and attributes nothing, which is the same error one level down.
  */
object Investigate:

    /** Which leg the falsifier re-runs. The distinction matters: forcing the variant's refused method
      * asks whether the refusal caused the loss, forcing the control's asks whether inlining caused
      * the win, and they are different questions with different answers.
      */
    enum Arm derives CanEqual:
        case Control, Variant

    /** A claim about why a row moved, stated so a JVM flag can contradict it. */
    enum Hypothesis derives CanEqual:
        /** The variant stopped inlining this method, and that is the delta. */
        case StoppedInlining(method: String)

        /** This method is refused for size against a named budget; raising the budget should recover it. */
        case ExceededBudget(method: String, budget: String, limit: Int)

        /** The win came from inlining rather than from the layout change beside it. */
        case WinFromInlining(method: String)

        /** The row's allocation depends on scalar replacement, so an enlarged compilation unit that
          * defeats escape analysis would show up as an allocation cliff.
          */
        case RidesScalarReplacement(row: String)

        def target: Maybe[String] =
            this match
                case StoppedInlining(m)       => Maybe(m)
                case ExceededBudget(m, _, _)  => Maybe(m)
                case WinFromInlining(m)       => Maybe(m)
                case RidesScalarReplacement(_) => Maybe.empty

        def show: String =
            this match
                case StoppedInlining(m)          => s"$m stopped inlining, and that is the delta"
                case ExceededBudget(m, b, l)     => s"$m exceeded $b ($l bytes)"
                case WinFromInlining(m)          => s"the win came from inlining $m, not from the layout beside it"
                case RidesScalarReplacement(row) => s"$row rides scalar replacement"
    end Hypothesis

    /** One runnable experiment: a hypothesis, the leg to re-run, and the flags that contradict it. */
    case class Falsifier(hypothesis: Hypothesis, arm: Arm, jvmArgs: Seq[String], expectation: String):
        def show: String = s"${hypothesis.show}\n      re-run ${arm.toString.toLowerCase} with ${jvmArgs.mkString(" ")}\n      $expectation"

    /** What a falsifier's run established. */
    enum Outcome derives CanEqual:
        /** The flag took and the delta went away: the hypothesis survives its own falsifier. */
        case Confirmed(detail: String)

        /** The flag took and the delta stayed: the hypothesis is wrong. */
        case Refuted(detail: String)

        /** Nothing was established, and this is a real result rather than a soft refutation. */
        case Inconclusive(why: String)

        def show: String =
            this match
                case Confirmed(d)    => s"CONFIRMED: $d"
                case Refuted(d)      => s"REFUTED: $d"
                case Inconclusive(w) => s"INCONCLUSIVE: $w"
    end Outcome

    /** The rule table: which experiments a comparison earns.
      *
      * Only rows that actually moved generate work, and only methods the compilation log has a
      * verdict for generate a method-level hypothesis. Two candidate falsifiers from earlier drafts
      * are deliberately absent: megamorphism, because the log profiles a receiver at 12 sites out of
      * 5,093 and the hypothesis is not statable from that; and GC ergonomics, because every leg pins
      * the heap and the collector, so the condition cannot arise without unpinning them.
      */
    def falsifiers(c: Comparison): Chunk[Falsifier] =
        val regressed = c.deltas.filter(_.verdict == Verdict.Regressed)
        val wins      = c.deltas.filter(_.verdict == Verdict.Faster)

        // a method the control inlined everywhere and the variant refuses everywhere is the
        // strongest form of this hypothesis, and the only one a single flag can address. Only when the
        // refusal is actionable: a force-inline flag cannot take on a site refused for `no static
        // binding` (megamorphic) or `klass not linked` (a warmup artifact), so proposing that
        // experiment spends a session to learn nothing (Bench.actionableJit)
        val stopped =
            val before = c.control.jit.map(v => v.method -> v).toMap
            c.variant.jit.filter { v =>
                v.alwaysRefused && Bench.actionableJit(v) && before.get(v.method).exists(_.alwaysInlined)
            }
        val stoppedFalsifiers =
            if regressed.isEmpty then Chunk.empty
            else
                stopped.map { v =>
                    Falsifier(
                        Hypothesis.StoppedInlining(v.method),
                        Arm.Variant,
                        Seq(s"-XX:CompileCommandFile=<file with: inline ${commandForm(v.method)}>"),
                        s"if the ${regressed.size} regressed row(s) recover, the refusal is the cause; if they do not, it is not"
                    )
                }

        // a method sitting just over a budget is the case where raising the budget is a clean
        // single-variable experiment. Well over it, the flag would change decisions everywhere
        val budget =
            c.variant.jit.flatMap { v =>
                v.nearBudget match
                    case Maybe.Present(_) if regressed.nonEmpty =>
                        val (name, limit) = if v.bytes <= 70 then ("MaxInlineSize", 35) else ("FreqInlineSize", 325)
                        Chunk(Falsifier(
                            Hypothesis.ExceededBudget(v.method, name, limit),
                            Arm.Variant,
                            Seq(s"-XX:$name=${v.bytes + 1}"),
                            s"raising only the budget this method is over separates 'too big for the budget' from 'the wrong shape'"
                        ))
                    case _ => Chunk.empty
            }

        // the cheap direction first: contradicting the win on the control costs one run and can
        // dissolve the case for the design change entirely
        val winFalsifiers =
            if wins.isEmpty then Chunk.empty
            else
                val after = c.variant.jit.map(v => v.method -> v).toMap
                c.control.jit.filter(v => v.alwaysRefused && after.get(v.method).exists(_.alwaysInlined)).map { v =>
                    Falsifier(
                        Hypothesis.WinFromInlining(v.method),
                        Arm.Control,
                        Seq(s"-XX:CompileCommandFile=<file with: inline ${commandForm(v.method)}>"),
                        "if the control reaches the variant's number, the win is the inlining and not the design"
                    )
                }

        // an allocation change with no timing change, or a large one either way, is the signature
        // this asks about: an enlarged compilation unit defeating escape analysis
        val scalar =
            c.deltas.filter(_.allocDelta.exists(a => Math.abs(a) > 1000.0)).map { d =>
                Falsifier(
                    Hypothesis.RidesScalarReplacement(d.row),
                    Arm.Control,
                    Seq("-XX:-EliminateAllocations"),
                    f"if disabling escape analysis on the control reproduces the variant's ${d.allocDelta.getOrElse(0.0)}%+.0f B/op, " +
                        "the variant destroyed a scalar replacement rather than adding an allocation"
                )
            }

        stoppedFalsifiers ++ budget ++ winFalsifiers ++ scalar
    end falsifiers

    /** `pkg.Class::method` is how the log names a site; `pkg.Class::method` is also what
      * CompileCommand wants, but with the class in its own field. Kept as one function so the two
      * spellings cannot drift apart silently.
      */
    def commandForm(logMethod: String): String =
        logMethod.split("::") match
            case Array(cls, m) => s"$cls::$m"
            case _             => logMethod

    /** Whether the instructed flag actually changed what it was supposed to change, and nothing else.
      *
      * Returns the reason it did not, or empty when it did. Both halves matter: a flag that took at
      * no site establishes nothing, and a flag that moved five other methods' verdicts as well has
      * run a different experiment from the one that was asked for.
      */
    def efficacy(h: Hypothesis, before: Run, after: Run): Maybe[String] =
        h.target match
            case Maybe.Absent => Maybe.empty
            case Maybe.Present(m) =>
                val was = before.jit.find(_.method == m)
                val now = after.jit.find(_.method == m)
                (was, now) match
                    case (None, _) => Maybe(s"$m has no verdict in the baseline leg, so nothing can be said to have moved")
                    case (_, None) => Maybe(s"$m has no verdict in the isolation leg; the method was not compiled there at all")
                    case (Some(b), Some(a)) =>
                        if b.inlined == a.inlined && b.refused == a.refused then
                            Maybe(
                                s"the flag did not take: $m is still ${b.inlined} inlined / ${b.refused} refused. " +
                                    "HotSpot treats an inline command as a hint and still refuses on MaxInlineLevel, node budget, " +
                                    "or a method it cannot compile."
                            )
                        else
                            val others =
                                val map = before.jit.map(v => v.method -> v).toMap
                                after.jit.filter(v => v.method != m && map.get(v.method).exists(b2 => b2.inlined != v.inlined || b2.refused != v.refused))
                            if others.isEmpty then Maybe.empty
                            else
                                Maybe(
                                    s"${others.size} other method(s) changed verdict too (${others.take(3).map(_.method).mkString(", ")}), " +
                                        "so this run changed more than one thing and attributes nothing. Forcing a callee spends the " +
                                        "caller's remaining budget, which is how that happens."
                                )
    end efficacy

    /** Which measured quantity answers a hypothesis.
      *
      * Not a detail: `RidesScalarReplacement` is a claim about bytes per operation, and adjudicating
      * it against wall clock reads the wrong column and answers a question nobody asked. That is what
      * this did on its first outing against real data, where the row it fires on is one whose timing
      * does not resolve at all and whose allocation is exact.
      */
    enum Quantity derives CanEqual:
        case Time, Allocation

        def of(r: Row): Maybe[Double] =
            this match
                case Time       => Maybe(r.score)
                case Allocation => r.allocPerOp

        /** Allocation is nearly exact, and "nearly" is measured rather than assumed.
          *
          * Across four A/A sets of three legs each on identical sources, every row that allocates
          * kilobytes reproduced its `gc.alloc.rate.norm` to the byte. The one row that allocates
          * megabytes did not: `trailingMapsStayLinear` spread 47.8 B on 2.32 MB in one set and 24.2 B
          * in another, so 2.1e-5 relative at worst.
          *
          * A flat one-byte threshold was tried first and it refused the campaign's own escape-analysis
          * result: the isolation landed 23.8 B from its target on 2.56 MB, which is a reproduction to
          * six significant figures, and was reported inconclusive. The band is therefore relative,
          * at 5e-5, a little over twice the worst spread ever observed here, with a one-byte floor so
          * the rows that really are exact stay exact. On the 240,000 B/op effect this has to resolve,
          * that band is 128 bytes, so it costs nothing that matters.
          */
        def resolution(b: Row, i: Row): Double =
            this match
                case Time => Math.max(b.relativeError, i.relativeError) * Math.max(b.score, i.score)
                case Allocation =>
                    val scale = Math.max(b.allocPerOp.getOrElse(0.0), i.allocPerOp.getOrElse(0.0))
                    Math.max(1.0, 5e-5 * scale)

        def unit: String = if this == Time then "" else " B/op"
    end Quantity

    def quantity(h: Hypothesis): Quantity =
        h match
            case Hypothesis.RidesScalarReplacement(_) => Quantity.Allocation
            case _                                    => Quantity.Time

    /** Adjudicates one falsifier from the run it produced.
      *
      * `baseline` is the leg the hypothesis is about, `isolation` the same leg re-run under the flag,
      * and `target` the number the hypothesis predicts the isolation will reach, in whichever quantity
      * the hypothesis is about. The recovery threshold is the legs' own resolution rather than a fixed
      * percentage, because a leg that cannot resolve the effect cannot answer the question either way,
      * and saying so is a result.
      */
    def adjudicate(
        f: Falsifier,
        row: String,
        baseline: Run,
        isolation: Run,
        target: Double
    ): Outcome =
        val q = quantity(f.hypothesis)
        efficacy(f.hypothesis, baseline, isolation) match
            case Maybe.Present(why) => Outcome.Inconclusive(why)
            case Maybe.Absent =>
                (baseline.rows.find(_.name == row), isolation.rows.find(_.name == row)) match
                    // the distance tests below are direction-free, so a thrpt row adjudicates the
                    // same way as an avgt one; what they cannot survive is two legs measured in
                    // different modes or units, where the distance compares nothing with nothing
                    case (Some(bb), Some(ii)) if !bb.comparableWith(ii) =>
                        Outcome.Inconclusive(
                            s"$row was measured as ${bb.mode} in ${bb.unit} on the baseline and as ${ii.mode} in ${ii.unit} on the isolation, " +
                                "so the two numbers are not the same quantity; re-run the isolation in the baseline's mode and unit"
                        )
                    case (Some(bb), Some(ii)) =>
                        (q.of(bb), q.of(ii)) match
                            case (Maybe.Present(b), Maybe.Present(i)) =>
                                val resolution = q.resolution(bb, ii)
                                val gap        = Math.abs(b - target)
                                if gap <= resolution then
                                    Outcome.Inconclusive(
                                        f"the baseline is already within its own resolution of the target (${gap}%.2f against +-${resolution}%.2f${q.unit}), " +
                                            "so this experiment could not have separated them whatever it returned"
                                    )
                                else if Math.abs(i - target) <= resolution then
                                    Outcome.Confirmed(f"$row moved from ${b}%.2f to ${i}%.2f${q.unit}, reaching ${target}%.2f within +-${resolution}%.2f")
                                else if Math.abs(i - b) <= resolution then
                                    Outcome.Refuted(f"$row stayed at ${i}%.2f${q.unit} against ${b}%.2f while the flag demonstrably took; the delta is not this")
                                else
                                    Outcome.Inconclusive(
                                        f"$row moved from ${b}%.2f to ${i}%.2f${q.unit}, which is neither the target ${target}%.2f nor no move at all"
                                    )
                            case _ =>
                                Outcome.Inconclusive(
                                    s"$row carries no ${if q == Quantity.Allocation then "allocation figure" else "score"} on one of the legs, " +
                                        "so the quantity this hypothesis is about was never measured"
                                )
                    case _ => Outcome.Inconclusive(s"$row is missing from one of the two legs")
        end match
    end adjudicate

    /** The report the investigator adds to a comparison. */
    def render(c: Comparison): String =
        val fs = falsifiers(c)
        if fs.isEmpty then ""
        else
            "\n\nNext experiments, each one run of a configuration this harness already issues:\n" +
                fs.zipWithIndex.map((f, i) => s"  ${i + 1}. ${f.show}").mkString("\n") +
                "\n  A falsifier whose flag did not take refutes nothing; the run reports that as inconclusive " +
                "rather than as evidence against the hypothesis."

end Investigate
