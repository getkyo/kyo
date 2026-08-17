import kyo.*

/** Benchmark bracket runner for the proto kernel.
  *
  * Encodes the measurement protocol the kernel skill requires, so a result that violates it cannot be reported. The guards are the point:
  * every failure mode below has happened by hand and each one either corrupted a measurement or wasted a run.
  *
  *   - the bracket runs in a throwaway worktree, never the tree commits come from
  *   - designs are flipped with `git restore --worktree`, which never writes the index (`git checkout <sha> -- <paths>` stages what it
  *     restores, and an interrupted bracket then leaks the comparison design into the next commit)
  *   - each leg verifies design markers before and after measuring, so every number carries proof of which design produced it
  *   - results come from JMH's json, not from grepping console text
  *   - a leg whose row count disagrees with the benchmark class is retried, then fails; it is never reported as clean
  */
object Bench:

    // The machine's observed run-to-run spread. A delta inside it is not a result.
    val DriftBand = 4.0

    case class Row(name: String, mode: String, count: Int, score: Double, error: Double, unit: String)

    case class Marker(name: String, pattern: String, path: String)

    case class Leg(label: String, sha: String, rows: Map[String, Row], markers: Map[String, Int])

    enum Verdict:
        case Faster, Flat, Regressed, BelowResolution

    case class Delta(row: String, control: Row, variant: Row, percent: Double, verdict: Verdict)

    /** Runs a command, failing the computation when it exits non-zero. */
    def exec(cwd: String, command: String*)(using Frame): String < (Sync & Abort[Exception]) =
        Sync.defer {
            val pb      = new ProcessBuilder(command*).directory(new java.io.File(cwd))
            val _       = pb.redirectErrorStream(true)
            val proc    = pb.start()
            val out     = new String(proc.getInputStream.readAllBytes(), "UTF-8")
            val status  = proc.waitFor()
            (out, status)
        }.map { (out, status) =>
            if status == 0 then out
            else Abort.fail(new Exception(s"exit $status from ${command.mkString(" ")}\n$out"))
        }

    /** Working-tree-only restore. Never `checkout`, which would also stage. */
    def restore(worktree: String, sha: String, paths: Seq[String])(using Frame): Unit < (Sync & Abort[Exception]) =
        exec(worktree, (Seq("git", "restore", s"--source=$sha", "--worktree", "--") ++ paths)*).unit

    def readMarkers(worktree: String, markers: Seq[Marker])(using Frame): Map[String, Int] < (Sync & Abort[Exception]) =
        Kyo.foreach(markers) { m =>
            Sync.defer {
                val f = new java.io.File(worktree, m.path)
                val n = if !f.isFile then 0 else scala.io.Source.fromFile(f).getLines().count(_.contains(m.pattern))
                m.name -> n
            }
        }.map(_.toMap)

    /** Counts the benchmarks the class declares, matching only a bare @Benchmark line. Counting substrings also matches @BenchmarkMode and
      * silently inflates the expected row count, which turns a complete run into a reported failure.
      */
    def declaredRows(worktree: String, benchSource: String)(using Frame): Int < (Sync & Abort[Exception]) =
        Sync.defer {
            val f = new java.io.File(worktree, benchSource)
            scala.io.Source.fromFile(f).getLines().count(_.trim == "@Benchmark")
        }

    def parseJmhJson(raw: String): Chunk[Row] =
        val parsed = ujson.read(raw).arr
        Chunk.from(parsed.map { entry =>
            val metric = entry("primaryMetric")
            Row(
                name = entry("benchmark").str.split('.').last,
                mode = entry("mode").str,
                count = metric("rawData").arr.map(_.arr.size).sum,
                score = metric("score").num,
                error = metric("scoreError").num match
                    case d if d.isNaN => 0.0
                    case d            => d,
                unit = metric("scoreUnit").str
            )
        })
    end parseJmhJson

    def runLeg(
        worktree: String,
        label: String,
        sha: String,
        paths: Seq[String],
        markers: Seq[Marker],
        rows: Seq[String],
        forks: Int,
        expected: Int,
        attempts: Int = 3
    )(using Frame): Leg < (Sync & Abort[Exception]) =
        val jsonPath = s"$worktree/target-bench-$label.json"
        val selector = if rows.isEmpty then "kyo.kernel.bench.ProtoKernelBench.*" else rows.mkString(" ")
        val sbtCmd   = s"kyo-kernel2JVM/Jmh/run -f $forks -rf json -rff $jsonPath $selector"

        def attempt(n: Int): Chunk[Row] < (Sync & Abort[Exception]) =
            exec(worktree, "sbt", "--client", sbtCmd).andThen {
                Sync.defer(parseJmhJson(scala.io.Source.fromFile(jsonPath).mkString))
            }.map { parsed =>
                if parsed.size == expected then parsed
                else if n < attempts then
                    // the first invocation after a recompile can match nothing; a short read is a failed run, never a clean one
                    attempt(n + 1)
                else Abort.fail(new Exception(s"leg $label produced ${parsed.size}/$expected rows after $attempts attempts"))
            }

        for
            _      <- restore(worktree, sha, paths)
            before <- readMarkers(worktree, markers)
            parsed <- attempt(1)
            after  <- readMarkers(worktree, markers)
            _ <-
                if before == after then Kyo.unit
                else Abort.fail(new Exception(s"leg $label markers moved during the run: $before -> $after"))
        yield Leg(label, sha, parsed.map(r => r.name -> r).toMap, before)
        end for
    end runLeg

    def compare(control: Leg, variant: Leg): Chunk[Delta] =
        Chunk.from(control.rows.keys.toSeq.flatMap { name =>
            variant.rows.get(name).map { v =>
                val c       = control.rows(name)
                val percent = (v.score - c.score) / c.score * 100
                val verdict =
                    // a score whose error is a large fraction of itself cannot support a percentage
                    if c.error > c.score * 0.5 || c.score <= 0.0 then Verdict.BelowResolution
                    else if percent < -DriftBand then Verdict.Faster
                    else if percent > DriftBand then Verdict.Regressed
                    else Verdict.Flat
                Delta(name, c, v, percent, verdict)
            }
        })
            // a below-resolution delta carries no information, so it must not sort among the
            // real movements: a 5ns row reading +20% otherwise lands where the worst
            // regression belongs and reads as the headline
            .sortBy(d => (d.verdict == Verdict.BelowResolution, d.percent))

    def render(control: Leg, variant: Leg, deltas: Chunk[Delta], forks: Int): String =
        val icon = (v: Verdict) =>
            v match
                case Verdict.Faster          => "🟢"
                case Verdict.Flat            => "⚪"
                case Verdict.Regressed       => "🔴"
                case Verdict.BelowResolution => "🔵"
        val head =
            s"""|Control `${control.sha}` (${control.label}) against variant `${variant.sha}` (${variant.label}).
                |JMH -f $forks. Drift band $DriftBand%, so anything inside it is not a result.
                |Markers control ${control.markers.mkString(" ")} | variant ${variant.markers.mkString(" ")}
                |
                || | row | mode | cnt | control | variant | delta |
                ||---|---|---|---|---|---|---|""".stripMargin
        val body = deltas.map { d =>
            val delta = if d.verdict == Verdict.BelowResolution then "below resolution" else f"${d.percent}%+.1f%%"
            f"| ${icon(d.verdict)} | `${d.row}` | ${d.control.mode} | ${d.variant.count} | " +
                f"${d.control.score}%.2f ± ${d.control.error}%.2f | ${d.variant.score}%.2f ± ${d.variant.error}%.2f | $delta |"
        }.mkString("\n")
        val reds = deltas.filter(_.verdict == Verdict.Regressed)
        val tail =
            if reds.isEmpty then "\nNo row regressed beyond the drift band."
            else
                "\nRegressed, so the work is unfinished until each is diagnosed or ruled on:\n" +
                    reds.map(d => f"  - ${d.row} ${d.percent}%+.1f%%, confirm at -f 3 before acting").mkString("\n")
        s"$head\n$body\n$tail"
    end render

end Bench
