package kyo

import kyo.ContainerPredef.MySQL
import kyo.ContainerPredef.Postgres

/** Isolated repro for the "Http1ResponseParser: invalid status code 0" race observed in kyo-pod's `HttpContainerBackend` under sustained
  * container churn (originally seen running kyo-sql's full test suite end-to-end).
  *
  * # Symptom
  *
  * Under load (~10+ container start/stop cycles), some iterations log
  *
  * ERROR kyo.logs -- Http1ResponseParser: invalid status code 0, closing
  *
  * and then time out. The same code passes cleanly when only 1–2 containers are exercised.
  *
  * # Hypothesis
  *
  * `kyo-pod` auto-detects HTTP backend (Unix socket → Docker daemon) over the shell backend. Under load:
  *   - Docker daemon's response to `inspect`/`exec` arrives slower than usual.
  *   - When a kyo-pod call is interrupted (via `Async.timeout` or Scope close) mid-response, the underlying HTTP/1 connection is closed
  *     mid-body or returned to the pool with un-drained body bytes.
  *   - The next request on a reused/pooled connection sees stale bytes and the parser interprets them as a new status line, producing
  *     "invalid status code 0".
  *
  * # Run
  *
  * docker volume prune -f && docker container prune -f sbt 'kyo-pod/testOnly kyo.HttpBackendStressTest' 2>&1 | tee /tmp/repro.log
  *
  * Detection (race reproduced if either count > 0): grep -c "invalid status code 0" /tmp/repro.log grep -c "Computation has timed out"
  * /tmp/repro.log
  */
class HttpBackendStressTest extends Test:

    // Each MySQL container needs ~5–7 s to become ready. The sequential variant chains 10 of them, so the
    // total wall-clock budget for that test must accommodate that work plus the per-container init overhead.
    override def timeout = 3.minutes

    "MySQL stress — sequential churn (10 iterations)" - runBackends {
        MySQL.initWith(MySQL.Config.default) { m =>
            m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-seq] iter=0 port=$p"))
        }
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-seq] iter=1 port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-seq] iter=2 port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-seq] iter=3 port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-seq] iter=4 port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-seq] iter=5 port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-seq] iter=6 port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-seq] iter=7 port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-seq] iter=8 port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map { p =>
                    java.lang.System.err.println(s"[repro-seq] iter=9 port=$p")
                    assert(p > 0)
                }
            })
    }

    "MySQL stress — interleaved PG + MySQL (10 iterations)" - runBackends {
        Postgres.initWith(Postgres.Config.default) { pg =>
            pg.container.mappedPort(pg.config.port).map(p => java.lang.System.err.println(s"[repro-mix] iter=0 pg port=$p"))
        }
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-mix] iter=1 mysql port=$p"))
            })
            .andThen(Postgres.initWith(Postgres.Config.default) { pg =>
                pg.container.mappedPort(pg.config.port).map(p => java.lang.System.err.println(s"[repro-mix] iter=2 pg port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-mix] iter=3 mysql port=$p"))
            })
            .andThen(Postgres.initWith(Postgres.Config.default) { pg =>
                pg.container.mappedPort(pg.config.port).map(p => java.lang.System.err.println(s"[repro-mix] iter=4 pg port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-mix] iter=5 mysql port=$p"))
            })
            .andThen(Postgres.initWith(Postgres.Config.default) { pg =>
                pg.container.mappedPort(pg.config.port).map(p => java.lang.System.err.println(s"[repro-mix] iter=6 pg port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map(p => java.lang.System.err.println(s"[repro-mix] iter=7 mysql port=$p"))
            })
            .andThen(Postgres.initWith(Postgres.Config.default) { pg =>
                pg.container.mappedPort(pg.config.port).map(p => java.lang.System.err.println(s"[repro-mix] iter=8 pg port=$p"))
            })
            .andThen(MySQL.initWith(MySQL.Config.default) { m =>
                m.container.mappedPort(m.config.port).map { p =>
                    java.lang.System.err.println(s"[repro-mix] iter=9 mysql port=$p")
                    assert(p > 0)
                }
            })
    }

    "MySQL stress — exec-heavy churn (1 PG container × 24 execs)" - runBackends {
        Postgres.initWith(Postgres.Config.default) { pg =>
            pg.psql("SELECT 1").andThen(pg.psql("SELECT 2")).andThen(pg.psql("SELECT 3")).andThen(pg.psql("SELECT 4"))
                .andThen(pg.psql("SELECT 5")).andThen(pg.psql("SELECT 6")).andThen(pg.psql("SELECT 7")).andThen(pg.psql("SELECT 8"))
                .andThen(pg.psql("SELECT 9")).andThen(pg.psql("SELECT 10")).andThen(pg.psql("SELECT 11")).andThen(pg.psql("SELECT 12"))
                .andThen(pg.psql("SELECT 13")).andThen(pg.psql("SELECT 14")).andThen(pg.psql("SELECT 15")).andThen(pg.psql("SELECT 16"))
                .andThen(pg.psql("SELECT 17")).andThen(pg.psql("SELECT 18")).andThen(pg.psql("SELECT 19")).andThen(pg.psql("SELECT 20"))
                .andThen(pg.psql("SELECT 21")).andThen(pg.psql("SELECT 22")).andThen(pg.psql("SELECT 23"))
                .andThen(pg.psql("SELECT 24"))
                .map { result =>
                    java.lang.System.err.println(s"[repro-exec] last exit=${result.exitCode}")
                    assert(result.exitCode.toInt == 0)
                }
        }
    }

end HttpBackendStressTest
