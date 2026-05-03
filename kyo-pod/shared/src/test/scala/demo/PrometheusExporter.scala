package demo

import kyo.*

/** Periodic container stats poller that emits Prometheus text format.
  *
  * Every `interval`, lists all running containers matching a label filter, collects `stats` for each in parallel, translates into
  * Prometheus `# TYPE` / `# HELP` metric families (CPU%, memory bytes, network bytes, block I/O bytes, pids), and writes the whole
  * exposition block to stdout. Each container's labels become metric labels.
  *
  * The demo spawns a handful of workload containers first, then polls 3 times before exiting.
  *
  * Demonstrates: `Container.list` with a label filter, per-container `stats`, `Dict[String, String]` labels flowing into metric labels,
  * `Kyo.foreach` for parallel stats collection, scheduled polling.
  */
object PrometheusExporter extends KyoApp:

    val poolLabel = "role=prometheus-exporter-demo"

    /** A small mix of workload containers: idle alpine, CPU spinner, memory hog. */
    def spawnWorkload(using Frame): Chunk[Container] < (Async & Abort[ContainerException] & Scope) =
        val configs = Chunk(
            "idle"   -> Command("sh", "-c", "sleep 300"),
            "cpu"    -> Command("sh", "-c", "while true; do :; done"),
            "memory" -> Command("sh", "-c", """python3 -c "x=bytearray(30*1024*1024); import time; time.sleep(300)" """)
        )
        Kyo.foreach(configs) { case (role, cmd) =>
            val image =
                if role == "memory" then ContainerImage("python:3.12-alpine")
                else ContainerImage("alpine:3.19")
            Container.init(Container.Config.default.copy(
                image = image,
                labels = Dict("role" -> "prometheus-exporter-demo", "workload" -> role),
                healthCheck = Container.HealthCheck.noop
            ).command(cmd))
        }
    end spawnWorkload

    /** Render a Prometheus text-format exposition for a single container's stats. */
    def renderMetrics(summary: Container.Summary, stats: Container.Stats): String =
        val lbl = promLabels(
            "container_id"   -> summary.id.value.take(12),
            "container_name" -> summary.names.headOption.getOrElse("?")
        ) ++ userLabelString(summary.labels)
        val lines = Chunk(
            s"""kyo_pod_cpu_usage_percent{$lbl} ${stats.cpu.usagePercent}""",
            s"""kyo_pod_memory_usage_bytes{$lbl} ${stats.memory.usage}""",
            s"""kyo_pod_memory_limit_bytes{$lbl} ${stats.memory.limit.getOrElse(0L)}""",
            s"""kyo_pod_memory_usage_percent{$lbl} ${stats.memory.usagePercent}""",
            s"""kyo_pod_block_read_bytes{$lbl} ${stats.blockIo.readBytes}""",
            s"""kyo_pod_block_write_bytes{$lbl} ${stats.blockIo.writeBytes}""",
            s"""kyo_pod_pids{$lbl} ${stats.pids.current}"""
        )
        val netLines = stats.network.toChunk.flatMap { case (iface, n) =>
            val netLbl = lbl + s""",interface="$iface""""
            Seq(
                s"""kyo_pod_network_rx_bytes{$netLbl} ${n.rxBytes}""",
                s"""kyo_pod_network_tx_bytes{$netLbl} ${n.txBytes}"""
            )
        }
        (lines.toSeq ++ netLines).mkString("\n")
    end renderMetrics

    /** Render labels to Prometheus format. Escapes quotes. */
    private def promLabels(pairs: (String, String)*): String =
        pairs.map { case (k, v) => s"""$k="${escape(v)}"""" }.mkString(",")

    private def userLabelString(labels: Dict[String, String]): String =
        val pairs = labels.toChunk.map { case (k, v) => s""",${sanitize(k)}="${escape(v)}"""" }
        pairs.mkString

    private def sanitize(k: String): String = k.map(c => if c.isLetterOrDigit || c == '_' then c else '_')

    private def escape(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    /** Prometheus exposition header. Emitted once per scrape. */
    private val helpHeader =
        """|# HELP kyo_pod_cpu_usage_percent Container CPU usage %.
           |# TYPE kyo_pod_cpu_usage_percent gauge
           |# HELP kyo_pod_memory_usage_bytes Container memory usage in bytes.
           |# TYPE kyo_pod_memory_usage_bytes gauge
           |# HELP kyo_pod_memory_limit_bytes Container memory limit in bytes (0 = unlimited).
           |# TYPE kyo_pod_memory_limit_bytes gauge
           |# HELP kyo_pod_memory_usage_percent Container memory usage % of limit.
           |# TYPE kyo_pod_memory_usage_percent gauge
           |# HELP kyo_pod_block_read_bytes Block-device bytes read.
           |# TYPE kyo_pod_block_read_bytes counter
           |# HELP kyo_pod_block_write_bytes Block-device bytes written.
           |# TYPE kyo_pod_block_write_bytes counter
           |# HELP kyo_pod_pids Current process count.
           |# TYPE kyo_pod_pids gauge
           |# HELP kyo_pod_network_rx_bytes Network bytes received per interface.
           |# TYPE kyo_pod_network_rx_bytes counter
           |# HELP kyo_pod_network_tx_bytes Network bytes transmitted per interface.
           |# TYPE kyo_pod_network_tx_bytes counter""".stripMargin

    def scrape(using Frame): String < (Async & Abort[ContainerException]) =
        Container.list(all = false, filters = Dict("label" -> Chunk(poolLabel))).map { summaries =>
            Kyo.foreach(summaries) { s =>
                s.attach.map(_.stats).map { st => (s, st) }
            }.map { pairs =>
                val body = pairs.toSeq.map { case (s, st) => renderMetrics(s, st) }.mkString("\n")
                s"$helpHeader\n$body\n"
            }
        }

    /** Scrape `rounds` times with `interval` between and print each exposition. */
    def scrapeLoop(rounds: Int, interval: Duration)(using Frame): Unit < (Async & Abort[ContainerException]) =
        def loop(n: Int): Unit < (Async & Abort[ContainerException]) =
            if n <= 0 then Kyo.unit
            else
                scrape.map { text =>
                    Console.printLine(s"=== scrape ${rounds - n + 1}/$rounds ===").andThen {
                        Console.printLine(text).andThen {
                            Async.sleep(interval).andThen(loop(n - 1))
                        }
                    }
                }
        loop(rounds)
    end scrapeLoop

    /** Demo entry point — spawns the workload, sleeps for one scrape interval to let stats stabilise, then takes one scrape and returns its
      * rendered Prometheus-format string so callers can assert on the metric content.
      */
    def demoMain(using Frame): String < (Async & Scope & Abort[ContainerException]) =
        Console.printLine("[exporter] spawning workload...").andThen {
            spawnWorkload.map { _ =>
                Async.sleep(2.seconds).andThen {
                    scrape.map { exposition =>
                        Console.printLine(exposition).andThen(exposition)
                    }
                }
            }
        }
    end demoMain

    run(demoMain.unit)
end PrometheusExporter
