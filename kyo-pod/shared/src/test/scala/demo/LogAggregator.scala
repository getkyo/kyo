package demo

import kyo.*

/** Aggregates logs from every running container matching a label filter.
  *
  * Each worker container prints timestamped messages on its own schedule. The aggregator discovers them via `Container.list(filters=...)`,
  * opens a `logStream` per worker, tags each entry with the worker's short name, merges all streams into one, optionally filters by a
  * regex, and prints to stdout.
  *
  * Demonstrates: `Container.list` with label filters, `Container.attach`, `logStream(timestamps=true)`, `Stream.collectAll` for N-way
  * merge, `Stream.filter` for live grep.
  */
object LogAggregator extends KyoApp:

    val demoLabel   = "role=log-aggregator-demo"
    val workerCount = 3

    /** Spin up `n` workers that each log on a schedule. Workers print `[worker-k] ok job-N` (ok-tagged) except the last, which prints
      * `[worker-k] ERROR job-N failed` so the regex filter has something to match. The TERM trap ensures the workers exit promptly when the
      * demo's Scope tears them down — without it, the daemon falls back to SIGKILL after `stopTimeout` and the HTTP `/stop` call appears
      * slow.
      */
    def spawnWorkers(n: Int)(using Frame): Chunk[Container] < (Async & Abort[ContainerException] & Scope) =
        Kyo.foreach(Chunk.from(0 until n)) { idx =>
            val msg =
                if idx == n - 1 then
                    s"""trap 'exit 0' TERM; for i in $$(seq 1 100); do echo "[worker-$idx] ERROR job-$$i failed"; sleep 0.3; done"""
                else
                    s"""trap 'exit 0' TERM; for i in $$(seq 1 100); do echo "[worker-$idx] ok job-$$i"; sleep 0.3; done"""
            val cfg = Container.Config.default.copy(
                image = ContainerImage("alpine:3.19"),
                labels = Dict("role" -> "log-aggregator-demo", "worker-index" -> idx.toString),
                healthCheck = Container.HealthCheck.noop
            ).command("sh", "-c", msg).stopTimeout(0.seconds)
            Container.init(cfg)
        }

    /** Tag a LogEntry with the worker short-name so merged output identifies the source. */
    def labeled(short: String, entry: Container.LogEntry): String =
        val ts = entry.timestamp.fold("")(t => s"$t ")
        val src = entry.source match
            case Container.LogEntry.Source.Stdout => "out"
            case Container.LogEntry.Source.Stderr => "err"
        s"$ts[$short/$src] ${entry.content}"
    end labeled

    /** Given a list of (container, short-name) pairs, build one merged `Stream[String]` of tagged entries. The short name is supplied by
      * the caller — typically derived from a Docker label so the merged output is traceable back to the worker's spawn order, not the
      * (daemon-determined) listing order.
      */
    def aggregate(
        sources: Chunk[(Container, String)],
        grep: Maybe[String] = Absent
    )(using Frame): Stream[String, Async & Abort[ContainerException]] =
        val perWorker: Seq[Stream[String, Async & Abort[ContainerException]]] =
            sources.toSeq.map { case (c, short) =>
                c.logStream(stdout = true, stderr = true, timestamps = true)
                    .map(e => labeled(short, e))
            }
        val merged = Stream.collectAll[String, ContainerException, Any](perWorker)
        grep match
            case Absent => merged
            case Present(p) =>
                val re = scala.util.matching.Regex(p)
                merged.filter(s => re.findFirstIn(s).isDefined)
        end match
    end aggregate

    /** Outcome of [[demoMain]]: how many workers were enumerated via the label filter and how many ERROR-grepped log lines were collected
      * during the 5-second window.
      */
    final case class DemoOutcome(workersFound: Int, errorLines: Chunk[String])

    /** Demo entry point — spawns N workers, lists them by label, streams their merged logs (filtered to ERROR) for 5 seconds, and returns
      * the per-window outcome so callers can assert on what was actually observed.
      */
    def demoMain(using Frame): DemoOutcome < (Async & Scope & Abort[ContainerException]) =
        Console.printLine(s"[aggregator] spawning $workerCount workers...").andThen {
            spawnWorkers(workerCount).map { _ =>
                Console.printLine("[aggregator] listing by label filter...").andThen {
                    Container.list(
                        all = false,
                        filters = Dict("label" -> Chunk(demoLabel))
                    ).map { summaries =>
                        Console.printLine(s"[aggregator] found ${summaries.length} workers via list/filter").andThen {
                            // Reattach by id and pair each container with the short name `w<worker-index>` taken from the Docker label
                            // we set during spawn. This keeps the merged output traceable back to the worker's spawn order regardless
                            // of how the daemon orders the listing.
                            Kyo.foreach(summaries) { s =>
                                Container.attach(s.id).map { c =>
                                    val short = s.labels.get("worker-index").fold("?")(idx => s"w$idx")
                                    (c, short)
                                }
                            }.map { sources =>
                                Console.printLine("[aggregator] streaming logs for 5 seconds (grep=ERROR only)...").andThen {
                                    val tagged = aggregate(sources, grep = Present("ERROR"))
                                    // Accumulate lines as they stream so the result survives a Timeout interrupt —
                                    // `.run` would block until the (infinite) stream ends, then yield nothing back
                                    // when the timeout fires.
                                    AtomicRef.init(Chunk.empty[String]).map { acc =>
                                        val drain = tagged.foreach { line =>
                                            Console.printLine(line).andThen(acc.updateAndGet(_ :+ line).unit)
                                        }
                                        Abort.recover[Timeout] { (_: Timeout) =>
                                            acc.get.map { lines =>
                                                Console.printLine(s"[aggregator] 5s elapsed; tearing down (${lines.length} lines)")
                                                    .andThen(DemoOutcome(summaries.length, lines))
                                            }
                                        } {
                                            Async.timeout(5.seconds)(drain).andThen {
                                                acc.get.map(lines => DemoOutcome(summaries.length, lines))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end demoMain

    run(demoMain.unit)
end LogAggregator
