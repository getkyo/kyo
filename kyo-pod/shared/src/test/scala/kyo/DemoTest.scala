package kyo

/** Smoke check for the kyo-pod demo apps. Each demo's `demoMain` is the same computation that runs from `KyoApp.main`, but each now returns
  * a structured value the test asserts on so a regression that breaks observable behaviour (not just a crash) is caught here.
  *
  * Each leaf is registered per available container runtime so the smoke check exercises the same backend the demo would use in practice.
  */
class DemoTest extends Test:

    override def timeout = 5.minutes

    "CodeSandbox: per-example outcomes match security guarantees" - runRuntimes { _ =>
        demo.CodeSandbox.demoMain.map { results =>
            val byName = results.toMap
            assert(
                byName("hello").exitCode == 0 && byName("hello").stdout.contains("hello from sandbox"),
                s"hello: ${byName("hello")}"
            )
            assert(
                byName("stderr-capture").stdout.contains("to stdout") && byName("stderr-capture").stderr.contains("to stderr"),
                s"stderr-capture: ${byName("stderr-capture")}"
            )
            // 64 MB cgroup limit must kill the 200 MB allocation. Linux SIGKILLs the process → exit 137 (128 + 9).
            assert(byName("memory-blow").exitCode == 137, s"memory-blow: ${byName("memory-blow")}")
            assert(byName("infinite-loop").timedOut, s"infinite-loop: ${byName("infinite-loop")}")
            assert(byName("no-network").stdout.contains("blocked"), s"no-network: ${byName("no-network")}")
        }
    }

    "IntegrationTestScaffold: probes succeed against PG + Redis + cross-DNS" - runRuntimes { _ =>
        demo.IntegrationTestScaffold.demoMain.map { probes =>
            assert(probes.postgres.exitCode.isSuccess, s"postgres SELECT 1 failed: ${probes.postgres}")
            assert(
                probes.redis.exitCode.isSuccess && probes.redis.stdout.trim.equalsIgnoreCase("PONG"),
                s"redis ping failed: ${probes.redis}"
            )
            assert(probes.dns.exitCode.isSuccess, s"pg→redis DNS resolution failed: ${probes.dns}")
        }
    }

    "LogAggregator: enumerates workers and streams ERROR-grepped lines" - runRuntimes { _ =>
        demo.LogAggregator.demoMain.map { outcome =>
            assert(outcome.workersFound > 0, s"expected ≥1 worker enumerated via label filter, got ${outcome.workersFound}")
            assert(
                outcome.errorLines.nonEmpty,
                s"expected ≥1 ERROR-grepped line during the 5s window, got ${outcome.errorLines.length}"
            )
            assert(
                outcome.errorLines.forall(_.contains("ERROR")),
                s"grep filter let through non-ERROR lines: ${outcome.errorLines.find(!_.contains("ERROR"))}"
            )
        }
    }

    "PrometheusExporter: scrape returns valid Prometheus exposition" - runRuntimes { _ =>
        demo.PrometheusExporter.demoMain.map { exposition =>
            assert(exposition.contains("# HELP"), s"missing HELP comment in exposition: ${exposition.take(200)}")
            assert(exposition.contains("# TYPE"), s"missing TYPE comment in exposition")
            assert(exposition.contains("kyo_pod_cpu_usage_percent"), "missing cpu metric")
            assert(exposition.contains("kyo_pod_memory_usage_bytes"), "missing memory metric")
        }
    }

    "ServiceMesh: end-to-end edge → api → cache request returns 'hi'" - runRuntimes { _ =>
        demo.ServiceMesh.demoMain.map { body =>
            assert(body.trim.contains("hi"), s"expected mesh probe to return 'hi', got: '$body'")
        }
    }

end DemoTest
