import Model.*
import kyo.*

/** Parses HotSpot's `-XX:+LogCompilation` XML.
  *
  * `PrintInlining` gives a method's size and verdict and nothing reliable beyond that: its inline tree is interleaved across compiler
  * threads, and its receiver profiles cover almost nothing of the measured code. This log carries what that one cannot.
  *
  *   - deoptimizations, which are invisible everywhere else and are a real performance story: a method whose profile went unstable falls
  *     back to the interpreter and is recompiled
  *   - receiver counts per call site, so monomorphic against megamorphic is measured rather than asserted
  *   - the inline tree as nesting, so "the answer inlines four levels into the drive" is a fact rather than a reading of the source
  *
  * Ids are scoped to a compilation task and reused across tasks, so the symbol table is rebuilt per `<task>`.
  */
object LogCompilation:

    private val Klass    = """<klass id='(\d+)' name='([^']+)'""".r
    private val Method   = """<method id='(\d+)' holder='(\d+)' name='([^']+)'[^>]*bytes='(\d+)'[^>]*iicount='(\d+)'""".r
    private val Call     = """<call method='(\d+)' count='(\d+)'[^>]*?(?:receiver='(\d+)' receiver_count='(\d+)')?/>""".r
    private val Trap     = """<uncommon_trap[^>]*reason='([^']+)' action='([^']+)'""".r
    private val TaskOpen  = """<task compile_id='(\d+)' method='([^']+)'""".r
    private val TaskLevel = """level='(\d+)'""".r
    private val TaskStamp = """stamp='([\d.]+)'""".r

    /** One compilation's worth of facts. */
    case class Task(compileId: Int, method: String, level: Int, stamp: Double, deopts: Chunk[String], calls: Chunk[CallMorphism])

    def parse(raw: String): Chunk[Task] =
        var klasses = Map.empty[String, String]
        var methods = Map.empty[String, (String, String, Int, Long)] // id -> (holder klass id, name, bytes, iicount)
        var current = Maybe.empty[(Int, String, Int, Double)]
        var deopts  = Chunk.empty[String]
        var calls   = Chunk.empty[CallMorphism]
        var out     = Chunk.empty[Task]

        def flush(): Unit =
            current.foreach { (id, m, lvl, st) =>
                out = out.append(Task(id, m, lvl, st, deopts, calls))
            }
            deopts = Chunk.empty
            calls = Chunk.empty

        def name(methodId: String): String =
            methods.get(methodId) match
                case Some((holder, n, _, _)) => s"${klasses.getOrElse(holder, holder)}::$n"
                case None                    => s"method#$methodId"

        raw.linesIterator.foreach { line =>
            TaskOpen.findFirstMatchIn(line).foreach { m =>
                flush()
                // ids are per-task, so the symbol table starts over with every compilation
                klasses = Map.empty
                methods = Map.empty
                val lvl = TaskLevel.findFirstMatchIn(line).map(_.group(1).toInt).getOrElse(0)
                val st  = TaskStamp.findFirstMatchIn(line).map(_.group(1).toDouble).getOrElse(0.0)
                current = Maybe((m.group(1).toInt, m.group(2), lvl, st))
            }
            Klass.findAllMatchIn(line).foreach(m => klasses += m.group(1) -> m.group(2))
            Method.findAllMatchIn(line).foreach(m =>
                methods += m.group(1) -> (m.group(2), m.group(3), m.group(4).toInt, m.group(5).toLong)
            )
            Trap.findAllMatchIn(line).foreach(m => deopts = deopts.append(s"${m.group(1)}/${m.group(2)}"))
            Call.findAllMatchIn(line).foreach { m =>
                val count = m.group(2).toLong
                val recvd = Maybe(m.group(4)).map(_.toLong)
                calls = calls.append(
                    CallMorphism(
                        callee = name(m.group(1)),
                        count = count,
                        receiverCount = recvd.getOrElse(0L),
                        // a receiver taking every call is monomorphic; a site the JIT could not
                        // pin to one receiver reports none at all
                        monomorphic = recvd.exists(_ == count)
                    )
                )
            }
        }
        flush()
        out
    end parse

    /** Everything the compilation log says about what compiling this run cost. */
    def metrics(tasks: Chunk[Task], profiledMs: Double, totalMs: Double): JitMetrics =
        val byMethod = tasks.groupBy(_.method)
        JitMetrics(
            msInWindow = profiledMs,
            msTotal = totalMs,
            tasks = tasks.size,
            c2Tasks = tasks.count(_.level >= 4),
            // a method compiled more than once was recompiled after its profile changed
            recompiled = byMethod.count(_._2.size > 1),
            deopts = tasks.map(_.deopts.size).sum,
            lastCompileAt = if tasks.isEmpty then 0.0 else tasks.map(_.stamp).max
        )
    end metrics

    /** Deoptimization reasons and how often each fired, worst first. */
    def deoptSummary(tasks: Chunk[Task]): Chunk[Deopt] =
        Chunk.from(
            tasks.flatMap(_.deopts).groupBy(identity).toSeq
                .map((reason, hits) => Deopt(reason, hits.size))
                .sortBy(-_.count)
        )

    /** Call sites in the measured code, aggregated by callee, worst-polymorphism first. */
    def morphism(tasks: Chunk[Task], prefix: String = "kyo."): Chunk[CallMorphism] =
        Chunk.from(
            tasks.flatMap(_.calls).filter(_.callee.startsWith(prefix))
                .groupBy(_.callee).toSeq
                .map { (callee, cs) =>
                    CallMorphism(callee, cs.map(_.count).sum, cs.map(_.receiverCount).sum, cs.forall(_.monomorphic))
                }
                .sortBy(c => (c.monomorphic, -c.count))
        )

end LogCompilation
