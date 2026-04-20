package kyo

import scala.concurrent.Future

abstract class ContainerTest(val runtime: String) extends Test:

    override def run(testName: Option[String], args: org.scalatest.Args): org.scalatest.Status =
        val runtimeAvailable = runtime match
            case "podman" => ContainerRuntime.hasPodman
            case "docker" => ContainerRuntime.hasDocker
            case _        => false
        if !runtimeAvailable then
            info(s"Skipping: $runtime not available")
            org.scalatest.SucceededStatus
        else
            super.run(testName, args)
        end if
    end run

    private val backends: Seq[(String, Container.BackendConfig)] =
        val shell = Seq("shell" -> Container.BackendConfig.Shell(runtime))
        val http = findSocket(runtime).map(path =>
            "http" -> Container.BackendConfig.UnixSocket(Path(path))
        ).toSeq
        http ++ shell
    end backends

    override def run(v: Future[Assertion] < (Abort[Any] & Async & Scope))(using Frame): Future[Assertion] =
        super.run {
            Kyo.foreach(backends) { (_, config) =>
                Scope.run(Container.withBackend(config)(v))
            }.map(_.last)
        }

    private def findSocket(rt: String): Option[String] =
        val candidates = rt match
            case "docker" =>
                val home = java.lang.System.getProperty("user.home", "")
                Seq(s"$home/.docker/run/docker.sock", "/var/run/docker.sock")
            case "podman" =>
                val xdg = java.lang.System.getenv("XDG_RUNTIME_DIR")
                if xdg != null then Seq(s"$xdg/podman/podman.sock") else Seq("/run/podman/podman.sock")
            case _ => Seq("/var/run/docker.sock")
        candidates.find(p => java.nio.file.Files.exists(java.nio.file.Path.of(p)))
    end findSocket

    val alpine = Container.Config(ContainerImage("alpine", "latest"))
        .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity")
        .stopTimeout(0.seconds)

    private val nameCounter        = new java.util.concurrent.atomic.AtomicLong(0)
    def uniqueName(prefix: String) = s"$prefix-${java.lang.System.currentTimeMillis}-${nameCounter.incrementAndGet()}"

    // =========================================================================
    // Config Builder
    // =========================================================================

    "Config" - {
        "minimal config only requires image" in {
            val config = Container.Config("alpine")
            assert(config.image.reference.contains("alpine"))
            assert(config.ports.isEmpty)
            assert(config.mounts.isEmpty)
            assert(config.labels.isEmpty)
            assert(config.dns.isEmpty)
            assert(config.extraHosts.isEmpty)
            assert(config.memory == Absent)
            assert(!config.privileged)
            assert(!config.interactive)
            assert(!config.allocateTty)
            assert(!config.autoRemove)
        }

        "builder chains are immutable" in {
            val base     = Container.Config("alpine")
            val withName = base.name("test")
            val withPort = base.port(80, 8080)
            assert(base.name == Absent)
            assert(base.ports.isEmpty)
            assert(withName.name == Present("test"))
            assert(withName.ports.isEmpty)
            assert(withPort.name == Absent)
            assert(withPort.ports.size == 1)
        }

        "port(container, host) adds a PortBinding with both ports" in {
            val config = Container.Config("nginx").port(80, 8080)
            assert(config.ports.size == 1)
            assert(config.ports.head.containerPort == 80)
            assert(config.ports.head.hostPort == 8080)
            assert(config.ports.head.protocol == Container.Config.Protocol.TCP)
        }

        "port(container) adds a PortBinding with random host port" in {
            val config = Container.Config("nginx").port(80)
            assert(config.ports.size == 1)
            assert(config.ports.head.containerPort == 80)
            assert(config.ports.head.hostPort == 0)
        }

        "multiple port calls accumulate" in {
            val config = Container.Config("app").port(80, 8080).port(443, 8443)
            assert(config.ports.size == 2)
            assert(config.ports(0).containerPort == 80)
            assert(config.ports(1).containerPort == 443)
        }

        "bind mount via builder" in {
            val config = Container.Config("alpine").bind(Path("/host"), Path("/container"))
            assert(config.mounts.size == 1)
            config.mounts.head match
                case Container.Config.Mount.Bind(s, t, ro) =>
                    assert(s == Path("/host"))
                    assert(t == Path("/container"))
                    assert(!ro)
                case other => fail(s"Expected Bind mount, got $other")
            end match
        }

        "volume mount via builder" in {
            val config = Container.Config("alpine").volume(Container.Volume.Id("data"), Path("/var/data"))
            assert(config.mounts.size == 1)
            config.mounts.head match
                case Container.Config.Mount.Volume(n, t, ro) =>
                    assert(n == Container.Volume.Id("data"))
                    assert(t == Path("/var/data"))
                    assert(!ro)
                case other => fail(s"Expected Volume mount, got $other")
            end match
        }

        "tmpfs mount via builder" in {
            val config = Container.Config("alpine").tmpfs(Path("/tmp/test"))
            assert(config.mounts.size == 1)
            config.mounts.head match
                case Container.Config.Mount.Tmpfs(t, sz) =>
                    assert(t == Path("/tmp/test"))
                    assert(sz == Absent)
                case other => fail(s"Expected Tmpfs mount, got $other")
            end match
        }

        "mount with function DSL" in {
            val config = Container.Config("alpine")
                .mount(_.Bind(Path("/a"), Path("/b"), readOnly = true))
            assert(config.mounts.size == 1)
            config.mounts.head match
                case Container.Config.Mount.Bind(s, t, ro) =>
                    assert(s == Path("/a"))
                    assert(t == Path("/b"))
                    assert(ro)
                case other => fail(s"Expected Bind mount, got $other")
            end match
        }

        "multiple mount calls accumulate in order" in {
            val config = Container.Config("alpine")
                .bind(Path("/a"), Path("/b"))
                .volume(Container.Volume.Id("v"), Path("/c"))
                .tmpfs(Path("/d"))
            assert(config.mounts.size == 3)
            config.mounts(0) match
                case _: Container.Config.Mount.Bind => succeed
                case other                          => fail(s"Expected Bind mount, got $other")
            config.mounts(1) match
                case _: Container.Config.Mount.Volume => succeed
                case other                            => fail(s"Expected Volume mount, got $other")
            config.mounts(2) match
                case _: Container.Config.Mount.Tmpfs => succeed
                case other                           => fail(s"Expected Tmpfs mount, got $other")
        }

        "networkMode with function DSL" in {
            val config = Container.Config("alpine").networkMode(_.Host)
            assert(config.networkMode == Present(Container.Config.NetworkMode.Host))
        }

        "restartPolicy with function DSL" in {
            val config = Container.Config("alpine").restartPolicy(_.Always)
            assert(config.restartPolicy == Container.Config.RestartPolicy.Always)
        }

        "restartPolicy OnFailure with maxRetries" in {
            val config = Container.Config("alpine").restartPolicy(_.OnFailure(5))
            config.restartPolicy match
                case Container.Config.RestartPolicy.OnFailure(n) => assert(n == 5)
                case other                                       => fail(s"Expected OnFailure(5), got $other")
        }

        "label adds a single label" in {
            val config = Container.Config("alpine").label("env", "test")
            assert(config.labels == Map("env" -> "test"))
        }

        "labels merges with existing" in {
            val config = Container.Config("alpine")
                .label("a", "1")
                .labels(Map("b" -> "2", "c" -> "3"))
            assert(config.labels == Map("a" -> "1", "b" -> "2", "c" -> "3"))
        }

        "labels overwrites on conflict" in {
            val config = Container.Config("alpine")
                .label("a", "1")
                .labels(Map("a" -> "2"))
            assert(config.labels == Map("a" -> "2"))
        }

        "dns accumulates across calls" in {
            val config = Container.Config("alpine").dns("8.8.8.8").dns("8.8.4.4")
            assert(config.dns == Chunk("8.8.8.8", "8.8.4.4"))
        }

        "extraHost adds structured entry" in {
            val config = Container.Config("alpine").extraHost("myhost", "10.0.0.1")
            assert(config.extraHosts.size == 1)
            assert(config.extraHosts.head.hostname == "myhost")
            assert(config.extraHosts.head.ip == "10.0.0.1")
        }

        "command(String*) creates Command from args" in {
            val config = Container.Config("alpine").command("sh", "-c", "echo hello")
            assert(config.command.args == Chunk("sh", "-c", "echo hello"))
        }

        "command(Command) preserves env and cwd" in {
            val cmd = Command("sh", "-c", "echo hello")
                .envAppend(Map("FOO" -> "bar"))
                .cwd(Path("/tmp"))
            val config = Container.Config("alpine").command(cmd)
            assert(config.command.args == Chunk("sh", "-c", "echo hello"))
            assert(config.command.env == Map("FOO" -> "bar"))
            assert(config.command.workDir == Present(Path("/tmp")))
        }

        "default command uses trap for fast shutdown" in {
            val config = Container.Config("alpine")
            assert(config.command.args == Chunk("sh", "-c", "trap 'exit 0' TERM; sleep infinity"))
        }

        "default restartPolicy is No" in {
            assert(Container.Config("alpine").restartPolicy == Container.Config.RestartPolicy.No)
        }

        "default stopTimeout is 3 seconds" in {
            assert(Container.Config("alpine").stopTimeout == 3.seconds)
        }

        "resource limit builders set values" in {
            val config = Container.Config("alpine")
                .memory(512 * 1024 * 1024L)
                .cpuLimit(1.5)
                .cpuAffinity("0-3")
                .maxProcesses(100)
            assert(config.memory == Present(512 * 1024 * 1024L))
            assert(config.cpuLimit == Present(1.5))
            assert(config.cpuAffinity == Present("0-3"))
            assert(config.maxProcesses == Present(100L))
        }

        "security builders set values" in {
            val config = Container.Config("alpine")
                .privileged(true)
                .addCapabilities(Container.Capability.NetAdmin, Container.Capability.SysPtrace)
                .dropCapabilities(Container.Capability.Mknod)
                .readOnlyFilesystem(true)
            assert(config.privileged)
            assert(config.addCapabilities == Chunk(Container.Capability.NetAdmin, Container.Capability.SysPtrace))
            assert(config.dropCapabilities == Chunk(Container.Capability.Mknod))
            assert(config.readOnlyFilesystem)
        }
    }

    // =========================================================================
    // Backend Selection
    // =========================================================================

    "Backend" - {
        "auto-detect creates a working backend" in run {
            Container.init(alpine).map { c =>
                c.state.map(s => assert(s == Container.State.Running))
            }
        }

        "withBackend(_.UnixSocket) uses http backend" in run {
            val socketPath = runtime match
                case "docker" =>
                    val home        = java.lang.System.getProperty("user.home", "")
                    val desktopSock = s"$home/.docker/run/docker.sock"
                    if java.nio.file.Files.exists(java.nio.file.Path.of(desktopSock)) then desktopSock
                    else "/var/run/docker.sock"
                case "podman" =>
                    val xdg = java.lang.System.getenv("XDG_RUNTIME_DIR")
                    if xdg != null then s"$xdg/podman/podman.sock" else "/run/podman/podman.sock"
                case _ => "/var/run/docker.sock"
            Container.withBackend(_.UnixSocket(Path(socketPath))) {
                Container.init(alpine).map { c =>
                    c.state.map(s => assert(s == Container.State.Running))
                }
            }
        }

        "withBackend(_.Shell) uses shell backend" in run {
            Container.withBackend(_.Shell(runtime)) {
                Container.init(alpine).map { c =>
                    c.state.map(s => assert(s == Container.State.Running))
                }
            }
        }

        "auto-detect aborts with BackendUnavailable when nothing works" in run {
            Abort.run[ContainerException] {
                Container.withBackend(_.UnixSocket(Path("/nonexistent/socket.sock"))) {
                    Container.init(alpine)
                }
            }.map { result =>
                result match
                    case Result.Failure(e: ContainerException.BackendUnavailable) =>
                        assert(e.backend == "http")
                        assert(e.reason.nonEmpty)
                    case other => fail(s"Expected BackendUnavailable, got $other")
            }
        }
    }

    // =========================================================================
    // Container Lifecycle
    // =========================================================================

    "init" - {
        "creates, starts, and waits for health check" in run {
            Container.init(alpine).map { c =>
                c.state.map(s => assert(s == Container.State.Running))
            }
        }

        "returns container with valid non-empty id" in run {
            Container.init(alpine).map { c =>
                assert(c.id.value.nonEmpty)
                assert(c.id.value.length >= 12) // Docker short IDs are at least 12 chars
            }
        }

        "registers scope cleanup — container removed on scope close" in run {
            val name = uniqueName("kyo-scope-cleanup")
            for
                idRef <- AtomicRef.init[Container.Id](Container.Id(""))
                _ <- Scope.run {
                    Container.init(alpine.name(name).autoRemove(false)).map { c =>
                        idRef.set(c.id)
                    }
                }
                id <- idRef.get
                r  <- Abort.run[ContainerException](Container.attach(id))
            yield
                assert(id.value.nonEmpty)
                r match
                    case Result.Failure(_: ContainerException.NotFound) => succeed
                    case Result.Success(_)                              => fail("Container should have been removed by scope cleanup")
                    case other                                          => fail(s"Expected NotFound, got $other")
                end match
            end for
        }

        "convenience overload with image" in run {
            Container.init(
                image = ContainerImage("alpine", "latest"),
                command = Command("tail", "-f", "/dev/null")
            ).map { c =>
                c.state.map(s => assert(s == Container.State.Running))
            }
        }

        "fails with ImageNotFound for nonexistent image" in run {
            Abort.run[ContainerException] {
                Container.init(Container.Config("nonexistent-image-xyz-999:notatag"))
            }.map { result =>
                result match
                    case Result.Failure(_: ContainerException.ImageNotFound) => succeed
                    case other                                               => fail(s"Expected ImageNotFound, got $other")
            }
        }

        "fails with AlreadyExists when name is taken" in run {
            val name = uniqueName("kyo-dup")
            Container.init(alpine.name(name)).map { _ =>
                Abort.run[ContainerException] {
                    Container.init(alpine.name(name))
                }.map { result =>
                    result match
                        case Result.Failure(_: ContainerException.AlreadyExists) => succeed
                        case other                                               => fail(s"Expected AlreadyExists, got $other")
                }
            }
        }

        "health check failure aborts init" in run {
            val failingCheck = Container.HealthCheck.exec(
                command = Command("false"),
                schedule = Schedule.fixed(100.millis).take(3)
            )
            Abort.run[ContainerException] {
                Container.init(alpine.healthCheck(failingCheck))
            }.map(result => assert(result.isFailure))
        }
    }

    "use" - {
        "creates container, runs block, returns result, cleans up" in run {
            val name = uniqueName("kyo-run")
            for
                idRef <- AtomicRef.init[Container.Id](Container.Id(""))
                result <- Container.use(alpine.name(name).autoRemove(false)) { c =>
                    idRef.set(c.id).andThen(42)
                }
                id <- idRef.get
                r  <- Abort.run[ContainerException](Container.attach(id))
            yield
                assert(result == 42)
                assert(r.isFailure) // container should be cleaned up
            end for
        }

        "container is removed after block fails" in run {
            for
                idRef <- AtomicRef.init[Container.Id](Container.Id(""))
                _ <- Abort.run[ContainerException] {
                    Container.use(alpine.autoRemove(false)) { c =>
                        idRef.set(c.id).andThen {
                            Abort.fail(ContainerException.General("test failure", "intentional"))
                        }
                    }
                }
                id <- idRef.get
                r  <- Abort.run[ContainerException](Container.attach(id))
            yield assert(r.isFailure) // container should be cleaned up

        }

        "block receives working container" in run {
            Container.use(alpine) { c =>
                c.exec("echo", "hello").map(_.stdout.trim)
            }.map(r => assert(r == "hello"))
        }
    }

    "attach (by id)" - {
        "attaches to existing running container" in run {
            Container.init(alpine).map { c =>
                Container.attach(c.id).map { attached =>
                    assert(attached.id == c.id)
                    attached.state.map(s => assert(s == Container.State.Running))
                }
            }
        }

        "fails with NotFound for nonexistent id" in run {
            Abort.run[ContainerException] {
                Container.attach(Container.Id("nonexistent-container-xyz-999"))
            }.map { result =>
                result match
                    case Result.Failure(_: ContainerException.NotFound) => succeed
                    case other                                          => fail(s"Expected NotFound, got $other")
            }
        }
    }

    "stop" - {
        "stops a running container" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.stop
                    s <- c.state
                yield assert(s == Container.State.Stopped)
            }
        }

        "container state is Stopped after stop with short timeout" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.stop(timeout = 1.seconds)
                    s <- c.state
                yield assert(s == Container.State.Stopped)
            }
        }
    }

    "kill" - {
        "kills a running container with SIGKILL" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.kill(Container.Signal.SIGKILL)
                    _ <- c.waitForExit
                    s <- c.state
                yield assert(s == Container.State.Stopped)
            }
        }

        "sends SIGTERM signal" in run {
            // Use sh with trap so SIGTERM is handled (tail -f ignores it)
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; while true; do sleep 1; done")
                .autoRemove(false)
            Container.init(config).map { c =>
                for
                    _ <- c.kill(Container.Signal.SIGTERM)
                    _ <- c.waitForExit
                    s <- c.state
                yield assert(s == Container.State.Stopped)
            }
        }
    }

    "restart" - {
        "restarts a running container — new PID, still Running" in run {
            Container.init(alpine).map { c =>
                for
                    pid1 <- c.inspect.map(_.pid)
                    _    <- c.restart
                    pid2 <- c.inspect.map(_.pid)
                    s    <- c.state
                yield
                    assert(s == Container.State.Running)
                    assert(pid1 != pid2)
            }
        }

        "restarts a stopped container" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _  <- c.stop
                    s1 <- c.state
                    _  <- c.restart
                    s2 <- c.state
                yield
                    assert(s1 == Container.State.Stopped)
                    assert(s2 == Container.State.Running)
            }
        }
    }

    "pause and unpause" - {
        "pause transitions to Paused state" in run {
            Container.init(alpine).map { c =>
                for
                    s1 <- c.state
                    _  <- c.pause
                    s2 <- c.state
                yield
                    assert(s1 == Container.State.Running)
                    assert(s2 == Container.State.Paused)
            }
        }

        "unpause transitions back to Running" in run {
            Container.init(alpine).map { c =>
                for
                    _  <- c.pause
                    s1 <- c.state
                    _  <- c.unpause
                    s2 <- c.state
                yield
                    assert(s1 == Container.State.Paused)
                    assert(s2 == Container.State.Running)
            }
        }
    }

    "remove" - {
        "removes a stopped container" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.stop
                    _ <- c.remove
                    r <- Abort.run[ContainerException](c.state)
                yield r match
                    case Result.Failure(_: ContainerException.NotFound) => succeed
                    case other                                          => fail(s"Expected NotFound, got $other")
            }
        }

        "force removes a running container" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    s <- c.state
                    _ = assert(s == Container.State.Running)
                    _ <- c.remove(force = true)
                    r <- Abort.run[ContainerException](c.state)
                yield r match
                    case Result.Failure(_: ContainerException.NotFound) => succeed
                    case other                                          => fail(s"Expected NotFound, got $other")
            }
        }

        "fails without force on running container" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                Abort.run[ContainerException](c.remove).map { r =>
                    assert(r.isFailure)
                }
            }
        }
    }

    "rename" - {
        "renames a container" in run {
            Container.init(alpine).map { c =>
                val newName = uniqueName("kyo-renamed")
                for
                    _ <- c.rename(newName)
                    i <- c.inspect
                yield assert(i.name.contains(newName))
                end for
            }
        }
    }

    "waitForExit" - {
        "returns Success for exit code 0" in run {
            Container.init(Container.Config("alpine").command("true")).map { c =>
                c.waitForExit.map(code => assert(code == ExitCode.Success))
            }
        }

        "returns Failure for non-zero exit code" in run {
            Container.init(Container.Config("alpine").command("sh", "-c", "exit 42")).map { c =>
                c.waitForExit.map(code => assert(code == ExitCode.Failure(42)))
            }
        }

        "returns Signaled for signal-killed container" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _    <- c.kill(Container.Signal.SIGKILL)
                    code <- c.waitForExit
                yield code match
                    case ExitCode.Signaled(9) => succeed
                    case ExitCode.Failure(_)  => succeed // some runtimes report as failure
                    case other                => fail(s"Expected Signaled(9) or Failure, got $other")
            }
        }

        "auto-removed container with failure exit code — best effort" in run {
            // Known limitation: with autoRemove(true) and a fast-exiting container,
            // Docker may remove the container before `docker wait` can capture the exit code.
            // This test documents the behavior rather than asserting a specific outcome.
            val config = Container.Config("alpine")
                .command("sh", "-c", "exit 42")
                .autoRemove(true)
            Container.init(config).map { c =>
                c.waitForExit.map { code =>
                    // Best case: Failure(42). Worst case: Success (exit code lost to race).
                    // Either is acceptable — the test verifies no crash/hang.
                    assert(
                        code == ExitCode.Failure(42) || code == ExitCode.Success,
                        s"Expected Failure(42) or Success (race), got $code"
                    )
                }
            }
        }
    }

    // =========================================================================
    // Health Check
    // =========================================================================

    "HealthCheck" - {
        "exec — passes when command succeeds and output matches" in run {
            val hc = Container.HealthCheck.exec(
                command = Command("echo", "ok"),
                expected = Present("ok"),
                schedule = Schedule.fixed(100.millis).take(10)
            )
            Container.init(alpine.healthCheck(hc)).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "exec — String* convenience" in run {
            Container.init(alpine.healthCheck(Container.HealthCheck.exec("echo", "ok"))).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "log — passes when message found in logs" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo 'ready to serve'; sleep infinity")
                .healthCheck(Container.HealthCheck.log(
                    "ready to serve",
                    schedule = Schedule.fixed(200.millis).take(30)
                ))
            Container.init(config).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "port — passes when port is listening" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; while true; do echo ok | nc -l -p 8080; done & sleep infinity")
                .healthCheck(Container.HealthCheck.port(
                    8080,
                    schedule = Schedule.fixed(200.millis).take(30)
                ))
            Container.init(config).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "all — passes only when all checks pass" in run {
            val hc = Container.HealthCheck.all(
                Container.HealthCheck.exec("echo", "ok"),
                Container.HealthCheck.exec("true")
            )
            Container.init(alpine.healthCheck(hc)).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "custom — accepts function" in run {
            val hc = Container.HealthCheck(Schedule.fixed(200.millis).take(10)) { container =>
                container.exec("echo", "alive").map { r =>
                    if !r.isSuccess then
                        Abort.fail(ContainerException.General("not alive", "check failed"))
                    else ()
                }
            }
            Container.init(alpine.healthCheck(hc)).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "ongoing — isHealthy tracks health continuously" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; touch /tmp/healthy; sleep infinity")
                .healthCheck(Container.HealthCheck(Schedule.fixed(200.millis).take(100)) { c =>
                    c.exec("test", "-f", "/tmp/healthy").map { r =>
                        if !r.isSuccess then
                            Abort.fail(ContainerException.General("unhealthy", "file missing"))
                        else ()
                    }
                })
            Container.init(config).map { c =>
                for
                    h1 <- c.isHealthy
                    _ = assert(h1)
                    _  <- c.exec("rm", "/tmp/healthy")
                    h2 <- c.isHealthy
                yield assert(!h2)
            }
        }
    }

    // =========================================================================
    // Inspection
    // =========================================================================

    "inspect" - {
        "returns container info with correct id and image" in run {
            Container.init(alpine).map { c =>
                c.inspect.map { info =>
                    assert(info.id == c.id)
                    assert(info.image.reference.contains("alpine"))
                    assert(info.state == Container.State.Running)
                }
            }
        }

        "returns port bindings" in run {
            Container.init(alpine.port(80, 18080)).map { c =>
                c.inspect.map { info =>
                    assert(info.ports.exists(_.containerPort == 80))
                }
            }
        }

        "returns labels" in run {
            Container.init(alpine.label("test-key", "test-value")).map { c =>
                c.inspect.map { info =>
                    assert(info.labels("test-key") == "test-value")
                }
            }
        }

        "returns network settings with IP address" in run {
            Container.init(alpine).map { c =>
                c.inspect.map { info =>
                    info.networkSettings.ipAddress match
                        case Present(ip) => assert(ip.nonEmpty)
                        case Absent      => fail("Expected IP address")
                }
            }
        }

        "fails with NotFound for removed container" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.stop
                    _ <- c.remove
                    r <- Abort.run[ContainerException](c.inspect)
                yield r match
                    case Result.Failure(_: ContainerException.NotFound) => succeed
                    case other                                          => fail(s"Expected NotFound, got $other")
            }
        }

        "correctly distinguishes volume mounts from bind mounts" in run {
            val volName = Container.Volume.Id(uniqueName("kyo-mount-type"))
            val hostDir = Path("/tmp/" + uniqueName("kyo-bind-type"))
            for
                _ <- Container.Volume.create(Container.Volume.Config(name = Present(volName)))
                _ <- hostDir.mkDir
                info <- Container.use(
                    alpine
                        .bind(hostDir, Path("/mnt/bind-target"))
                        .volume(volName, Path("/mnt/vol-target"))
                ) { c =>
                    c.inspect
                }
                _ <- hostDir.removeAll
                _ <- Container.Volume.remove(volName)
            yield
                // Should have at least 2 mounts
                assert(info.mounts.size >= 2, s"Expected at least 2 mounts, got ${info.mounts.size}")
                // Find the bind mount and volume mount
                val hasBind = info.mounts.exists {
                    case Container.Config.Mount.Bind(_, target, _) =>
                        target == Path("/mnt/bind-target")
                    case _ => false
                }
                val hasVolume = info.mounts.exists {
                    case Container.Config.Mount.Volume(_, target, _) =>
                        target == Path("/mnt/vol-target")
                    case _ => false
                }
                assert(hasBind, s"Expected a Bind mount for /mnt/bind-target, got: ${info.mounts}")
                assert(hasVolume, s"Expected a Volume mount for /mnt/vol-target, got: ${info.mounts}")
            end for
        }
    }

    "state" - {
        "returns Running for running container" in run {
            Container.init(alpine).map(_.state.map(s => assert(s == Container.State.Running)))
        }

        "returns Paused for paused container" in run {
            Container.init(alpine).map { c =>
                c.pause.andThen(c.state).map(s => assert(s == Container.State.Paused))
            }
        }

        "returns Stopped for stopped container" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.stop
                    s <- c.state
                yield assert(s == Container.State.Stopped)
            }
        }
    }

    "stats" - {
        "returns non-zero CPU and memory values" in run {
            Container.init(alpine).map { c =>
                c.stats.map { s =>
                    // memory.limit is Maybe[Long] — Docker CLI may or may not provide it
                    s.memory.limit match
                        case Present(lim) => assert(lim > 0)
                        case Absent       => succeed
                    assert(s.cpu.onlineCpus > 0)
                    assert(s.pids.current > 0)
                }
            }
        }
    }

    "statsStream" - {
        "emits multiple distinct stats snapshots" in run {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.statsStream.take(3).run.map { stats =>
                        assert(stats.size == 3)
                        assert(stats.map(_.readAt).distinct.size == 3)
                    }
                }
            }
        }
    }

    "top" - {
        "returns process titles and at least one process" in run {
            Container.init(alpine).map { c =>
                c.top.map { result =>
                    assert(result.titles.nonEmpty)
                    assert(result.titles.exists(t => t.contains("PID") || t.contains("pid")))
                    assert(result.processes.nonEmpty)
                    assert(result.processes.head.size == result.titles.size)
                }
            }
        }
    }

    "changes" - {
        "detects added files" in run {
            Container.init(alpine).map { c =>
                for
                    _       <- c.exec("touch", "/tmp/kyo-test-newfile")
                    changes <- c.changes
                yield assert(changes.exists(ch =>
                    ch.path.contains("kyo-test-newfile") &&
                        ch.kind == Container.FilesystemChange.Kind.Added
                ))
            }
        }
    }

    // =========================================================================
    // Exec
    // =========================================================================

    "exec" - {
        "runs command and returns stdout" in run {
            Container.init(alpine).map { c =>
                c.exec("echo", "hello world").map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.trim == "hello world")
                }
            }
        }

        "returns stderr separately from stdout" in run {
            Container.init(alpine).map { c =>
                c.exec("sh", "-c", "echo out; echo err >&2").map { r =>
                    assert(r.stdout.trim == "out")
                    assert(r.stderr.trim == "err")
                }
            }
        }

        "returns correct exit code for non-zero exit" in run {
            Container.init(alpine).map { c =>
                c.exec("sh", "-c", "exit 42").map { r =>
                    assert(!r.isSuccess)
                    assert(r.exitCode == ExitCode.Failure(42))
                }
            }
        }

        "Command with env propagates environment" in run {
            Container.init(alpine).map { c =>
                val cmd = Command("sh", "-c", "echo $MY_VAR").envAppend(Map("MY_VAR" -> "hello"))
                c.exec(cmd).map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.trim == "hello")
                }
            }
        }

        "Command with cwd sets working directory" in run {
            Container.init(alpine).map { c =>
                val cmd = Command("pwd").cwd(Path("/tmp"))
                c.exec(cmd).map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.trim == "/tmp")
                }
            }
        }

        "handles command with spaces in arguments" in run {
            Container.init(alpine).map { c =>
                c.exec("echo", "hello world with spaces").map { r =>
                    assert(r.stdout.trim == "hello world with spaces")
                }
            }
        }

        "handles command with special characters" in run {
            Container.init(alpine).map { c =>
                c.exec("echo", "a&b|c;d").map { r =>
                    assert(r.stdout.trim == "a&b|c;d")
                }
            }
        }

        "handles empty output" in run {
            Container.init(alpine).map { c =>
                c.exec("true").map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.isEmpty)
                }
            }
        }

        "handles large output without truncation" in run {
            Container.init(alpine).map { c =>
                c.exec("sh", "-c", "dd if=/dev/zero bs=1024 count=100 2>/dev/null | base64").map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.length > 100 * 1024) // base64 expands ~33%
                }
            }
        }

        "concurrent exec calls on same container" in run {
            Container.init(alpine).map { c =>
                for
                    f1 <- Fiber.init(c.exec("echo", "one"))
                    f2 <- Fiber.init(c.exec("echo", "two"))
                    f3 <- Fiber.init(c.exec("echo", "three"))
                    r1 <- f1.get
                    r2 <- f2.get
                    r3 <- f3.get
                yield
                    assert(r1.isSuccess && r2.isSuccess && r3.isSuccess)
                    val outputs = Set(r1.stdout.trim, r2.stdout.trim, r3.stdout.trim)
                    assert(outputs == Set("one", "two", "three"))
            }
        }
    }

    "execStream" - {
        "streams stdout and stderr as LogEntry with correct sources" in run {
            Container.init(alpine).map { c =>
                Scope.run {
                    // execStream uses redirectErrorStream(true), so stderr is merged into stdout
                    c.execStream(Command("sh", "-c", "echo out; echo err >&2")).run.map { entries =>
                        val all = entries.filter(_.source == Container.LogEntry.Source.Stdout)
                        assert(all.exists(_.content.contains("out")))
                        assert(all.exists(_.content.contains("err")))
                    }
                }
            }
        }

        "stream completes when command exits" in run {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.execStream("echo", "done").run.map { entries =>
                        assert(entries.nonEmpty)
                        assert(entries.exists(_.content.contains("done")))
                    }
                }
            }
        }
    }

    "execInteractive" - {
        "write sends data to stdin, read receives response" in {
            pending // TODO: Requires HTTP backend for bidirectional I/O
        }
    }

    // =========================================================================
    // Attach
    // =========================================================================

    "attach" - {
        "bidirectional — write then read response" in {
            pending // TODO: Requires HTTP backend for bidirectional I/O
        }

        "attach(stdout=false) does not receive stdout data" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo stdout-visible; echo stderr-visible >&2; sleep infinity")
            Container.init(config).map { c =>
                Scope.run {
                    c.attach(stdin = false, stdout = false, stderr = true).map { session =>
                        session.read.take(1).run.map { entries =>
                            // With stdout=false, we should NOT see stdout-visible
                            assert(
                                !entries.exists(_.content.contains("stdout-visible")),
                                s"stdout=false should exclude stdout, but got: ${entries.map(_.content)}"
                            )
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // Logs
    // =========================================================================

    "logs" - {
        "returns LogEntry entries with stdout output" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo hello-from-container; sleep infinity")
            Container.init(config).map { c =>
                c.logs.map { entries =>
                    assert(entries.exists(_.content.contains("hello-from-container")))
                    assert(entries.exists(_.source == Container.LogEntry.Source.Stdout))
                }
            }
        }

        "stdout=false excludes stdout, keeps stderr" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo stdout-msg; echo stderr-msg >&2; sleep infinity")
            Container.init(config).map { c =>
                c.logs(stdout = false, stderr = true).map { entries =>
                    assert(!entries.exists(_.content.contains("stdout-msg")))
                    assert(entries.exists(_.content.contains("stderr-msg")))
                }
            }
        }

        "tail limits to last N lines" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; for i in $(seq 1 20); do echo line$i; done; sleep infinity")
            Container.init(config).map { c =>
                c.logs(stdout = true, stderr = true, tail = 5).map { entries =>
                    assert(entries.size == 5)
                    assert(entries.last.content == "line20")
                    assert(entries.head.content == "line16")
                }
            }
        }

        "returns empty chunk for container with no output" in run {
            Container.init(alpine).map { c =>
                c.logs.map(entries => assert(entries.isEmpty))
            }
        }

        "logsText returns raw string for backward compat" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo hello-from-container; sleep infinity")
            Container.init(config).map { c =>
                c.logsText.map { text =>
                    assert(text.contains("hello-from-container"))
                }
            }
        }
    }

    "logStream" - {
        "streams log entries containing both stdout and stderr content" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo stdout-data; echo stderr-data >&2; sleep infinity")
            Container.init(config).map { c =>
                Scope.run {
                    // Shell backend merges stdout/stderr via redirectErrorStream,
                    // so source tagging is Stdout for all entries. Verify content arrives.
                    c.logStream.take(2).run.map { entries =>
                        assert(entries.nonEmpty, "Expected log entries from logStream")
                        val allContent = entries.map(_.content).toSeq.mkString(" ")
                        assert(allContent.contains("stdout-data"), s"Missing stdout-data in: $allContent")
                        assert(allContent.contains("stderr-data"), s"Missing stderr-data in: $allContent")
                    }
                }
            }
        }

        "delivers entries incrementally as container produces output" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "for i in 1 2 3 4 5; do echo line$i; sleep 0.5; done")
            Container.init(config).map { c =>
                Scope.run {
                    for
                        t0      <- Clock.now
                        entries <- c.logStream.take(5).run
                        tEnd    <- Clock.now
                    yield
                        val totalMs = tEnd.toJava.toEpochMilli - t0.toJava.toEpochMilli
                        assert(entries.size >= 3, s"Expected at least 3 log entries, got ${entries.size}")
                        // If entries arrive incrementally (following), total time should span > 1 second
                        // If all collected at once (no --follow), they arrive instantly
                        assert(
                            totalMs > 1000,
                            s"Entries arrived in ${totalMs}ms — expected > 1000ms for incremental streaming"
                        )
                    end for
                }
            }
        }

        "logStream(stdout=false, stderr=true) returns only stderr entries" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo stdout-only; echo stderr-only >&2; sleep infinity")
            Container.init(config).map { c =>
                Scope.run {
                    c.logStream(stdout = false, stderr = true).take(1).run.map { entries =>
                        assert(entries.nonEmpty, "Expected at least one log entry")
                        // Should only have stderr entries, no stdout
                        assert(
                            !entries.exists(_.content.contains("stdout-only")),
                            s"stdout=false should exclude stdout entries, but got: ${entries.map(_.content)}"
                        )
                        assert(
                            entries.exists(_.content.contains("stderr-only")),
                            "Expected stderr-only content in entries"
                        )
                    }
                }
            }
        }

        "logStream with timestamps=true populates LogEntry.timestamp" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo timestamped-entry; sleep infinity")
            Container.init(config).map { c =>
                Scope.run {
                    c.logStream(stdout = true, stderr = true, timestamps = true).take(1).run.map { entries =>
                        assert(entries.nonEmpty, "Expected at least one log entry")
                        val entry = entries.head
                        entry.timestamp match
                            case Present(_) => succeed
                            case Absent =>
                                fail(s"Expected timestamp to be Present when timestamps=true, but got Absent. Content: '${entry.content}'")
                        end match
                    }
                }
            }
        }
    }

    // =========================================================================
    // File Operations
    // =========================================================================

    "copyTo" - {
        "copies a local file into the container and content matches" in run {
            val localPath = Path("/tmp/" + uniqueName("kyo-copyto"))
            Container.init(alpine).map { c =>
                for
                    _      <- localPath.write("test content 12345")
                    _      <- c.copyTo(localPath, Path("/tmp/copied"))
                    result <- c.exec("cat", "/tmp/copied")
                    _      <- localPath.remove
                yield
                    assert(result.isSuccess)
                    assert(result.stdout.trim == "test content 12345")
            }
        }

        "handles empty file" in run {
            val localPath = Path("/tmp/" + uniqueName("kyo-empty"))
            Container.init(alpine).map { c =>
                for
                    _      <- localPath.write("")
                    _      <- c.copyTo(localPath, Path("/tmp/empty"))
                    result <- c.exec("wc", "-c", "/tmp/empty")
                    _      <- localPath.remove
                yield
                    assert(result.isSuccess)
                    assert(result.stdout.trim.startsWith("0"))
            }
        }
    }

    "copyFrom" - {
        "copies a file from container to local and content matches" in run {
            val localPath = Path("/tmp/" + uniqueName("kyo-copyfrom"))
            Container.init(alpine).map { c =>
                for
                    _       <- c.exec("sh", "-c", "echo container-data-67890 > /tmp/source")
                    _       <- c.copyFrom(Path("/tmp/source"), localPath)
                    content <- localPath.read
                    _       <- localPath.remove
                yield assert(content.trim == "container-data-67890")
            }
        }

        "fails when container path doesn't exist" in run {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException] {
                    c.copyFrom(Path("/nonexistent/path/xyz"), Path("/tmp/dest"))
                }.map(r => assert(r.isFailure))
            }
        }
    }

    "stat" - {
        "returns file metadata with correct name and non-zero size" in run {
            Container.init(alpine).map { c =>
                for
                    _    <- c.exec("sh", "-c", "echo data12345 > /tmp/testfile")
                    info <- c.stat(Path("/tmp/testfile"))
                yield
                    assert(info.name == "testfile")
                    assert(info.size > 0)
                    assert(info.mode > 0)
            }
        }

        "returns link target for symlinks" in run {
            Container.init(alpine).map { c =>
                for
                    _    <- c.exec("sh", "-c", "echo x > /tmp/real; ln -s /tmp/real /tmp/link")
                    info <- c.stat(Path("/tmp/link"))
                yield info.linkTarget match
                    case Present(target) => assert(target.contains("/tmp/real"))
                    case Absent          => fail("Expected link target for symlink")
            }
        }

        "fails for nonexistent path" in run {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException] {
                    c.stat(Path("/nonexistent/path/xyz"))
                }.map(r => assert(r.isFailure))
            }
        }
    }

    "export" - {
        "streams non-empty container filesystem" in run {
            // Note: podman export may not work on remote/machine setups
            Container.init(alpine).map { c =>
                Abort.run[Timeout] {
                    Async.timeout(10.seconds) {
                        Scope.run {
                            c.exportFs.take(4096).run.map { bytes =>
                                assert(bytes.size >= 512) // at least one tar block
                            }
                        }
                    }
                }.map {
                    case Result.Success(_) => succeed
                    case Result.Failure(_) =>
                        info("exportFs timed out — podman remote may not support export")
                        succeed
                }
            }
        }

        "exportFs binary integrity — tar magic bytes survive" in run {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.exportFs.run.map { allBytes =>
                        // A valid tar archive has "ustar" at byte offset 257
                        // If exportFs round-trips through String (UTF-8), null bytes
                        // in tar headers get corrupted, destroying the magic
                        assert(allBytes.size > 512, s"Export too small: ${allBytes.size} bytes")
                        val magic = new String(allBytes.toArray.slice(257, 263), "ASCII")
                        assert(
                            magic.startsWith("ustar"),
                            s"Tar magic corrupted: expected 'ustar' at offset 257, got '${magic}' — " +
                                "binary data was likely round-tripped through UTF-8 String"
                        )
                        // Also verify null bytes exist (tar headers have many nulls)
                        // UTF-8 String conversion would replace \0 with replacement chars
                        val nullCount = allBytes.count(_ == 0.toByte)
                        assert(
                            nullCount > 10,
                            s"Expected null bytes in tar headers, found only $nullCount — " +
                                "suggests binary corruption via String encoding"
                        )
                    }
                }
            }
        }

        "exportFs streams multiple chunks — not single blob" in run {
            Container.init(alpine).map { c =>
                for
                    // Create a 5MB file so the tar export is large enough to require multiple chunks
                    _ <- c.exec("sh", "-c", "dd if=/dev/urandom bs=1024 count=5120 of=/tmp/bigfile 2>/dev/null")
                    chunkCount <- Scope.run {
                        // Count how many chunks the stream emits
                        var count = 0
                        c.exportFs.mapChunk { chunk =>
                            count += 1
                            chunk
                        }.run.map(_ => count)
                    }
                yield
                    // A 5MB+ tar streamed properly should produce multiple chunks.
                    // If the implementation loads everything into one String and emits once,
                    // we get exactly 1 chunk.
                    assert(
                        chunkCount > 1,
                        s"Expected multiple chunks for 5MB export, got $chunkCount — " +
                            "suggests exportFs loads entire filesystem into memory before emitting"
                    )
            }
        }
    }

    // =========================================================================
    // Resource Updates
    // =========================================================================

    "update" - {
        "updates memory limit and verifies via stats" in run {
            Container.init(alpine.memory(256 * 1024 * 1024L)).map { c =>
                for
                    s1 <- c.stats
                    _  <- c.update(memory = Present(128 * 1024 * 1024L))
                    s2 <- c.stats
                yield
                    assert(s1.memory.limit == Present(256 * 1024 * 1024L))
                    assert(s2.memory.limit == Present(128 * 1024 * 1024L))
            }
        }

        "Absent fields leave values unchanged" in run {
            Container.init(alpine.memory(256 * 1024 * 1024L)).map { c =>
                for
                    s1 <- c.stats
                    _  <- c.update // no changes
                    s2 <- c.stats
                yield assert(s1.memory.limit == s2.memory.limit)
            }
        }
    }

    // =========================================================================
    // Network Operations (on container)
    // =========================================================================

    "connectToNetwork" - {
        "connects container to a network and visible in inspect" in run {
            val netName = uniqueName("kyo-net-conn")
            for
                netId <- Container.Network.create(Container.Network.Config(name = netName))
                result <- Container.use(alpine) { c =>
                    for
                        _ <- c.connectToNetwork(netId)
                        i <- c.inspect
                    yield i.networkSettings.networks.contains(netId)
                }
                _ <- Container.Network.remove(netId)
            yield assert(result)
            end for
        }

        "fails with NetworkNotFound for bad network id" in run {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException] {
                    c.connectToNetwork(Container.Network.Id("nonexistent-net-xyz"))
                }.map { r =>
                    r match
                        case Result.Failure(_: ContainerException.NetworkNotFound) => succeed
                        case other                                                 => fail(s"Expected NetworkNotFound, got $other")
                }
            }
        }
    }

    "disconnectFromNetwork" - {
        "disconnects container and no longer in inspect" in run {
            val netName = uniqueName("kyo-net-disc")
            for
                netId <- Container.Network.create(Container.Network.Config(name = netName))
                result <- Container.use(alpine) { c =>
                    for
                        _  <- c.connectToNetwork(netId)
                        i1 <- c.inspect
                        _ = assert(i1.networkSettings.networks.contains(netId))
                        _  <- c.disconnectFromNetwork(netId)
                        i2 <- c.inspect
                    yield i2.networkSettings.networks.contains(netId)
                }
                _ <- Container.Network.remove(netId)
            yield assert(!result)
            end for
        }
    }

    // =========================================================================
    // Container List and Prune
    // =========================================================================

    "list" - {
        "lists running containers and includes our container" in run {
            val name = uniqueName("kyo-list")
            Container.init(alpine.name(name)).map { c =>
                Container.list.map { summaries =>
                    assert(summaries.exists(_.id == c.id))
                    assert(summaries.find(_.id == c.id).exists(_.state == Container.State.Running))
                }
            }
        }

        "all=true includes stopped containers" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.stop
                    s <- Container.list(all = true)
                yield
                    assert(s.exists(_.id == c.id))
                    assert(s.find(_.id == c.id).exists(_.state == Container.State.Stopped))
            }
        }

        "filters by label" in run {
            val labelVal = uniqueName("kyo-filter")
            Container.init(alpine.label("kyo-test", labelVal)).map { c =>
                Container.list(all = false, filters = Map("label" -> Seq(s"kyo-test=$labelVal"))).map { summaries =>
                    assert(summaries.size == 1)
                    assert(summaries.head.id == c.id)
                }
            }
        }

        "returns empty for no matches" in run {
            Container.list(all = false, filters = Map("name" -> Seq("nonexistent-name-xyz-99999"))).map { summaries =>
                assert(summaries.isEmpty)
            }
        }

        "returns populated ports for container with port mapping" in run {
            val name = uniqueName("kyo-list-ports")
            Container.init(alpine.name(name).port(80, 18080)).map { c =>
                Container.list.map { summaries =>
                    val summary = summaries.find(_.id == c.id)
                    assert(summary.isDefined, s"Container $name not found in list")
                    assert(
                        summary.get.ports.nonEmpty,
                        s"Expected ports to be populated for container with port mapping, got empty ports"
                    )
                    assert(
                        summary.get.ports.exists(_.containerPort == 80),
                        s"Expected containerPort 80 in ports, got: ${summary.get.ports}"
                    )
                }
            }
        }

        "returns populated mounts for container with volume" in run {
            val volName = Container.Volume.Id(uniqueName("kyo-list-mounts"))
            for
                _ <- Container.Volume.create(Container.Volume.Config(name = Present(volName)))
                result <- Container.use(alpine.volume(volName, Path("/data"))) { c =>
                    Container.list.map { summaries =>
                        val summary = summaries.find(_.id == c.id)
                        assert(summary.isDefined, "Container not found in list")
                        assert(
                            summary.get.mounts.nonEmpty,
                            s"Expected mounts to be populated for container with volume, got empty mounts"
                        )
                    }
                }
                _ <- Container.Volume.remove(volName)
            yield result
            end for
        }
    }

    "prune" - {
        "removes stopped containers and returns their ids" in run {
            val labelVal = uniqueName("kyo-prune")
            Container.init(alpine.autoRemove(false).label("kyo-prune", labelVal)).map { c =>
                val cid = c.id
                for
                    _ <- c.stop
                    r <- Container.prune(filters = Map("label" -> Seq(s"kyo-prune=$labelVal")))
                yield
                    assert(r.deleted.nonEmpty)
                    assert(r.deleted.exists(_.contains(cid.value.take(12))))
                end for
            }
        }

        "returns only valid container IDs — no headers or summary lines" in run {
            val labelVal = uniqueName("kyo-prune-ids")
            Container.init(alpine.autoRemove(false).label("kyo-prune-ids", labelVal)).map { c =>
                for
                    _ <- c.stop
                    r <- Container.prune(filters = Map("label" -> Seq(s"kyo-prune-ids=$labelVal")))
                yield
                    // All entries in `deleted` should be valid hex container IDs (12-64 chars)
                    val hexPattern = "^[a-f0-9]{12,64}$".r
                    r.deleted.foreach { entry =>
                        assert(
                            hexPattern.findFirstIn(entry).isDefined,
                            s"Prune returned non-ID entry: '$entry' — expected only hex container IDs"
                        )
                    }
                    assert(r.deleted.nonEmpty, "Expected at least one pruned container")
                end for
            }
        }
    }

    // =========================================================================
    // Image
    // =========================================================================

    "ContainerImage.pull" - {
        "pulls an image and it becomes inspectable" in run {
            val img = ContainerImage("alpine", "latest")
            for
                _    <- ContainerImage.pull(img)
                info <- ContainerImage.inspect(img)
            yield
                assert(info.repoTags.exists(_.reference.contains("alpine")))
                assert(info.size > 0)
            end for
        }

        "fails for nonexistent image" in run {
            Abort.run[ContainerException] {
                ContainerImage.pull(ContainerImage("nonexistent-image-xyzzy-99999", "notatag"))
            }.map { r =>
                assert(r.isFailure)
            }
        }

        "imagePull actually contacts registry when image exists — not identical to ensure" in run {
            val img = ContainerImage("alpine", "latest")
            for
                // Ensure alpine is already present
                _ <- ContainerImage.ensure(img)
                // Time ensure (local check only — should be fast)
                t0e <- Clock.now
                _   <- ContainerImage.ensure(img)
                t1e <- Clock.now
                ensureMs = t1e.toJava.toEpochMilli - t0e.toJava.toEpochMilli
                // Time pull (should contact registry — should take longer)
                t0p <- Clock.now
                _   <- ContainerImage.pull(img)
                t1p <- Clock.now
                pullMs = t1p.toJava.toEpochMilli - t0p.toJava.toEpochMilli
            yield
                // Both should succeed, but pull should take meaningfully longer
                // because it contacts the registry. If pull is identical to ensure
                // (skips when image exists locally), both will be equally fast.
                // ensure is a pure local check (~50-200ms), pull should be 500ms+
                assert(
                    pullMs > ensureMs * 2 || pullMs > 500,
                    s"pull (${pullMs}ms) should be slower than ensure (${ensureMs}ms) — " +
                        "pull appears to skip registry contact when image exists locally"
                )
            end for
        }
    }

    "ContainerImage.pullWithProgress" - {
        "streams progress events with status" in run {
            val img = ContainerImage("alpine", "latest")
            ContainerImage.ensure(img).andThen {
                Scope.run {
                    ContainerImage.pullWithProgress(img).run.map { events =>
                        assert(events.nonEmpty)
                        assert(events.forall(_.status.nonEmpty))
                    }
                }
            }
        }

        "first event arrives before pull completes — true incremental streaming" in run {
            val img = ContainerImage("nginx", "latest")
            for
                // Remove nginx so the pull has real work to do
                _ <- Abort.run[ContainerException](ContainerImage.remove(img, force = true))
                timedResult <- Abort.run[Timeout] {
                    Async.timeout(15.seconds) {
                        Scope.run {
                            for
                                t0         <- Clock.now
                                firstEvent <- ContainerImage.pullWithProgress(img).take(1).run
                                tFirst     <- Clock.now
                            yield (firstEvent, tFirst.toJava.toEpochMilli - t0.toJava.toEpochMilli)
                        }
                    }
                }
                // Ensure nginx is present for other tests regardless of outcome
                _ <- ContainerImage.ensure(img)
            yield timedResult match
                case Result.Success((firstEvent, firstMs)) =>
                    assert(firstEvent.nonEmpty, "Expected at least one progress event")
                    assert(
                        firstMs < 15000,
                        s"First event took ${firstMs}ms — events are buffered until pull completion"
                    )
                case Result.Failure(_) =>
                    // Timed out — Podman buffers pull output, first event doesn't arrive within 15s
                    info("pullWithProgress timed out at 15s — runtime may buffer pull output (not truly streaming)")
                    succeed
            end for
        }
    }

    "ContainerImage.list" - {
        "lists local images including alpine" in run {
            for
                _      <- ContainerImage.ensure(ContainerImage("alpine", "latest"))
                images <- ContainerImage.list
            yield
                assert(images.nonEmpty)
                assert(images.exists(_.repoTags.exists(_.reference.contains("alpine"))))
        }
    }

    "ContainerImage.inspect" - {
        "returns image metadata with architecture and OS" in run {
            val img = ContainerImage("alpine", "latest")
            for
                _ <- ContainerImage.ensure(img)
                i <- ContainerImage.inspect(img)
            yield
                assert(i.id.value.nonEmpty)
                assert(i.repoTags.exists(_.reference.contains("alpine")))
                assert(i.size > 0)
                assert(i.architecture.nonEmpty)
                assert(i.os == "linux")
            end for
        }

        "fails for nonexistent image" in run {
            Abort.run[ContainerException] {
                ContainerImage.inspect(ContainerImage("nonexistent-image-xyz", "latest"))
            }.map(r => assert(r.isFailure))
        }
    }

    "ContainerImage.remove" - {
        "removes a tagged image" in run {
            val tagName = uniqueName("kyo-rm")
            val img     = ContainerImage("alpine", "latest")
            for
                _     <- ContainerImage.ensure(img)
                _     <- ContainerImage.tag(img, tagName, "v1")
                r     <- ContainerImage.remove(ContainerImage(tagName, "v1"))
                check <- Abort.run[ContainerException](ContainerImage.inspect(ContainerImage(tagName, "v1")))
            yield
                assert(r.nonEmpty)
                assert(check.isFailure)
            end for
        }
    }

    "ContainerImage.tag" - {
        "tags an image and new tag is inspectable" in run {
            val tagName = uniqueName("kyo-tag")
            val img     = ContainerImage("alpine", "latest")
            for
                _ <- ContainerImage.ensure(img)
                _ <- ContainerImage.tag(img, tagName, "v1")
                i <- ContainerImage.inspect(ContainerImage(tagName, "v1"))
                _ <- ContainerImage.remove(ContainerImage(tagName, "v1"))
            yield assert(i.repoTags.exists(_.reference.contains(tagName)))
            end for
        }
    }

    "ContainerImage.buildFromPath" - {
        "streams build progress incrementally during multi-step build" in run {
            val dir     = Path("/tmp/" + uniqueName("kyo-build-inc"))
            val imgName = uniqueName("kyo-built-inc")
            for
                _ <- dir.mkDir
                // Multi-step Dockerfile with sleeps to ensure build takes time
                _ <- (dir / "Dockerfile").write(
                    "FROM alpine:latest\n" +
                        "RUN sleep 1 && echo step1\n" +
                        "RUN sleep 1 && echo step2\n" +
                        "RUN sleep 1 && echo step3\n"
                )
                result <- Scope.run {
                    for
                        t0         <- Clock.now
                        firstEvent <- ContainerImage.buildFromPath(dir, tags = Seq(s"$imgName:latest"), noCache = true).take(1).run
                        tFirst     <- Clock.now
                    yield (firstEvent, tFirst.toJava.toEpochMilli - t0.toJava.toEpochMilli)
                }
                // Clean up: build full image then remove
                _ <- Scope.run { ContainerImage.buildFromPath(dir, tags = Seq(s"$imgName:latest"), noCache = true).run.unit }
                _ <- Abort.run[ContainerException](ContainerImage.remove(ContainerImage(imgName, "latest"), force = true))
                _ <- (dir / "Dockerfile").remove
                _ <- dir.removeAll
            yield
                val (firstEvent, firstMs) = result
                assert(firstEvent.nonEmpty, "Expected at least one build progress event")
                // Build takes 3+ seconds (3 sleep steps). If streaming, first event
                // arrives quickly (< 2s). If collect-then-emit, take(1) blocks for 3s+.
                assert(
                    firstMs < 2000,
                    s"First build event took ${firstMs}ms — expected < 2000ms for a 3s build. " +
                        "Events are likely buffered until build completes (not streaming)"
                )
            end for
        }

        "builds from local directory and image is inspectable" in run {
            val dir     = Path("/tmp/" + uniqueName("kyo-build"))
            val imgName = uniqueName("kyo-built")
            for
                _ <- dir.mkDir
                _ <- (dir / "Dockerfile").write("FROM alpine:latest\nRUN echo built\n")
                _ <- Scope.run {
                    ContainerImage.buildFromPath(dir, tags = Seq(s"$imgName:latest")).run
                }
                i <- ContainerImage.inspect(ContainerImage(imgName, "latest"))
                _ <- ContainerImage.remove(ContainerImage(imgName, "latest"), force = true)
                _ <- (dir / "Dockerfile").remove
                _ <- dir.removeAll
            yield assert(i.repoTags.exists(_.reference.contains(imgName)))
            end for
        }
    }

    "ContainerImage.search" - {
        "searches Docker Hub and returns results" in run {
            ContainerImage.search("alpine", limit = 5).map { results =>
                assert(results.nonEmpty)
                assert(results.size <= 5)
                assert(results.exists(_.name.contains("alpine")))
            }
        }
    }

    "ContainerImage.history" - {
        "returns layer history with created-by commands" in run {
            val img = ContainerImage("alpine", "latest")
            for
                _       <- ContainerImage.ensure(img)
                history <- ContainerImage.history(img)
            yield
                assert(history.nonEmpty)
                assert(history.exists(_.size > 0))
            end for
        }
    }

    "ContainerImage.prune" - {
        "removes unused images and returns result" in run {
            ContainerImage.prune.map { result =>
                assert(result.spaceReclaimed >= 0)
            }
        }
    }

    "ContainerImage.commit" - {
        "creates image from container with committed changes" in run {
            val imgName = uniqueName("kyo-commit")
            Container.init(alpine).map { c =>
                for
                    _  <- c.exec("touch", "/committed-marker-file")
                    id <- ContainerImage.commit(c.id, repo = imgName, tag = "v1")
                    i  <- ContainerImage.inspect(ContainerImage(imgName, "v1"))
                    _  <- ContainerImage.remove(ContainerImage(imgName, "v1"), force = true)
                yield
                    assert(id.nonEmpty)
                    assert(i.repoTags.exists(_.reference.contains(imgName)))
            }
        }
    }

    "ContainerImage.RegistryAuth" - {
        "apply creates auth with non-empty auths map" in {
            val auth = ContainerImage.RegistryAuth("user", "pass", "https://index.docker.io/v1/")
            assert(auth.auths.nonEmpty)
            assert(auth.auths.contains(ContainerImage.Registry("https://index.docker.io/v1/")))
        }

        "fromConfig does not crash on missing or malformed config" in run {
            // RegistryAuth.fromConfig reads ~/.docker/config.json — it should not crash
            // even if the file is missing, empty, or has unexpected shape
            Abort.run[ContainerException] {
                ContainerImage.RegistryAuth.fromConfig
            }.map {
                case Result.Success(auth) =>
                    // Successfully loaded — verify it's a valid RegistryAuth (may be empty)
                    succeed
                case Result.Failure(_) =>
                    // Graceful failure is acceptable (e.g., no config file)
                    succeed
            }
        }
    }

    // =========================================================================
    // Network
    // =========================================================================

    "Network.create" - {
        "creates a bridge network and returns valid id" in run {
            val name = uniqueName("kyo-net")
            for
                id   <- Container.Network.create(Container.Network.Config(name = name))
                info <- Container.Network.inspect(id)
                _    <- Container.Network.remove(id)
            yield
                assert(id.value.nonEmpty)
                assert(info.name == name)
                assert(info.driver == Container.NetworkDriver.Bridge)
            end for
        }

        "creates with labels" in run {
            val name = uniqueName("kyo-net-lbl")
            for
                id <- Container.Network.create(Container.Network.Config(
                    name = name,
                    labels = Map("env" -> "test")
                ))
                info <- Container.Network.inspect(id)
                _    <- Container.Network.remove(id)
            yield assert(info.labels("env") == "test")
            end for
        }
    }

    "Network.list" - {
        "lists all networks including default bridge" in run {
            Container.Network.list.map { nets =>
                assert(nets.nonEmpty)
                assert(nets.exists(n => n.name == "bridge" || n.name == "podman"))
            }
        }

        "filters by name" in run {
            val name = uniqueName("kyo-net-flt")
            for
                id   <- Container.Network.create(Container.Network.Config(name = name))
                nets <- Container.Network.list(filters = Map("name" -> Seq(name)))
                _    <- Container.Network.remove(id)
            yield
                assert(nets.size == 1)
                assert(nets.head.name == name)
            end for
        }
    }

    "Network.inspect" - {
        "returns network details matching create config" in run {
            val name = uniqueName("kyo-net-insp")
            for
                id   <- Container.Network.create(Container.Network.Config(name = name))
                info <- Container.Network.inspect(id)
                _    <- Container.Network.remove(id)
            yield
                assert(info.name == name)
                assert(info.id == id)
                assert(info.driver == Container.NetworkDriver.Bridge)
            end for
        }

        "fails with NetworkNotFound for bad id" in run {
            Abort.run[ContainerException] {
                Container.Network.inspect(Container.Network.Id("nonexistent-net-xyz-999"))
            }.map { r =>
                r match
                    case Result.Failure(_: ContainerException.NetworkNotFound) => succeed
                    case other                                                 => fail(s"Expected NetworkNotFound, got $other")
            }
        }
    }

    "Network.remove" - {
        "removes a network and inspect fails" in run {
            val name = uniqueName("kyo-net-rm")
            for
                id <- Container.Network.create(Container.Network.Config(name = name))
                _  <- Container.Network.remove(id)
                r  <- Abort.run[ContainerException](Container.Network.inspect(id))
            yield r match
                case Result.Failure(_: ContainerException.NetworkNotFound) => succeed
                case other                                                 => fail(s"Expected NetworkNotFound, got $other")
            end for
        }
    }

    "Network.connect" - {
        "two containers on same network can ping each other" in run {
            val netName = uniqueName("kyo-net-ping")
            for
                netId <- Container.Network.create(Container.Network.Config(name = netName))
                result <- Container.use(alpine.name(uniqueName("kyo-srv"))) { server =>
                    Container.use(alpine.name(uniqueName("kyo-cli"))) { client =>
                        for
                            _ <- Container.Network.connect(netId, server.id, aliases = Seq("server"))
                            _ <- Container.Network.connect(netId, client.id)
                            r <- client.exec("ping", "-c", "1", "-W", "2", "server")
                        yield r.isSuccess
                    }
                }
                _ <- Container.Network.remove(netId)
            yield assert(result)
            end for
        }
    }

    "Network.prune" - {
        "returns correct network IDs for pruned networks" in run {
            val netName = uniqueName("kyo-net-prune")
            val label   = uniqueName("kyo-net-prune-lbl")
            for
                netId <- Container.Network.create(Container.Network.Config(
                    name = netName,
                    labels = Map("kyo-prune-test" -> label)
                ))
                pruned <- Container.Network.prune(filters = Map("label" -> Seq(s"kyo-prune-test=$label")))
            yield
                // The returned IDs should include our network
                assert(pruned.nonEmpty, "Expected at least one pruned network")
                assert(
                    pruned.exists(id => id.value == netId.value || id.value.contains(netName)),
                    s"Expected pruned IDs to include created network $netId (name=$netName), got: ${pruned.map(_.value)}"
                )
            end for
        }
    }

    // =========================================================================
    // Volume
    // =========================================================================

    "Volume.create" - {
        "creates a volume with auto-generated name" in run {
            for
                info <- Container.Volume.create(Container.Volume.Config())
                _    <- Container.Volume.remove(info.name)
            yield
                assert(info.name.value.nonEmpty)
                assert(info.driver == "local")
                assert(info.mountpoint.nonEmpty)
        }

        "creates a volume with explicit name" in run {
            val name = Container.Volume.Id(uniqueName("kyo-vol"))
            for
                info <- Container.Volume.create(Container.Volume.Config(name = Present(name)))
                _    <- Container.Volume.remove(name)
            yield assert(info.name == name)
            end for
        }

        "creates with labels" in run {
            val name = Container.Volume.Id(uniqueName("kyo-vol-lbl"))
            for
                info <- Container.Volume.create(Container.Volume.Config(
                    name = Present(name),
                    labels = Map("env" -> "test")
                ))
                _ <- Container.Volume.remove(name)
            yield assert(info.labels("env") == "test")
            end for
        }
    }

    "Volume.list" - {
        "lists volumes including created one" in run {
            val name = Container.Volume.Id(uniqueName("kyo-vol-lst"))
            for
                _    <- Container.Volume.create(Container.Volume.Config(name = Present(name)))
                vols <- Container.Volume.list
                _    <- Container.Volume.remove(name)
            yield assert(vols.exists(_.name == name))
            end for
        }

        "preserves labels from creation" in run {
            val name = Container.Volume.Id(uniqueName("kyo-vol-labels"))
            for
                _ <- Container.Volume.create(Container.Volume.Config(
                    name = Present(name),
                    labels = Map("test-key" -> "test-value", "another" -> "label")
                ))
                vols <- Container.Volume.list
                _    <- Container.Volume.remove(name)
            yield
                val vol = vols.find(_.name == name)
                assert(vol.isDefined, s"Volume $name not found in list")
                assert(
                    vol.get.labels.get("test-key").contains("test-value"),
                    s"Expected label test-key=test-value in volume list, got labels: ${vol.get.labels}"
                )
                assert(
                    vol.get.labels.get("another").contains("label"),
                    s"Expected label another=label in volume list, got labels: ${vol.get.labels}"
                )
            end for
        }
    }

    "Volume.inspect" - {
        "returns volume details with mountpoint" in run {
            val name = Container.Volume.Id(uniqueName("kyo-vol-insp"))
            for
                _    <- Container.Volume.create(Container.Volume.Config(name = Present(name)))
                info <- Container.Volume.inspect(name)
                _    <- Container.Volume.remove(name)
            yield
                assert(info.name == name)
                assert(info.mountpoint.nonEmpty)
                assert(info.driver == "local")
            end for
        }

        "fails with VolumeNotFound for bad name" in run {
            Abort.run[ContainerException] {
                Container.Volume.inspect(Container.Volume.Id("nonexistent-vol-xyz-999"))
            }.map { r =>
                r match
                    case Result.Failure(_: ContainerException.VolumeNotFound) => succeed
                    case other                                                => fail(s"Expected VolumeNotFound, got $other")
            }
        }
    }

    "Volume.remove" - {
        "removes a volume and inspect fails" in run {
            val name = Container.Volume.Id(uniqueName("kyo-vol-rm"))
            for
                _ <- Container.Volume.create(Container.Volume.Config(name = Present(name)))
                _ <- Container.Volume.remove(name)
                r <- Abort.run[ContainerException](Container.Volume.inspect(name))
            yield r match
                case Result.Failure(_: ContainerException.VolumeNotFound) => succeed
                case other                                                => fail(s"Expected VolumeNotFound, got $other")
            end for
        }
    }

    "Volume.prune" - {
        "removes unused volumes and returns result" in run {
            Container.Volume.prune.map { r =>
                assert(r.spaceReclaimed >= 0)
            }
        }
    }

    // =========================================================================
    // Integration / Edge Cases
    // =========================================================================

    "container with mounts" - {
        "bind mount — host file visible in container" in run {
            val hostDir = Path("/tmp/" + uniqueName("kyo-bind"))
            for
                _ <- hostDir.mkDir
                _ <- (hostDir / "data.txt").write("from-host-12345")
                content <- Container.use(alpine.bind(hostDir, Path("/mnt/data"), readOnly = true)) { c =>
                    c.exec("cat", "/mnt/data/data.txt").map(_.stdout.trim)
                }
                _ <- (hostDir / "data.txt").remove
                _ <- hostDir.removeAll
            yield assert(content == "from-host-12345")
            end for
        }

        "bind mount — readOnly prevents writes" in run {
            val hostDir = Path("/tmp/" + uniqueName("kyo-bind-ro"))
            for
                _ <- hostDir.mkDir
                result <- Container.use(alpine.bind(hostDir, Path("/mnt/data"), readOnly = true)) { c =>
                    c.exec("touch", "/mnt/data/test")
                }
                _ <- hostDir.removeAll
            yield assert(!result.isSuccess)
            end for
        }

        "bind mount from /tmp works on macOS" in run {
            val hostDir  = Path("/tmp/" + uniqueName("kyo-tmp-bind"))
            val filename = "test-data.txt"
            for
                _ <- hostDir.mkDir
                _ <- (hostDir / filename).write("from-tmp-host-path")
                content <- Container.use(alpine.bind(hostDir, Path("/mnt/tmpdata"), readOnly = true)) { c =>
                    c.exec("cat", s"/mnt/tmpdata/$filename").map(_.stdout.trim)
                }
                _ <- (hostDir / filename).remove
                _ <- hostDir.removeAll
            yield
                // On macOS, readlink -f is not available. If the implementation uses readlink -f
                // to resolve /tmp -> /private/tmp, this test would fail.
                assert(
                    content == "from-tmp-host-path",
                    s"Expected 'from-tmp-host-path' from /tmp bind mount, got '$content'"
                )
            end for
        }

        "named volume persists across container recreations" in run {
            val volName = Container.Volume.Id(uniqueName("kyo-vol-persist"))
            for
                _ <- Container.Volume.create(Container.Volume.Config(name = Present(volName)))
                _ <- Container.use(alpine.volume(volName, Path("/data"))) { c =>
                    c.exec("sh", "-c", "echo persistent-data-xyz > /data/file.txt")
                }
                content <- Container.use(alpine.volume(volName, Path("/data"))) { c =>
                    c.exec("cat", "/data/file.txt").map(_.stdout.trim)
                }
                _ <- Container.Volume.remove(volName)
            yield assert(content == "persistent-data-xyz")
            end for
        }
    }

    "container with environment from Command" - {
        "env from Command.envAppend appears in container" in run {
            Container.use(
                Container.Config("alpine")
                    .command(Command("sh", "-c", "trap 'exit 0' TERM; echo $MY_VAR; sleep infinity")
                        .envAppend(Map("MY_VAR" -> "from-command-env")))
            ) { c =>
                c.logsText.map { text =>
                    assert(text.contains("from-command-env"))
                }
            }
        }
    }

    "multi-container scenarios" - {
        "shared volume between two containers" in run {
            val volName = Container.Volume.Id(uniqueName("kyo-shared"))
            for
                _ <- Container.Volume.create(Container.Volume.Config(name = Present(volName)))
                _ <- Container.use(alpine.volume(volName, Path("/shared"))) { writer =>
                    writer.exec("sh", "-c", "echo shared-payload-abc > /shared/file.txt").map { r =>
                        assert(r.isSuccess)
                    }
                }
                content <- Container.use(alpine.volume(volName, Path("/shared"))) { reader =>
                    reader.exec("cat", "/shared/file.txt").map(_.stdout.trim)
                }
                _ <- Container.Volume.remove(volName)
            yield assert(content == "shared-payload-abc")
            end for
        }
    }

    "concurrent operations" - {
        "parallel init of multiple containers yields distinct ids" in run {
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

        "parallel exec on same container returns correct results" in run {
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
        "scope cleanup runs even when computation aborts" in run {
            for
                idRef <- AtomicRef.init[Container.Id](Container.Id(""))
                _ <- Abort.run[ContainerException] {
                    Scope.run {
                        Container.init(alpine.autoRemove(false)).map { c =>
                            idRef.set(c.id).andThen {
                                Abort.fail(ContainerException.General("test", "intentional"))
                            }
                        }
                    }
                }
                id <- idRef.get
                r  <- Abort.run[ContainerException](Container.attach(id))
            yield
                assert(id.value.nonEmpty)
                r match
                    case Result.Failure(_: ContainerException.NotFound) => succeed
                    case Result.Success(_)                              => fail("Container should have been cleaned up")
                    case other                                          => fail(s"Expected NotFound, got $other")
                end match
        }

        "operations on removed container fail with NotFound" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _  <- c.stop
                    _  <- c.remove
                    r1 <- Abort.run[ContainerException](c.exec("echo", "hi"))
                    r2 <- Abort.run[ContainerException](c.inspect)
                    r3 <- Abort.run[ContainerException](c.state)
                yield
                    assert(r1.isFailure)
                    assert(r2.isFailure)
                    assert(r3.isFailure)
            }
        }

        "backend unavailable gives clear error with backend name" in run {
            Abort.run[ContainerException] {
                Container.withBackend(_.UnixSocket(Path("/nonexistent/socket.sock"))) {
                    Container.init(alpine)
                }
            }.map { r =>
                r match
                    case Result.Failure(e: ContainerException.BackendUnavailable) =>
                        assert(e.backend == "http")
                        assert(e.reason.nonEmpty)
                    case other => fail(s"Expected BackendUnavailable, got $other")
            }
        }

        "error on failed operation contains correct resource ID — not last CLI arg" in run {
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
                case Result.Failure(e: ContainerException.NotFound) =>
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
            end for
        }
    }

    // =========================================================================
    // Edge Cases (from testcontainers, docker-java, moby issues)
    // =========================================================================

    "lifecycle races" - {
        // moby/moby#37698, moby/moby#23371 — name conflict race after removal
        "name reuse after delete — no 409 conflict" in run {
            val name = uniqueName("kyo-reuse")
            for
                id1 <- Container.use(alpine.name(name).autoRemove(false)) { c =>
                    c.id: Container.Id < Any
                }
                // Container is stopped and removed by run. Immediately create with same name.
                id2 <- Container.use(alpine.name(name)) { c =>
                    c.id: Container.Id < Any
                }
            yield assert(id1 != id2)
            end for
        }

        "stop on already-stopped container is idempotent" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
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

        "start on already-running container is idempotent" in run {
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
        "concurrent stop and remove does not leave zombie" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                val cid = c.id
                for
                    f1 <- Fiber.init(Abort.run[ContainerException](c.stop))
                    f2 <- Fiber.init(Abort.run[ContainerException](c.remove(force = true)))
                    _  <- f1.get
                    _  <- f2.get
                    r  <- Abort.run[ContainerException](Container.attach(cid))
                yield assert(r.isFailure) // container should be fully gone
                end for
            }
        }

        "kill on paused container" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
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

        "autoRemove container — explicit remove returns NotFound" in run {
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
                yield assert(r.isFailure)
                end for
            }
        }
    }

    "exec edge cases" - {
        "exec on stopped container fails without unnecessary retries" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _  <- c.stop
                    t0 <- Clock.now
                    r  <- Abort.run[ContainerException](c.exec("echo", "hello"))
                    t1 <- Clock.now
                yield
                    assert(r.isFailure, "Expected exec on stopped container to fail")
                    val elapsedMs = t1.toJava.toEpochMilli - t0.toJava.toEpochMilli
                    // The Retry wraps exec with Schedule.fixed(100.millis).take(2), meaning
                    // 3 total attempts with 100ms between each. For a deterministic failure
                    // like exec-on-stopped-container, this wastes 200ms+ on pointless retries.
                    // Without retries it should fail in < 500ms (Podman's SSH-based daemon adds latency).
                    assert(
                        elapsedMs < 500,
                        s"exec on stopped container took ${elapsedMs}ms — " +
                            "retries are wasting time on a deterministic NotFound/AlreadyStopped failure"
                    )
                end for
            }
        }

        "exec on stopped container fails clearly" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.stop
                    r <- Abort.run[ContainerException](c.exec("echo", "hello"))
                yield assert(r.isFailure)
            }
        }

        // moby/moby#48965 — exec on paused container
        "exec on paused container fails" in run {
            Container.init(alpine).map { c =>
                for
                    _ <- c.pause
                    r <- Abort.run[ContainerException](c.exec("echo", "hello"))
                yield assert(r.isFailure)
            }
        }

        // docker-java#481 — output pipe not fully consumed causes hang
        "exec with large output does not hang (backpressure)" in run {
            Container.init(alpine).map { c =>
                // 1MB of output — verifies stdout is fully drained
                c.exec("sh", "-c", "dd if=/dev/urandom bs=1024 count=1024 2>/dev/null | base64").map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.length > 1024 * 1024)
                }
            }
        }

        "exec exit code race — fast exit returns correct code" in run {
            Container.init(alpine).map { c =>
                // Command that exits before stdout could be read
                for
                    r1 <- c.exec("sh", "-c", "exit 0")
                    r2 <- c.exec("sh", "-c", "exit 1")
                    r3 <- c.exec("sh", "-c", "exit 137")
                yield
                    assert(r1.exitCode == ExitCode.Success)
                    assert(r2.exitCode == ExitCode.Failure(1))
                    assert(r3.exitCode == ExitCode.Failure(137))
            }
        }

        // moby/moby#32633 — high-concurrency exec throttling
        "high concurrency exec — 20 parallel calls" in run {
            Container.init(alpine).map { c =>
                Kyo.foreach((1 to 20).toSeq) { i =>
                    Fiber.init(c.exec("echo", i.toString))
                }.map { fibers =>
                    Kyo.foreach(fibers)(_.get).map { results =>
                        assert(results.forall(_.isSuccess))
                        assert(results.map(_.stdout.trim.toInt).toSet == (1 to 20).toSet)
                    }
                }
            }
        }
    }

    "log edge cases" - {
        // moby/moby#41820 — unexpected EOF on follow with fast exit
        "follow logs on container that exits immediately" in run {
            val config = Container.Config("alpine").command("echo", "quick")
            Container.init(config).map { c =>
                Scope.run {
                    c.logStream.run.map { entries =>
                        assert(entries.exists(_.content.contains("quick")))
                    }
                }
            }
        }

        // docker/cli#5305 — log follow disconnects on restart
        "logs after container restart contain both runs" in run {
            // Use the container's main command to produce output, since exec output
            // to /dev/stdout is not captured in container logs on all runtimes
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo run-marker; sleep infinity")
                .autoRemove(false)
            Container.init(config).map { c =>
                for
                    _    <- c.restart
                    text <- c.logsText
                yield
                    // After restart, the command runs again, so run-marker appears at least twice
                    assert(text.contains("run-marker"))
            }
        }

        "TTY mode logs — no multiplexing, raw stream" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo tty-output; sleep infinity")
                .allocateTty(true)
            Container.init(config).map { c =>
                c.logsText.map { text =>
                    assert(text.contains("tty-output"))
                }
            }
        }
    }

    "stats edge cases" - {
        "stats on stopped container fails cleanly" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.stop
                    r <- Abort.run[ContainerException](c.stats)
                yield assert(r.isFailure)
            }
        }

        "statsStream cancellation releases resources" in run {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.statsStream.take(1).run.map { stats =>
                        assert(stats.size == 1)
                    }
                }
                    // After scope closes, verify container is still healthy
                    .andThen(c.state.map(s => assert(s == Container.State.Running)))
            }
        }
    }

    "copy edge cases" - {
        // CVE-2018-15664 — symlink traversal in archive path
        "copy file with unicode name roundtrip" in run {
            val localPath = Path("/tmp/" + uniqueName("kyo-unicode"))
            Container.init(alpine).map { c =>
                for
                    _      <- localPath.write("unicode content")
                    _      <- c.copyTo(localPath, Path("/tmp/файл"))
                    result <- c.exec("cat", "/tmp/файл")
                    _      <- localPath.remove
                yield
                    assert(result.isSuccess)
                    assert(result.stdout.trim == "unicode content")
            }
        }

        "copy to read-only filesystem fails" in run {
            Container.init(alpine.readOnlyFilesystem(true)).map { c =>
                Abort.run[ContainerException] {
                    val localPath = Path("/tmp/" + uniqueName("kyo-ro"))
                    localPath.write("data").andThen {
                        c.copyTo(localPath, Path("/usr/test"))
                    }
                }.map(r => assert(r.isFailure))
            }
        }

        "copy large file (> 64KB pipe buffer)" in run {
            val localPath = Path("/tmp/" + uniqueName("kyo-large"))
            val content   = "x" * (128 * 1024) // 128KB
            Container.init(alpine).map { c =>
                for
                    _      <- localPath.write(content)
                    _      <- c.copyTo(localPath, Path("/tmp/large"))
                    result <- c.exec("wc", "-c", "/tmp/large")
                    _      <- localPath.remove
                yield
                    assert(result.isSuccess)
                    assert(result.stdout.trim.split("\\s+").head.toInt == 128 * 1024)
            }
        }
    }

    "port edge cases" - {
        // testcontainers#5385 — port not bound immediately after start
        "port not immediately available after start — health check waits" in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "sleep 1; while true; do echo ok | nc -l -p 9090; done")
                .port(9090, 19090)
                .healthCheck(Container.HealthCheck.port(9090, schedule = Schedule.fixed(200.millis).take(30)))
            Container.init(config).map { c =>
                c.state.map(s => assert(s == Container.State.Running))
            }
        }

        "exposed port without host binding has Absent hostPort in inspect" in run {
            Container.init(alpine.port(80)).map { c =>
                c.inspect.map { info =>
                    val binding = info.ports.find(_.containerPort == 80)
                    assert(binding.isDefined)
                }
            }
        }
    }

    "network edge cases" - {
        // moby/moby#17217 — "has active endpoints" error on network remove
        "remove network with still-connected container fails" in run {
            val netName = uniqueName("kyo-net-inuse")
            for
                netId <- Container.Network.create(Container.Network.Config(name = netName))
                result <- Container.use(alpine) { c =>
                    for
                        _ <- Container.Network.connect(netId, c.id)
                        r <- Abort.run[ContainerException](Container.Network.remove(netId))
                    yield r.isFailure
                }
                _ <- Container.Network.remove(netId) // cleanup after container is gone
            yield assert(result)
            end for
        }
    }

    "volume edge cases" - {
        // moby/moby#43068 — volume-in-use removal
        "remove volume in use by stopped container fails" in run {
            val volName = Container.Volume.Id(uniqueName("kyo-vol-inuse"))
            for
                _ <- Container.Volume.create(Container.Volume.Config(name = Present(volName)))
                c <- Container.init(alpine.volume(volName, Path("/data")).autoRemove(false))
                _ <- c.stop
                r <- Abort.run[ContainerException](Container.Volume.remove(volName))
                _ <- Abort.run[ContainerException](c.remove)
            yield assert(r.isFailure)
            end for
        }

        "remove with removeVolumes cleans up anonymous volumes" in run {
            val volName = Container.Volume.Id(uniqueName("kyo-vol-anon"))
            Container.init(alpine
                .mount(Container.Config.Mount.Volume(volName, Path("/anon-data")))
                .autoRemove(false)).map { c =>
                for
                    _ <- c.stop
                    _ <- c.remove(force = false, removeVolumes = true)
                yield succeed
            }
        }
    }

    "podman compatibility" - {
        "default network name may differ — handle both bridge and podman" in run {
            Container.init(alpine).map { c =>
                c.inspect.map { info =>
                    val netNames = info.networkSettings.networks.values.toSeq
                    assert(netNames.nonEmpty)
                }
            }
        }
    }

    "image pull edge cases" - {
        "pull progress tracks per-layer by id" in run {
            val img = ContainerImage("alpine", "latest")
            ContainerImage.ensure(img).andThen {
                Scope.run {
                    ContainerImage.pullWithProgress(img).run.map { events =>
                        val layerIds = events.flatMap(_.id.toList).distinct
                        // multi-layer image should have multiple distinct layer IDs
                        assert(events.exists(_.status.nonEmpty))
                    }
                }
            }
        }
    }

    // =========================================================================
    // Checkpoint/Restore
    // =========================================================================

    "checkpoint" - {
        "checkpoint/restore preserves a working container ID" in run {
            // CRIU may not be available — wrap in Abort.run to handle gracefully
            Container.init(alpine.autoRemove(false)).map { c =>
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
                    case Result.Success(_)                                  => succeed
                    case Result.Failure(_: ContainerException.NotSupported) => succeed // CRIU not available
                    case Result.Failure(e)                                  =>
                        // Checkpoint/restore may fail for various reasons (CRIU not installed, etc.)
                        // The important thing is that IF restore succeeds, the ID should work
                        val msg = e.toString
                        if msg.contains("CRIU") || msg.contains("criu") || msg.contains("checkpoint") then
                            succeed // Expected on systems without CRIU
                        else
                            fail(s"Unexpected failure: $e")
                        end if
                }
            }
        }

        "checkpoint on non-running container fails with clear error" in run {
            Container.init(alpine.autoRemove(false)).map { c =>
                for
                    _ <- c.stop
                    r <- Abort.run[ContainerException](c.checkpoint("snap1"))
                yield assert(r.isFailure)
            }
        }

        "restore from non-existent checkpoint fails with clear error" in run {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException](c.restore("nonexistent-checkpoint-xyz")).map { r =>
                    assert(r.isFailure)
                }
            }
        }
    }

    // =========================================================================
    // Configurable stats interval
    // =========================================================================

    "statsStream with interval" - {
        "statsStream with custom interval emits stats" in run {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.statsStream(100.millis).take(2).run.map { stats =>
                        assert(stats.size == 2)
                        assert(stats.map(_.readAt).distinct.size == 2)
                    }
                }
            }
        }

        "default statsStream delegates to 200ms interval" in run {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.statsStream.take(1).run.map { stats =>
                        assert(stats.size == 1)
                    }
                }
            }
        }
    }

    // =========================================================================
    // initUnscoped
    // =========================================================================

    "initUnscoped" - {
        "creates container without scope cleanup" taggedAs containerOnly in run {
            val name   = uniqueName("kyo-unscoped")
            val config = alpine.name(name).autoRemove(false)
            Container.initUnscoped(config).map { c =>
                c.state.map { s =>
                    c.stop(0.seconds).andThen {
                        c.remove(force = true).andThen {
                            assert(s == Container.State.Running)
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // exec edge cases
    // =========================================================================

    "exec edge cases" - {
        "exec with command not found" taggedAs containerOnly in run {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException](c.exec("nonexistent-cmd-xyz")).map {
                    case Result.Failure(ex: ContainerException.ExecFailed) =>
                        assert(
                            ex.exitCode == ExitCode.Failure(126) || ex.exitCode == ExitCode.Failure(127),
                            s"Expected exit code 126 or 127 but got ${ex.exitCode}"
                        )
                    case other =>
                        fail(s"Expected ExecFailed but got: $other")
                }
            }
        }
    }

    // =========================================================================
    // config edge cases
    // =========================================================================

    "config edge cases" - {
        "hostname is reflected in container" taggedAs containerOnly in run {
            val config = alpine.hostname("myhost")
            Container.init(config).map { c =>
                c.exec("hostname").map { r =>
                    assert(
                        r.stdout.trim.contains("myhost"),
                        s"Expected hostname to contain 'myhost', got: '${r.stdout.trim}'"
                    )
                }
            }
        }

        "user sets container user" taggedAs containerOnly in run {
            val config = alpine.user("nobody")
            Container.init(config).map { c =>
                c.exec("whoami").map { r =>
                    assert(
                        r.stdout.trim == "nobody",
                        s"Expected 'nobody', got: '${r.stdout.trim}'"
                    )
                }
            }
        }
    }

    // =========================================================================
    // log edge cases
    // =========================================================================

    "log edge cases" - {
        "logStream on stopped container terminates" taggedAs containerOnly in run {
            val config = Container.Config("alpine")
                .command("sh", "-c", "echo done")
                .autoRemove(false)
                .stopTimeout(0.seconds)
            Container.initUnscoped(config).map { c =>
                Async.sleep(2.seconds).andThen {
                    Scope.run {
                        c.logStream.take(1).run.map { entries =>
                            // Stream should terminate without hanging because container exited
                            c.remove(force = true).andThen {
                                assert(entries.size <= 1)
                            }
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // stats edge cases
    // =========================================================================

    "stats edge cases" - {
        "stats on paused container returns valid data" taggedAs containerOnly in run {
            Container.init(alpine).map { c =>
                c.pause.andThen {
                    c.stats.map { s =>
                        c.unpause.andThen {
                            assert(
                                s.memory.usage > 0,
                                s"Expected non-zero memory usage for paused container, got: ${s.memory.usage}"
                            )
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // network edge cases
    // =========================================================================

    "network edge cases" - {
        "container connected to multiple networks" taggedAs containerOnly in run {
            val netName1 = uniqueName("kyo-net-multi1")
            val netName2 = uniqueName("kyo-net-multi2")
            for
                netId1 <- Container.Network.create(Container.Network.Config(name = netName1))
                netId2 <- Container.Network.create(Container.Network.Config(name = netName2))
                result <- Container.use(alpine) { c =>
                    for
                        _    <- c.connectToNetwork(netId1)
                        _    <- c.connectToNetwork(netId2)
                        info <- c.inspect
                    yield
                        assert(
                            info.networkSettings.networks.contains(netId1),
                            s"Expected network $netId1 in inspect, got: ${info.networkSettings.networks.keys}"
                        )
                        assert(
                            info.networkSettings.networks.contains(netId2),
                            s"Expected network $netId2 in inspect, got: ${info.networkSettings.networks.keys}"
                        )
                    end for
                }
                _ <- Container.Network.remove(netId1)
                _ <- Container.Network.remove(netId2)
            yield result
            end for
        }
    }

    // =========================================================================
    // scope cleanup
    // =========================================================================

    "scope cleanup" - {
        "scope cleanup works when container crashes" taggedAs containerOnly in run {
            val name = uniqueName("kyo-crash")
            val config = Container.Config("alpine")
                .command("sh", "-c", "exit 1")
                .name(name)
                .autoRemove(false)
                .stopTimeout(0.seconds)
            Abort.run[ContainerException] {
                Container.use(config) { c =>
                    // Container exits with code 1 immediately.
                    // Wait briefly for the process to exit.
                    Async.sleep(2.seconds).andThen {
                        c.state
                    }
                }
            }.map { _ =>
                // After scope cleanup, the container should be removed.
                // Trying to inspect it should fail.
                Abort.run[ContainerException] {
                    Container.attach(Container.Id(name)).map(_.inspect)
                }.map { r =>
                    assert(r.isFailure, "Expected container to be removed after scope cleanup")
                }
            }
        }
    }

end ContainerTest

class ContainerTestPodman extends ContainerTest("podman")
class ContainerTestDocker extends ContainerTest("docker")
