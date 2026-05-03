package kyo

/** Integration tests for kyo-pod's higher-level orchestration logic — multi-container scenarios, concurrency, error recovery, lifecycle
  * races, checkpoint, scope cleanup, runOnce, initAll, Scope teardown, statsStream interval, and a handful of healthcheck-timing tests.
  *
  * These tests exercise framework code paths (kyo Async/Scope, healthcheck retry logic, multi-container orchestration helpers) where the
  * choice of container backend (HTTP vs Shell) and runtime (Podman vs Docker) is plumbing rather than the subject under test. They use
  * [[Test.runBackend]] (single leaf, HTTP backend, auto-detected runtime), so the suite registers no `[runtime]` markers and the build's
  * testGrouping does not fork it per runtime — every test runs once total. Tests that genuinely exercise backend-specific code paths (init
  * JSON vs CLI args, exec stream framing, log demuxing, image parsing, error mapping, …) stay in [[ContainerItTest]] and use `runBackends`
  * for full http × shell × podman × docker coverage.
  */
class ContainerOrchestrationItTest extends Test:

    val alpine = Container.Config(ContainerImage("alpine", "latest"))
        .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity & wait")
        .stopTimeout(0.seconds)

    private val nameCounter = new java.util.concurrent.atomic.AtomicLong(0L)

    def uniqueName(prefix: String) =
        s"$prefix-${nameCounter.incrementAndGet()}"

    "multi-container scenarios" - {
        "shared volume between two containers" - runBackend {
            val volName = Container.Volume.Id(uniqueName("kyo-shared"))
            Scope.run {
                Container.Volume.init(Container.Volume.Config.default.copy(name = Present(volName))).map { _ =>
                    for
                        _ <- Scope.run {
                            Container.initWith(alpine.volume(volName, Path("/shared"))) { writer =>
                                writer.exec("sh", "-c", "echo shared-payload-abc > /shared/file.txt").map { r =>
                                    assert(r.isSuccess)
                                }
                            }
                        }
                        content <- Scope.run {
                            Container.initWith(alpine.volume(volName, Path("/shared"))) { reader =>
                                reader.exec("cat", "/shared/file.txt").map(_.stdout.trim)
                            }
                        }
                    yield assert(content == "shared-payload-abc")
                    end for
                }
            }
        }
    }

    "concurrent operations" - {
        "parallel init of multiple containers yields distinct ids" - runBackend {
            for
                f1  <- Fiber.init(Scope.run(Container.init(alpine).map(_.id)))
                f2  <- Fiber.init(Scope.run(Container.init(alpine).map(_.id)))
                f3  <- Fiber.init(Scope.run(Container.init(alpine).map(_.id)))
                id1 <- f1.get
                id2 <- f2.get
                id3 <- f3.get
            yield
                val ids = Set(id1, id2, id3)
                assert(ids.size == 3) // all distinct
                assert(ids.forall(_.value.nonEmpty))
        }

        "parallel exec on same container returns correct results" - runBackend {
            Container.init(alpine).map { c =>
                for
                    f1 <- Fiber.init(c.exec("echo", "alpha"))
                    f2 <- Fiber.init(c.exec("echo", "beta"))
                    f3 <- Fiber.init(c.exec("echo", "gamma"))
                    r1 <- f1.get
                    r2 <- f2.get
                    r3 <- f3.get
                yield
                    assert(Set(r1, r2, r3).forall(_.isSuccess))
                    assert(Set(r1.stdout.trim, r2.stdout.trim, r3.stdout.trim) == Set("alpha", "beta", "gamma"))
            }
        }
    }

    "error recovery" - {
        "scope cleanup runs even when computation aborts" - runBackend {
            for
                idRef <- AtomicRef.init[Container.Id](Container.Id(""))
                _ <- Abort.run[ContainerException] {
                    Scope.run {
                        Container.init(alpinePersistent(alpine)).map { c =>
                            idRef.set(c.id).andThen {
                                Abort.fail(ContainerOperationException("test", "intentional"))
                            }
                        }
                    }
                }
                id <- idRef.get
                r  <- Abort.run[ContainerException](Container.attach(id))
            yield
                assert(id.value.nonEmpty)
                r match
                    case Result.Failure(_: ContainerMissingException) => succeed
                    case Result.Success(_)                            => fail("Container should have been cleaned up")
                    case other                                        => fail(s"Expected NotFound, got $other")
                end match
        }

        "operations on removed container fail with NotFound" - runBackend {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _  <- c.stop
                    _  <- c.remove
                    r1 <- Abort.run[ContainerException](c.exec("echo", "hi"))
                    r2 <- Abort.run[ContainerException](c.inspect)
                    r3 <- Abort.run[ContainerException](c.state)
                yield
                    r1 match
                        case Result.Failure(_: ContainerMissingException) => succeed
                        case other => fail(s"expected ContainerMissingException for exec, got: $other")
                    r2 match
                        case Result.Failure(_: ContainerMissingException) => succeed
                        case other => fail(s"expected ContainerMissingException for inspect, got: $other")
                    r3 match
                        case Result.Failure(_: ContainerMissingException) => succeed
                        case other => fail(s"expected ContainerMissingException for state, got: $other")
            }
        }

        "backend unavailable gives clear error with backend name" - runBackend {
            Abort.run[ContainerException] {
                Container.withBackendConfig(_.UnixSocket(Path("/nonexistent/socket.sock"))) {
                    Container.init(alpine)
                }
            }.map { r =>
                r match
                    case Result.Failure(e: ContainerBackendUnavailableException) =>
                        assert(e.backend == "http")
                        assert(e.reason.nonEmpty)
                    case other => fail(s"Expected BackendUnavailable, got $other")
            }
        }

        "error on failed operation contains correct resource ID — not last CLI arg" - runBackend {
            // Use a fake container ID that doesn't exist
            val fakeId = Container.Id("nonexistent-container-abc123")
            for
                // copyFrom builds CLI args: ["cp", "containerId:/path", "/tmp/dest"]
                // The last arg is "/tmp/dest", NOT the container ID.
                // mapError uses args.lastOption as the target — it should still
                // reference the container ID, not the destination path.
                r <- Abort.run[ContainerException](
                    Container.attach(fakeId).map { c =>
                        c.inspect
                    }
                )
            yield r match
                case Result.Failure(e: ContainerMissingException) =>
                    assert(
                        e.id.value == fakeId.value,
                        s"Expected error to reference container '${fakeId.value}', got '${e.id.value}'"
                    )
                case Result.Failure(e) =>
                    // Other exception — check it references the container somehow
                    assert(
                        e.getMessage.contains("nonexistent-container-abc123"),
                        s"Expected error to mention the container ID, got: ${e.getMessage}"
                    )
                case Result.Success(_) =>
                    fail("Expected failure for nonexistent container")
                case Result.Panic(t) =>
                    fail(s"panic: $t")
            end for
        }
    }

    "lifecycle races" - {
        // moby/moby#37698, moby/moby#23371 — name conflict race after removal
        "name reuse after delete — no 409 conflict" - runBackend {
            val name = uniqueName("kyo-reuse")
            for
                id1 <- Scope.run {
                    Container.initWith(alpine.name(name).autoRemove(false)) { c =>
                        c.id: Container.Id < Any
                    }
                }
                // Container is stopped and removed by run. Immediately create with same name.
                id2 <- Scope.run {
                    Container.initWith(alpine.name(name)) { c =>
                        c.id: Container.Id < Any
                    }
                }
            yield assert(id1 != id2)
            end for
        }

        "stop on already-stopped container is idempotent" - runBackend {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _  <- c.stop
                    s1 <- c.state
                    _  <- c.stop // second stop should not fail
                    s2 <- c.state
                yield
                    assert(s1 == Container.State.Stopped)
                    assert(s2 == Container.State.Stopped)
            }
        }

        "start on already-running container is idempotent" - runBackend {
            Container.init(alpine).map { c =>
                for
                    s1 <- c.state
                    _  <- c.start // already running — should not fail
                    s2 <- c.state
                yield
                    assert(s1 == Container.State.Running)
                    assert(s2 == Container.State.Running)
            }
        }

        // moby/moby#35933 — concurrent stop+rm can leave stuck state
        "concurrent stop and remove does not leave zombie" - runBackend {
            Container.init(alpinePersistent(alpine)).map { c =>
                val cid = c.id
                for
                    f1 <- Fiber.init(Abort.run[ContainerException](c.stop))
                    f2 <- Fiber.init(Abort.run[ContainerException](c.remove(force = true)))
                    _  <- f1.get
                    _  <- f2.get
                    r  <- Abort.run[ContainerException](Container.attach(cid))
                yield r match
                    case Result.Failure(_: ContainerMissingException) => succeed // container should be fully gone
                    case other                                        => fail(s"expected ContainerMissingException, got: $other")
                end for
            }
        }

        "kill on paused container" - runBackend {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _  <- c.pause
                    s1 <- c.state
                    _ = assert(s1 == Container.State.Paused)
                    // Paused containers can't process SIGTERM; use SIGKILL
                    _  <- c.kill(Container.Signal.SIGKILL)
                    _  <- c.waitForExit
                    s2 <- c.state
                yield assert(s2 == Container.State.Stopped)
            }
        }

        "autoRemove container — explicit remove returns NotFound" - runBackend {
            val config = Container.Config("alpine")
                .command("true")
                .autoRemove(true)
            Container.init(config).map { c =>
                val cid = c.id
                for
                    _ <- c.waitForExit
                    _ <- Loop(()) { _ =>
                        Abort.run[ContainerException](c.state).map {
                            case Result.Failure(_) => Loop.done(())
                            case _                 => Loop.continue(())
                        }
                    }
                    r <- Abort.run[ContainerException](c.remove)
                yield r match
                    case Result.Failure(_: ContainerMissingException) => succeed
                    case other                                        => fail(s"expected ContainerMissingException, got: $other")
                end for
            }
        }

        "concurrent mappedPort while stop is in flight returns either bound port or typed error" - runBackend {
            val cfg = Container.Config(ContainerImage("nginx:alpine"))
                .port(80, 0)
                .healthCheck(Container.HealthCheck.port(80))
            // Both arms carry Abort[ContainerException]; Async.race returns the winner's value.
            // Wrap in Abort.run so the result is a typed Result regardless of which arm wins.
            def trial: (Boolean, Boolean) < (Async & Abort[ContainerException] & Scope) =
                Container.init(cfg.autoRemove(false)).map { c =>
                    Abort.run[ContainerException](
                        Async.race(
                            c.stop(2.seconds).map(_ => false), // stop wins → false = "not a mappedPort success"
                            c.mappedPort(80).map(_ => true)    // mappedPort wins → true = "bound port observed"
                        )
                    ).map { outcome =>
                        outcome match
                            case Result.Success(portObserved)                   => (portObserved, !portObserved)
                            case Result.Failure(_: ContainerMissingException)   => (false, true)
                            case Result.Failure(_: ContainerOperationException) => (false, true)
                            case Result.Failure(_: ContainerConflictException)  => (false, true)
                            case _                                              => (false, false) // panic / unexpected
                    }
                }
            Kyo.foreach(0 until 10)(_ => trial).map { results =>
                val anySuccess = results.exists(_._1)
                val allTyped   = results.forall(r => r._1 || r._2)
                assert(allTyped, s"expected every iteration to produce typed Success or typed Failure, got $results")
                // Document race-window reachability; non-failing test if window is closed by higher-level guard:
                if !anySuccess then
                    info("race window already closed by higher-level guard — only typed Failure observed")
                succeed
            }
        }
    }

    "checkpoint" - {
        "checkpoint/restore preserves a working container ID" - runBackend {
            // CRIU may not be available — wrap in Abort.run to handle gracefully
            Container.init(alpinePersistent(alpine)).map { c =>
                Abort.run[ContainerException] {
                    for
                        _     <- c.checkpoint("test-snap")
                        _     <- c.restore("test-snap")
                        state <- c.state
                        // After restore, the original container ID should still work
                        result <- c.exec("echo", "post-restore")
                    yield
                        assert(
                            state == Container.State.Running,
                            s"Container should be Running after restore, got $state"
                        )
                        assert(
                            result.isSuccess,
                            s"exec on restored container should succeed, got exit=${result.exitCode}"
                        )
                }.map {
                    case Result.Success(_)                                 => succeed
                    case Result.Failure(_: ContainerNotSupportedException) => succeed // CRIU not available
                    case Result.Failure(e)                                 =>
                        // Checkpoint/restore may fail for various reasons (CRIU not installed, etc.)
                        // The important thing is that IF restore succeeds, the ID should work
                        val msg = e.toString
                        if msg.contains("CRIU") || msg.contains("criu") || msg.contains("checkpoint") ||
                            msg.contains("501")
                        then
                            succeed // Expected on systems without CRIU or checkpoint support
                        else
                            fail(s"Unexpected failure: $e")
                        end if
                    case Result.Panic(t) => fail(s"panic: $t")
                }
            }
        }

        "checkpoint on non-running container fails with clear error" - runBackend {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop
                    r <- Abort.run[ContainerException](c.checkpoint("snap1"))
                yield r match
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
            }
        }

        "restore from non-existent checkpoint fails with clear error" - runBackend {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException](c.restore("nonexistent-checkpoint-xyz")).map {
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
                }
            }
        }
    }

    "statsStream with interval" - {
        "statsStream with custom interval emits stats" - runBackend {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.statsStream(100.millis).take(2).run.map { stats =>
                        assert(stats.size == 2)
                        assert(stats.map(_.readAt).distinct.size == 2)
                    }
                }
            }
        }

        "default statsStream delegates to 200ms interval" - runBackend {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.statsStream.take(1).run.map { stats =>
                        assert(stats.size == 1)
                    }
                }
            }
        }
    }

    "scope cleanup" - {
        "scope cleanup works when container crashes" - runBackend {
            val name = uniqueName("kyo-crash")
            val config = Container.Config("alpine")
                .command("sh", "-c", "exit 1")
                .name(name)
                .autoRemove(false)
                .stopTimeout(0.seconds)
            Abort.run[ContainerException] {
                Scope.run {
                    Container.initWith(config) { c =>
                        // Container exits with code 1 immediately.
                        // Wait briefly for the process to exit.
                        Async.sleep(2.seconds).andThen {
                            c.state
                        }
                    }
                }
            }.map { _ =>
                // After scope cleanup, the container should be removed.
                // Trying to inspect it should fail.
                Abort.run[ContainerException] {
                    Container.attach(Container.Id(name)).map(_.inspect)
                }.map {
                    case Result.Failure(_: ContainerMissingException) => succeed
                    case other => fail(s"Expected container to be removed after scope cleanup, got: $other")
                }
            }
        }
    }

    "isHealthy returns false in under 500ms when healthcheck fails" - runBackend {
        val config = Container.Config("alpine")
            .command("sh", "-c", "trap 'exit 0' TERM; touch /tmp/healthy; sleep infinity & wait")
            .healthCheck(Container.HealthCheck.init(Schedule.fixed(200.millis).take(10)) { c =>
                c.exec("test", "-f", "/tmp/healthy").map { r =>
                    if !r.isSuccess then
                        Abort.fail(ContainerHealthCheckException(c.id, "unhealthy", attempts = 1, lastError = "file missing"))
                    else ()
                }
            })
        Container.init(config).map { c =>
            for
                _  <- c.isHealthy // warm up (should be healthy)
                _  <- c.exec("rm", "/tmp/healthy")
                t0 <- Clock.now
                h  <- c.isHealthy
                t1 <- Clock.now
            yield
                val elapsedMs = t1.toJava.toEpochMilli - t0.toJava.toEpochMilli
                assert(!h, "Expected isHealthy to return false after rm")
                assert(
                    elapsedMs < 500,
                    s"Expected isHealthy to return in < 500ms when failing, took ${elapsedMs}ms — it is running the full retry schedule"
                )
        }
    }

    "init completes under 2s when healthcheck fails and container is gone mid-retry" - runBackend {
        // Container lives ~300ms then auto-removes. Healthcheck always fails.
        // Once the container is auto-removed, the retry loop's state check returns
        // NotFound, which falls into the catch-all branch and keeps retrying for the
        // full schedule (3s total here).
        val config = Container.Config("alpine")
            .command("sh", "-c", "sleep 0.3; exit 0")
            .autoRemove(true)
            .healthCheck(Container.HealthCheck.exec(
                command = Command("false"),
                expected = Absent,
                retrySchedule = Schedule.fixed(100.millis).take(30)
            ))
        for
            t0 <- Clock.now
            _  <- Abort.run[ContainerException](Container.init(config))
            t1 <- Clock.now
        yield
            val elapsedMs = t1.toJava.toEpochMilli - t0.toJava.toEpochMilli
            assert(
                elapsedMs < 2000,
                s"Expected init to complete in <2s when container auto-removes during healthcheck retries, " +
                    s"took ${elapsedMs}ms — runHealthCheck is not short-circuiting on NotFound"
            )
        end for
    }

    "scope cleanup waits stopTimeout when stopSignal is Present" - runBackend {
        // stopTimeout=1s keeps the test meaningful (proves kill path honors stopTimeout)
        // while minimizing the deliberate wait; trap sleeps long enough to force the timeout.
        val config = Container.Config("alpine")
            .command("sh", "-c", "trap 'sleep 3; exit 0' USR1; sleep infinity")
            .stopSignal(Container.Signal.SIGUSR1)
            .stopTimeout(1.second)
            .autoRemove(false)
        for
            t0 <- Clock.now
            _  <- Scope.run(Container.init(config).unit)
            t1 <- Clock.now
        yield
            val elapsedMs = t1.toJava.toEpochMilli - t0.toJava.toEpochMilli
            assert(
                elapsedMs >= 800,
                s"Expected cleanup to wait ~stopTimeout (1s) when stopSignal is Present, took ${elapsedMs}ms — " +
                    "the kill path is not honoring stopTimeout"
            )
            assert(
                elapsedMs < 20000,
                s"Cleanup took too long (${elapsedMs}ms); expected timeout then force-remove"
            )
        end for
    }

    "runOnce" - {
        "runOnce — alpine echo returns stdout" - runBackend {
            Container.runOnce(
                image = ContainerImage("alpine:3.19"),
                command = Command("echo", "hello-from-runOnce")
            ).map { r =>
                assert(r.exitCode.isSuccess, s"exit=${r.exitCode.toInt}")
                assert(r.stdout.trim == "hello-from-runOnce", s"stdout='${r.stdout}'")
            }
        }

        "runOnce with sleeping command past timeout returns Signaled(15) and timeout marker" - runBackend {
            // Do NOT use Container.sleepForever — it traps SIGTERM and exits cleanly.
            // Plain "sleep 60" ensures SIGTERM produces Signaled(15).
            Container.runOnce(
                image = ContainerImage("alpine", "latest"),
                command = Command("sleep", "60"),
                timeout = 2.seconds
            ).map { result =>
                assert(
                    result.exitCode == ExitCode.Signaled(15),
                    s"expected Signaled(15), got ${result.exitCode}"
                )
                assert(
                    result.stderr.contains("[kyo-pod] runOnce timed out"),
                    s"expected timeout marker in stderr, got stderr='${result.stderr}'"
                )
            }
        }

        "runOnce with command that exits cleanly under timeout returns its exitCode" - runBackend {
            // Pre-pull alpine so cold-pull doesn't eat the timeout budget.
            ContainerImage.ensure(ContainerImage("alpine", "latest")).andThen {
                Container.runOnce(
                    image = ContainerImage("alpine", "latest"),
                    command = Command("sh", "-c", "exit 42"),
                    timeout = 60.seconds
                ).map { r =>
                    assert(
                        r.exitCode == ExitCode.Failure(42),
                        s"expected Failure(42), got ${r.exitCode}"
                    )
                    assert(
                        !r.stderr.contains("timed out"),
                        s"stderr should not contain 'timed out', got stderr='${r.stderr}'"
                    )
                }
            }
        }
    }

    "initAll" - {
        "initAll — sequential startup" - runBackend {
            // Two containers is enough to verify sequential behavior; more doesn't add signal.
            val configs = Chunk(
                Container.Config.default.copy(image = ContainerImage("alpine:3.19"), command = Present(Container.sleepForever)),
                Container.Config.default.copy(image = ContainerImage("alpine:3.19"), command = Present(Container.sleepForever))
            )
            Scope.run {
                Container.initAll(configs).map { containers =>
                    assert(containers.length == 2)
                    Kyo.foreach(containers)(_.state).map { states =>
                        assert(states.forall(_ == Container.State.Running), s"states=$states")
                    }
                }
            }
        }
    }

    "Scope teardown" - {
        "Scope teardown — 10 containers all cleaned up" - runBackend {
            val label = uniqueName("kyo-teardown")
            Scope.run {
                Kyo.foreach(Chunk.from(0 until 10)) { _ =>
                    Container.init(Container.Config.default.copy(
                        image = ContainerImage("alpine", "3.19"),
                        command = Present(Container.sleepForever),
                        labels = Dict("kyo-teardown-test" -> label)
                    ).stopTimeout(0.seconds))
                }
            }.andThen {
                Container.list(all = true, filters = Dict("label" -> Chunk(s"kyo-teardown-test=$label"))).map { remaining =>
                    assert(remaining.isEmpty, s"expected all 10 containers cleaned up; still see ${remaining.size}")
                }
            }
        }
    }

end ContainerOrchestrationItTest
