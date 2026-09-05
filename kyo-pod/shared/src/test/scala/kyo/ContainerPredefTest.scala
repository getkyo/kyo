package kyo

import kyo.*
import kyo.ContainerPredef.MongoDB
import kyo.ContainerPredef.MySQL
import kyo.ContainerPredef.Postgres

class ContainerPredefTest extends BasePodTest:

    "Postgres" - {
        "Config" - {
            "default has expected fields" in {
                val c = Postgres.Config.default
                assert(c.image == ContainerImage("postgres:16-alpine"))
                assert(c.username == "test")
                assert(c.password == "test")
                assert(c.database == "test")
                assert(c.port == 5432)
            }
            "username() builder is immutable" in {
                val base    = Postgres.Config.default
                val updated = base.username("admin")
                assert(base.username == "test")
                assert(updated.username == "admin")
                assert(updated.password == base.password)
                assert(updated.database == base.database)
                assert(updated.port == base.port)
                assert(updated.image == base.image)
            }
            "image() builder updates only image" in {
                val updated = Postgres.Config.default.image(ContainerImage("postgres:15"))
                assert(updated.image == ContainerImage("postgres:15"))
                assert(updated.username == "test")
            }
            "Postgres.Config.default == Postgres.Config()" in {
                assert(Postgres.Config.default == Postgres.Config())
            }
            "readinessBudget defaults to 120s and its builder is immutable" in {
                val base    = Postgres.Config.default
                val updated = base.readinessBudget(30.seconds)
                assert(base.readinessBudget == 120.seconds)
                assert(updated.readinessBudget == 30.seconds)
                assert(updated.port == base.port)
            }
        }
    }

    "MySQL" - {
        "Config" - {
            "default has expected fields" in {
                val c = MySQL.Config.default
                assert(c.image == ContainerImage("mysql:8.0"))
                assert(c.username == "test")
                assert(c.password == "test")
                assert(c.database == "test")
                assert(c.rootPassword == "test")
                assert(c.port == 3306)
            }
            "username() builder is immutable" in {
                val base    = MySQL.Config.default
                val updated = base.username("admin")
                assert(base.username == "test")
                assert(updated.username == "admin")
                assert(updated.password == base.password)
                assert(updated.database == base.database)
            }
            "image() builder updates only image" in {
                val updated = MySQL.Config.default.image(ContainerImage("mysql:8.4"))
                assert(updated.image == ContainerImage("mysql:8.4"))
                assert(updated.username == "test")
            }
            "rootPassword() builder updates rootPassword" in {
                val updated = MySQL.Config.default.rootPassword("supersecret")
                assert(updated.rootPassword == "supersecret")
                assert(updated.password == "test")
            }
            "MySQL.Config.default == MySQL.Config()" in {
                assert(MySQL.Config.default == MySQL.Config())
            }
            "readinessBudget defaults to 120s and its builder is immutable" in {
                val base    = MySQL.Config.default
                val updated = base.readinessBudget(45.seconds)
                assert(base.readinessBudget == 120.seconds)
                assert(updated.readinessBudget == 45.seconds)
                assert(updated.serverArgs == base.serverArgs)
            }
        }
        "buildContainerConfig" - {
            // The command line is `mysqld` followed by the memory caps followed by the caller's own args, and
            // the ORDER is load-bearing twice over. `mysqld` is the executable, so anything that replaces the
            // whole command drops it. The caller's args come last because MySQL takes the last spelling of a
            // repeated flag, which is what lets a fixture raise a cap the defaults lower.
            //
            // This pins the contract because the failure mode is silent: five fixtures were calling
            // `.command(...)` on the RESULT of this method, which replaced the line and dropped all three
            // caps, and the official image's entrypoint hides it by prepending `mysqld` to any argv whose
            // first element starts with a dash. The containers booted and simply took the memory back,
            // roughly 350MB each from performance_schema alone.
            "command line is mysqld, then the default caps, then the caller's serverArgs, in that order" in {
                val cfg  = MySQL.buildContainerConfig(MySQL.Config.default.appendServerArgs("--sql-mode=ANSI"))
                val args = cfg.command.map(_.args)
                assert(
                    args == Present(Chunk("mysqld") ++ MySQL.defaultServerArgs ++ Chunk("--sql-mode=ANSI")),
                    s"command line lost its executable, its caps or its ordering; got $args"
                )
                assert(
                    args.map(_.head) == Present("mysqld"),
                    "the first element is the executable, so anything replacing the command line drops it"
                )
                assert(
                    MySQL.defaultServerArgs.forall(a => args.exists(_.contains(a))),
                    s"every default memory cap must survive into the command line; got $args"
                )
            }
            "default config (non-root user, non-empty password) sets MYSQL_USER/PASSWORD/ROOT_PASSWORD" in {
                val cfg = MySQL.buildContainerConfig(MySQL.Config.default)
                val env = cfg.env
                assert(env.get("MYSQL_DATABASE") == Present("test"))
                assert(env.get("MYSQL_USER") == Present("test"))
                assert(env.get("MYSQL_PASSWORD") == Present("test"))
                assert(env.get("MYSQL_ROOT_PASSWORD") == Present("test"))
                assert(env.get("MYSQL_ALLOW_EMPTY_PASSWORD") == Absent)
            }
            "root user with empty password sets MYSQL_ALLOW_EMPTY_PASSWORD and omits MYSQL_ROOT_PASSWORD" in {
                val cfg = MySQL.buildContainerConfig(MySQL.Config.default.username("root").password(""))
                val env = cfg.env
                assert(env.get("MYSQL_DATABASE") == Present("test"))
                assert(env.get("MYSQL_ALLOW_EMPTY_PASSWORD") == Present("yes"))
                assert(env.get("MYSQL_USER") == Absent)
                assert(env.get("MYSQL_PASSWORD") == Absent)
                assert(
                    env.get("MYSQL_ROOT_PASSWORD") == Absent,
                    "MYSQL_ROOT_PASSWORD must not be set alongside MYSQL_ALLOW_EMPTY_PASSWORD=yes (image rejects the combination)"
                )
            }
            "root user with non-empty password sets MYSQL_ROOT_PASSWORD and omits MYSQL_USER/MYSQL_PASSWORD" in {
                val cfg = MySQL.buildContainerConfig(MySQL.Config.default.username("root").password("secret"))
                val env = cfg.env
                assert(env.get("MYSQL_DATABASE") == Present("test"))
                assert(env.get("MYSQL_ROOT_PASSWORD") == Present("test"))
                assert(env.get("MYSQL_ALLOW_EMPTY_PASSWORD") == Absent)
                assert(env.get("MYSQL_USER") == Absent)
                assert(env.get("MYSQL_PASSWORD") == Absent)
            }
        }
    }

    "MongoDB" - {
        "Config" - {
            "default has expected fields" in {
                val c = MongoDB.Config.default
                assert(c.image == ContainerImage("mongo:7"))
                assert(c.database == "test")
                assert(c.port == 27017)
            }
            "image() builder is immutable" in {
                val base    = MongoDB.Config.default
                val updated = base.image(ContainerImage("mongo:6"))
                assert(base.image == ContainerImage("mongo:7"))
                assert(updated.image == ContainerImage("mongo:6"))
                assert(updated.database == base.database)
                assert(updated.port == base.port)
            }
            "database() builder updates only database" in {
                val updated = MongoDB.Config.default.database("mydb")
                assert(updated.database == "mydb")
                assert(updated.image == MongoDB.Config.default.image)
            }
            "MongoDB.Config.default == MongoDB.Config()" in {
                assert(MongoDB.Config.default == MongoDB.Config())
            }
            "readinessBudget defaults to 120s and its builder is immutable" in {
                val base    = MongoDB.Config.default
                val updated = base.readinessBudget(90.seconds)
                assert(base.readinessBudget == 120.seconds)
                assert(updated.readinessBudget == 90.seconds)
                assert(updated.port == base.port)
            }
        }
    }

    "retryableExecFailure" - {

        // A daemon that failed the exec is a different signal from a probe that ran and reported the service down.
        // Only the first is worth another attempt, and only while the container is still alive to answer it.
        "a running container with budget left is retried" in {
            assert(ContainerPredef.retryableExecFailure(Result.Success(Container.State.Running), 1))
        }

        // A container that died mid-boot fails every later exec for the same reason, so retrying only delays the report.
        "a container that is no longer running is terminal" in {
            assert(ContainerPredef.retryableExecFailure(Result.Success(Container.State.Dead), 1) == false)
            assert(ContainerPredef.retryableExecFailure(Result.Success(Container.State.Stopped), 1) == false)
            assert(ContainerPredef.retryableExecFailure(Result.Success(Container.State.Created), 1) == false)
        }

        // The bound is what keeps a health check from becoming a poll; every host exec leaves a conmon behind for minutes.
        "a running container with no budget left is terminal" in {
            assert(ContainerPredef.retryableExecFailure(Result.Success(Container.State.Running), 0) == false)
        }
    }

    // The leaves above pin the POLICY in isolation; these pin that the loop obeys it. Driving `readinessAttempt` with scripted outcomes
    // counts the attempts it actually makes, which asserting the predicate alone cannot: a loop that never consulted it would still leave
    // those leaves green.
    "readinessAttempt" - {

        val ok     = Container.ExecResult(ExitCode(0), "", "")
        val notOk  = Container.ExecResult(ExitCode(1), "", "service down")
        val daemon = ContainerBackendException("exec failed")

        /** Runs the loop over a scripted sequence of probe outcomes, reporting how many attempts were consumed and which terminal branch
          * (if any) the loop took.
          */
        def drive(
            probes: Seq[Result[ContainerException, Container.ExecResult]],
            state: Result[ContainerException, Container.State],
            retries: Int = 1
        )(using Frame) =
            for
                attempts <- AtomicInt.init(0)
                down     <- AtomicInt.init(0)
                failed   <- AtomicInt.init(0)
                _ <- Abort.run[ContainerException](ContainerPredef.readinessAttempt(
                    () => attempts.getAndIncrement.map(i => probes(math.min(i, probes.size - 1))),
                    () => state,
                    _ => down.incrementAndGet.unit,
                    _ => failed.incrementAndGet.unit,
                    retries
                ))
                a <- attempts.get
                d <- down.get
                f <- failed.get
            yield (a, d, f)

        "a probe that passes runs once and reports nothing" in {
            drive(Seq(Result.succeed(ok)), Result.Success(Container.State.Running)).map { case (a, d, f) =>
                assert((a, d, f) == (1, 0, 0), s"expected one attempt and no failure report; got attempts=$a down=$d failed=$f")
            }
        }

        // The retry the policy allows must actually be taken: a daemon-failed exec against a live container gets a second attempt.
        "a daemon-failed exec against a running container is retried once, then succeeds" in {
            drive(Seq(Result.fail(daemon), Result.succeed(ok)), Result.Success(Container.State.Running)).map { case (a, d, f) =>
                assert((a, d, f) == (2, 0, 0), s"expected the retry to be taken and to succeed; got attempts=$a down=$d failed=$f")
            }
        }

        // And it must stop there rather than becoming a poll.
        "a daemon-failed exec is retried at most once, then reported" in {
            drive(Seq(Result.fail(daemon)), Result.Success(Container.State.Running)).map { case (a, d, f) =>
                assert(
                    (a, d, f) == (2, 0, 1),
                    s"expected exactly two attempts then an exec-failed report; got attempts=$a down=$d failed=$f"
                )
            }
        }

        // A container that died mid-boot fails every later exec for the same reason, so the loop must not spend the retry on it.
        "a daemon-failed exec against a dead container is not retried" in {
            drive(Seq(Result.fail(daemon)), Result.Success(Container.State.Dead)).map { case (a, d, f) =>
                assert((a, d, f) == (1, 0, 1), s"expected no retry for a dead container; got attempts=$a down=$d failed=$f")
            }
        }

        // The service's own verdict is terminal: a probe that RAN and said "down" must never be retried, or the loop would mask exactly
        // the failure the health check exists to report.
        "a probe that ran and reported the service down is never retried" in {
            drive(Seq(Result.succeed(notOk)), Result.Success(Container.State.Running)).map { case (a, d, f) =>
                assert((a, d, f) == (1, 1, 0), s"expected one attempt and a probe-down report; got attempts=$a down=$d failed=$f")
            }
        }

        // Asking the daemon what happened is itself code that can be defective. That defect belongs to the state query, so reporting it
        // as the exec's failure would blame the service under test for a bug in the machinery inspecting it.
        "a panic from the state query propagates instead of being reported as an exec failure" in {
            val boom = new RuntimeException("state query defect")
            for
                attempts <- AtomicInt.init(0)
                failed   <- AtomicInt.init(0)
                outcome <- Abort.run[ContainerException](ContainerPredef.readinessAttempt(
                    () => attempts.getAndIncrement.andThen(Result.fail(daemon)),
                    () => Result.panic(boom),
                    _ => Kyo.unit,
                    _ => failed.incrementAndGet.unit,
                    1
                ))
                a <- attempts.get
                f <- failed.get
            yield
                assert(
                    outcome match
                        case Result.Panic(t) => t eq boom
                        case _               => false
                    ,
                    s"expected the state-query panic to propagate unchanged; got $outcome"
                )
                assert((a, f) == (1, 0), s"expected one attempt and no exec-failed report; got attempts=$a failed=$f")
            end for
        }
    }

    "readinessScript" - {
        "embeds the configured budget as the loop deadline" in {
            // The generated shell loop computes its end as `date +%s` plus the budget in seconds, so a fixture's
            // readinessBudget must appear verbatim in the script. This pins the flow-through that the
            // Container.HealthCheck closure otherwise hides (it exposes only check/schedule, not the script).
            val script = ContainerPredef.readinessScript(Chunk("psql"), 30.seconds)
            assert(script.contains("+30)"), s"budget seconds must reach the loop deadline; got: $script")
        }
        "carries a Config's readinessBudget (default 120s)" in {
            val budget = Postgres.Config.default.readinessBudget
            assert(ContainerPredef.readinessScript(Chunk("psql"), budget).contains("+120)"))
        }
    }

    // =========================================================================
    // URL formatters — pure string formatting, no runtime needed
    // =========================================================================

    "Postgres.formatJdbcUrl" - {
        "default shape" in {
            assert(Postgres.formatJdbcUrl("127.0.0.1", 5432, "test") == "jdbc:postgresql://127.0.0.1:5432/test")
        }
        "preserves custom database name" in {
            assert(Postgres.formatJdbcUrl("127.0.0.1", 49154, "mydb") == "jdbc:postgresql://127.0.0.1:49154/mydb")
        }
        "ephemeral host port (e.g. 0-allocated mapping) matches expected pattern" in {
            val url = Postgres.formatJdbcUrl("127.0.0.1", 65432, "test")
            val pat = """^jdbc:postgresql://127\.0\.0\.1:\d+/test$""".r
            assert(pat.matches(url), s"URL didn't match expected pattern: $url")
        }
    }

    "MySQL.formatJdbcUrl" - {
        "default shape" in {
            assert(MySQL.formatJdbcUrl("127.0.0.1", 3306, "test") == "jdbc:mysql://127.0.0.1:3306/test")
        }
        "preserves custom database name" in {
            assert(MySQL.formatJdbcUrl("127.0.0.1", 49155, "mydb") == "jdbc:mysql://127.0.0.1:49155/mydb")
        }
        "ephemeral host port matches expected pattern" in {
            val url = MySQL.formatJdbcUrl("127.0.0.1", 33060, "test")
            val pat = """^jdbc:mysql://127\.0\.0\.1:\d+/test$""".r
            assert(pat.matches(url), s"URL didn't match expected pattern: $url")
        }
    }

    "MongoDB.formatConnectionString" - {
        "default shape (no database segment)" in {
            assert(MongoDB.formatConnectionString("127.0.0.1", 27017) == "mongodb://127.0.0.1:27017")
        }
    }

    "MongoDB.formatUrl" - {
        "default shape with database" in {
            assert(MongoDB.formatUrl("127.0.0.1", 27017, "test") == "mongodb://127.0.0.1:27017/test")
        }
        "preserves custom database name" in {
            assert(MongoDB.formatUrl("127.0.0.1", 49156, "mydb") == "mongodb://127.0.0.1:49156/mydb")
        }
        "ephemeral host port matches expected pattern" in {
            val url = MongoDB.formatUrl("127.0.0.1", 27018, "test")
            val pat = """^mongodb://127\.0\.0\.1:\d+/test$""".r
            assert(pat.matches(url), s"URL didn't match expected pattern: $url")
        }
    }

end ContainerPredefTest
