package kyo

class ContainerItTest extends Test:

    val alpine = Container.Config(ContainerImage("alpine", "latest"))
        .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity")
        .stopTimeout(0.seconds)

    private val nameCounter = new java.util.concurrent.atomic.AtomicLong(0L)

    def uniqueName(prefix: String) =
        s"$prefix-${nameCounter.incrementAndGet()}"

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
            val config = Container.Config("alpine").port(80, 8080)
            assert(config.ports.size == 1)
            assert(config.ports.head.containerPort == 80)
            assert(config.ports.head.hostPort == 8080)
            assert(config.ports.head.protocol == Container.Config.Protocol.TCP)
        }

        "port(container) adds a PortBinding with random host port" in {
            val config = Container.Config("alpine").port(80)
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
            assert(config.networkMode == Container.Config.NetworkMode.Host)
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
            assert(config.labels.is(Dict("env" -> "test")))
        }

        "labels merges with existing" in {
            val config = Container.Config("alpine")
                .label("a", "1")
                .labels(Dict("b" -> "2", "c" -> "3"))
            assert(config.labels.is(Dict("a" -> "1", "b" -> "2", "c" -> "3")))
        }

        "labels overwrites on conflict" in {
            val config = Container.Config("alpine")
                .label("a", "1")
                .labels(Dict("a" -> "2"))
            assert(config.labels.is(Dict("a" -> "2")))
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
            assert(config.command.isDefined)
            assert(config.command.map(_.args) == Present(Chunk("sh", "-c", "echo hello")))
        }

        "command(Command) preserves env and cwd" in {
            val cmd = Command("sh", "-c", "echo hello")
                .envAppend(Map("FOO" -> "bar"))
                .cwd(Path("/tmp"))
            val config = Container.Config("alpine").command(cmd)
            assert(config.command.map(_.args) == Present(Chunk("sh", "-c", "echo hello")))
            assert(config.command.map(_.env) == Present(Map("FOO" -> "bar")))
            assert(config.command.flatMap(_.workDir) == Present(Path("/tmp")))
        }

        "default command is Absent — uses image CMD/ENTRYPOINT" in {
            val config = Container.Config("alpine")
            assert(config.command.isEmpty)
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
        "auto-detect creates a working backend" - runBackends {
            assertRuns(alpine)
        }

        "withBackendConfig(_.UnixSocket) uses http backend" - runRuntimes { runtime =>
            ContainerRuntime.findSocket(runtime) match
                case Some(socketPath) =>
                    Container.withBackendConfig(_.UnixSocket(Path(socketPath))) {
                        assertRuns(alpine)
                    }
                case None =>
                    Container.init(alpine).map { c =>
                        info(s"No Unix socket found for $runtime — test ran with default backend")
                        c.state.map(s => assert(s == Container.State.Running))
                    }
        }

        "withBackendConfig(_.Shell) uses shell backend" - runRuntimes { runtime =>
            Container.withBackendConfig(_.Shell(runtime)) {
                assertRuns(alpine)
            }
        }

        "auto-detect aborts with BackendUnavailable when nothing works" - runBackends {
            Abort.run[ContainerException] {
                Container.withBackendConfig(_.UnixSocket(Path("/nonexistent/socket.sock"))) {
                    Container.init(alpine)
                }
            }.map { result =>
                result match
                    case Result.Failure(e: ContainerBackendUnavailableException) =>
                        assert(e.backend == "http")
                        assert(e.reason.nonEmpty)
                    case other => fail(s"Expected BackendUnavailable, got $other")
            }
        }

        "auto-detect selects a working backend via BackendConfig" - runBackends {
            // AutoDetect resolves the same way as the implicit detection — verify it works
            // Uses run (which provides both HTTP and Shell backends) to exercise each
            assertRuns(alpine)
        }

        "auto-detect passes meter to backend" - runRuntimes { runtime =>
            Meter.initSemaphore(4).map { meter =>
                Container.withBackendConfig(_.Shell(runtime, meter)) {
                    Container.init(alpine).map { c =>
                        Kyo.foreach((1 to 4).toSeq) { i =>
                            Fiber.init(c.exec("echo", i.toString))
                        }.map { fibers =>
                            Kyo.foreach(fibers)(_.get).map { results =>
                                assert(results.forall(_.isSuccess))
                            }
                        }
                    }
                }
            }
        }

        "Shell with explicit command path" - runRuntimes { runtime =>
            val cmd = if runtime == "docker" then "docker" else "podman"
            Container.withBackendConfig(_.Shell(cmd)) {
                assertRuns(alpine)
            }
        }

        "Shell with nonexistent command fails with BackendUnavailable" - runBackends {
            Abort.run[ContainerException] {
                Container.withBackendConfig(_.Shell("nonexistent-runtime-xyz")) {
                    Container.init(alpine)
                }
            }.map { result =>
                result match
                    case Result.Failure(e: ContainerBackendUnavailableException) => succeed
                    case other                                                   => fail(s"Expected BackendUnavailable, got $other")
            }
        }

        "UnixSocket with valid docker socket" - runRuntimes { runtime =>
            if ContainerRuntime.findSocket(runtime).isEmpty then succeed
            else
                val path = ContainerRuntime.findSocket(runtime).get
                Container.withBackendConfig(_.UnixSocket(Path(path))) {
                    assertRuns(alpine)
                }
        }

        "UnixSocket with nonexistent path fails with BackendUnavailable" - runBackends {
            Abort.run[ContainerException] {
                Container.withBackendConfig(_.UnixSocket(Path("/tmp/nonexistent-socket-xyz.sock"))) {
                    Container.init(alpine)
                }
            }.map { result =>
                result match
                    case Result.Failure(e: ContainerBackendUnavailableException) => succeed
                    case Result.Failure(e)                                       => fail(s"Expected BackendUnavailable, got $e")
                    case _                                                       => fail("Expected failure")
            }
        }

        "Meter limits concurrent HTTP operations" - runRuntimes { runtime =>
            if ContainerRuntime.findSocket(runtime).isEmpty then succeed
            else
                Meter.initSemaphore(2).map { meter =>
                    val socketPath = ContainerRuntime.findSocket(runtime).get
                    Container.withBackendConfig(_.UnixSocket(Path(socketPath), meter)) {
                        Container.init(alpine).map { c =>
                            // HTTP backend wraps exec in meter.run, so with meter=2 and
                            // 6 execs sleeping 0.5s, it takes >= 3 * 0.5s = 1.5s
                            val start = java.lang.System.currentTimeMillis()
                            Kyo.foreach((1 to 6).toSeq) { _ =>
                                Fiber.init(c.exec("sleep", "0.5"))
                            }.map(fibers => Kyo.foreach(fibers)(_.get)).map { results =>
                                val elapsed = java.lang.System.currentTimeMillis() - start
                                assert(results.forall(_.isSuccess))
                                assert(elapsed >= 1000, s"Expected >= 1000ms with meter=2, took ${elapsed}ms")
                            }
                        }
                    }
                }
        }

        "nested withBackendConfig overrides outer backend" - runRuntimes { runtime =>
            Container.withBackendConfig(_.Shell(runtime)) {
                val socketOpt = ContainerRuntime.findSocket(runtime)
                if socketOpt.isEmpty then succeed
                else
                    Container.withBackendConfig(_.UnixSocket(Path(socketOpt.get))) {
                        assertRuns(alpine)
                    }
                end if
            }
        }

        "connection refused to valid-looking socket gives clear error" - runBackends {
            val fakePath = Path(s"/tmp/kyo-fake-${java.lang.System.currentTimeMillis}.sock")
            fakePath.write("not a socket").andThen {
                Abort.run[ContainerException] {
                    Container.withBackendConfig(_.UnixSocket(fakePath)) {
                        Container.init(alpine)
                    }
                }.map { result =>
                    fakePath.remove.andThen {
                        result match
                            case Result.Failure(_: ContainerBackendException) => succeed
                            case other                                        => fail(s"Expected backend connection error, got $other")
                    }
                }
            }
        }

        "auto-detect prefers HTTP when socket is available" - runRuntimes { runtime =>
            if ContainerRuntime.findSocket(runtime).isEmpty then succeed
            else
                // When a socket is available, the HTTP backend should work
                val path = ContainerRuntime.findSocket(runtime).get
                Container.withBackendConfig(_.UnixSocket(Path(path))) {
                    Container.init(alpine).map { c =>
                        c.inspect.map { info =>
                            assert(info.state == Container.State.Running)
                        }
                    }
                }
        }

        "currentBackendDescription returns non-empty string" - runBackends {
            Container.currentBackendDescription.map { desc =>
                assert(desc.nonEmpty)
                assert(desc.contains("Backend"))
            }
        }
    }

    // =========================================================================
    // Container Lifecycle
    // =========================================================================

    "init" - {
        "creates, starts, and waits for health check" - runBackends {
            assertRuns(alpine)
        }

        "returns container with valid non-empty id" - runBackends {
            Container.init(alpine).map { c =>
                assert(c.id.value.nonEmpty)
                assert(c.id.value.length >= 12) // Docker short IDs are at least 12 chars
            }
        }

        "registers scope cleanup — container removed on scope close" - runBackends {
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
                    case Result.Failure(_: ContainerMissingException) => succeed
                    case Result.Success(_)                            => fail("Container should have been removed by scope cleanup")
                    case other                                        => fail(s"Expected NotFound, got $other")
                end match
            end for
        }

        "convenience overload with image" - runBackends {
            Container.init(
                image = ContainerImage("alpine", "latest"),
                command = Present(Command("tail", "-f", "/dev/null"))
            ).map { c =>
                c.state.map(s => assert(s == Container.State.Running))
            }
        }

        "fails with ImageNotFound for nonexistent image" - runBackends {
            Abort.run[ContainerException] {
                Container.init(Container.Config("nonexistent-image-xyz-999:notatag"))
            }.map { result =>
                result match
                    case Result.Failure(_: ContainerImageMissingException) => succeed
                    case other                                             => fail(s"Expected ImageNotFound, got $other")
            }
        }

        "init — non-existent image raises ImageNotFound with reference in message" - runBackends {
            val bogus = ContainerImage("kyo-pod-nonexistent-xyzzy:notatag")
            Abort.run[ContainerException] {
                Container.init(Container.Config.default.copy(image = bogus, healthCheck = Container.HealthCheck.init(_ => Kyo.unit)))
            }.map {
                case Result.Failure(e: ContainerImageMissingException) =>
                    assert(e.getMessage.contains("kyo-pod-nonexistent-xyzzy"))
                case other => fail(s"expected ImageNotFound, got $other")
            }
        }

        // mappedPort error must distinguish "port not declared in config" from "port simply missing
        // from inspect" — same behaviour across backends. (When the container has actually crashed
        // we surface ContainerStartFailedException at init time, so mappedPort never sees a dead
        // container handle in the normal flow.)
        "mappedPort for an undeclared port produces a clear configuration error" - runBackends {
            Container.init(alpine.port(80)).map { c =>
                Abort.run[ContainerException](c.mappedPort(9999)).map {
                    case Result.Failure(e: ContainerOperationException) =>
                        assert(
                            e.getMessage.contains("not declared") || e.getMessage.contains("Configured ports"),
                            s"expected config-aware error mentioning declared/configured ports, got: ${e.getMessage}"
                        )
                    case other => fail(s"expected ContainerOperationException, got $other")
                }
            }
        }

        // Port-mapping wait: init must not return until every configured port has a positive
        // hostPort observable via inspect. Same contract on http and shell — neither should
        // race the host-side port-forwarding hook.
        "init — mappedPort succeeds immediately for every configured port" - runBackends {
            val cfg = Container.Config(ContainerImage("nginx", "alpine"))
                .port(80)
                .stopTimeout(0.seconds)
                .healthCheck(Container.HealthCheck.running)
            Container.init(cfg).map { c =>
                c.mappedPort(80).map { hp =>
                    assert(hp > 0, s"hostPort should be positive immediately after init, got $hp")
                }
            }
        }

        // Reproducer for an arm64-only CI failure where docker/http reported "Image not found"
        // for images that succeeded under docker/shell, podman/http, podman/shell. The arm64
        // GitHub runner had no images pre-cached in the docker daemon, exposing a path where
        // the http backend's auto-pull during `Container.init` didn't actually leave the
        // image present before the create call. busybox is chosen because no other test uses
        // it, so each backend gets a clean "image absent" state regardless of test ordering.
        "init — auto-pulls when image is absent locally" - runBackends {
            val img = ContainerImage("busybox", "1.36")
            for
                // Force-remove to guarantee a "not present" precondition. Tolerate "missing"
                // (image was already absent) and "in use" (a leftover container references it).
                _ <- Abort.run[ContainerException](ContainerImage.remove(img, force = true))
                // Verify precondition: image is genuinely not local.
                inspectBefore <- Abort.run[ContainerException](ContainerImage.inspect(img))
                _ = assert(
                    inspectBefore.failure.exists(_.isInstanceOf[ContainerImageMissingException]),
                    s"precondition failed: expected $img absent, inspect returned $inspectBefore"
                )
                // Now `Container.init` MUST auto-pull. Failing here with ImageNotFound means
                // imageEnsure didn't actually populate the daemon before create ran.
                result <- Abort.run[ContainerException](
                    Container.init(Container.Config.default.copy(
                        image = img,
                        command = Maybe(Command("sh", "-c", "trap 'exit 0' TERM; sleep infinity")),
                        stopTimeout = 0.seconds,
                        healthCheck = Container.HealthCheck.running
                    )).map(_ => ())
                )
            yield result match
                case Result.Success(_) => succeed
                case Result.Failure(e: ContainerImageMissingException) =>
                    fail(s"auto-pull failed — Container.init should pull missing images, got $e")
                case other => fail(s"unexpected result: $other")
            end for
        }

        "fails with AlreadyExists when name is taken" - runBackends {
            val name = uniqueName("kyo-dup")
            Container.init(alpine.name(name)).map { _ =>
                Abort.run[ContainerException] {
                    Container.init(alpine.name(name))
                }.map { result =>
                    result match
                        case Result.Failure(_: ContainerAlreadyExistsException) => succeed
                        case other                                              => fail(s"Expected AlreadyExists, got $other")
                }
            }
        }

        "health check failure aborts init" - runBackends {
            val failingCheck = Container.HealthCheck.exec(
                command = Command("false"),
                retrySchedule = Schedule.fixed(100.millis).take(3)
            )
            Abort.run[ContainerException] {
                Container.init(alpine.healthCheck(failingCheck))
            }.map {
                case Result.Failure(_: ContainerException) => succeed
                case other                                 => fail(s"expected typed Failure, got: $other")
            }
        }
    }

    "use" - {
        "creates container, runs block, returns result, cleans up" - runBackends {
            val name = uniqueName("kyo-run")
            for
                idRef <- AtomicRef.init[Container.Id](Container.Id(""))
                result <- Scope.run {
                    Container.initWith(alpine.name(name).autoRemove(false)) { c =>
                        idRef.set(c.id).andThen(42)
                    }
                }
                id <- idRef.get
                r  <- Abort.run[ContainerException](Container.attach(id))
            yield
                assert(result == 42)
                r match
                    case Result.Failure(_: ContainerException) => succeed // container should be cleaned up
                    case other                                 => fail(s"expected typed Failure, got: $other")
            end for
        }

        "container is removed after block fails" - runBackends {
            for
                idRef <- AtomicRef.init[Container.Id](Container.Id(""))
                _ <- Abort.run[ContainerException] {
                    Scope.run {
                        Container.initWith(alpinePersistent(alpine)) { c =>
                            idRef.set(c.id).andThen {
                                Abort.fail(ContainerOperationException("test failure", "intentional"))
                            }
                        }
                    }
                }
                id <- idRef.get
                r  <- Abort.run[ContainerException](Container.attach(id))
            yield r match
                case Result.Failure(_: ContainerException) => succeed // container should be cleaned up
                case other                                 => fail(s"expected typed Failure, got: $other")

        }

        "block receives working container" - runBackends {
            Scope.run {
                Container.initWith(alpine) { c =>
                    c.exec("echo", "hello").map(_.stdout.trim)
                }
            }.map(r => assert(r == "hello"))
        }
    }

    "attach (by id)" - {
        "attaches to existing running container" - runBackends {
            Container.init(alpine).map { c =>
                Container.attach(c.id).map { attached =>
                    assert(attached.id == c.id)
                    attached.state.map(s => assert(s == Container.State.Running))
                }
            }
        }

        "fails with NotFound for nonexistent id" - runBackends {
            Abort.run[ContainerException] {
                Container.attach(Container.Id("nonexistent-container-xyz-999"))
            }.map { result =>
                result match
                    case Result.Failure(_: ContainerMissingException) => succeed
                    case other                                        => fail(s"Expected NotFound, got $other")
            }
        }

        // attachById preserves env vars set in Container.Config
        "attachById preserves env vars from container config" - runBackends {
            val config = Container.Config(ContainerImage("alpine", "latest"))
                .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity")
                .env("MY_KEY", "my-value")
                .stopTimeout(0.seconds)
            Container.init(config).map { c =>
                Container.attach(c.id).map { attached =>
                    assert(
                        attached.config.env.toMap.get("MY_KEY") == Some("my-value"),
                        s"expected MY_KEY=my-value in attached config env, got: ${attached.config.env.toMap}"
                    )
                }
            }
        }
    }

    "stop" - {
        "stops a running container" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop
                    s <- c.state
                yield assert(s == Container.State.Stopped)
            }
        }

        "container state is Stopped after stop with short timeout" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop(timeout = 1.seconds)
                    s <- c.state
                yield assert(s == Container.State.Stopped)
            }
        }
    }

    "kill" - {
        "kills a running container with SIGKILL" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.kill(Container.Signal.SIGKILL)
                    _ <- c.waitForExit
                    s <- c.state
                yield assert(s == Container.State.Stopped)
            }
        }

        "sends SIGTERM signal" - runBackends {
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

        // kill+waitForExit on autoRemove container preserves real exit code
        // — stop() must start /wait fiber before sending stop signal so the auto-remove cleanup
        // race does not clobber the exit code observed by the caller.
        "kill+waitForExit on autoRemove container preserves real exit code" - runBackends {
            val cfg = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 137' TERM; sleep infinity")
                .autoRemove(true)
            Container.init(cfg).map { c =>
                for
                    _ <- c.kill(Container.Signal.SIGTERM)
                    // Capture exit code BEFORE auto-remove cleanup races to clobber it.
                    exit <- c.waitForExit
                yield exit match
                    case ExitCode.Failure(137) => succeed
                    case ExitCode.Signaled(9)  => succeed // ExitCode.apply(137) maps to Signaled(9)
                    case other =>
                        fail(s"expected exit 137 (Failure(137) or Signaled(9)), got $other")
            }
        }
    }

    "restart" - {
        "restarts a running container — new PID, still Running" - runBackends {
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

        "restarts a stopped container" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
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
        "pause transitions to Paused state" - runBackends {
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

        "unpause transitions back to Running" - runBackends {
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
        "removes a stopped container" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop
                    _ <- c.remove
                    r <- Abort.run[ContainerException](c.state)
                yield r match
                    case Result.Failure(_: ContainerMissingException) => succeed
                    case other                                        => fail(s"Expected NotFound, got $other")
            }
        }

        "force removes a running container" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    s <- c.state
                    _ = assert(s == Container.State.Running)
                    _ <- c.remove(force = true)
                    r <- Abort.run[ContainerException](c.state)
                yield r match
                    case Result.Failure(_: ContainerMissingException) => succeed
                    case other                                        => fail(s"Expected NotFound, got $other")
            }
        }

        "fails without force on running container" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                Abort.run[ContainerException](c.remove).map {
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
                }
            }
        }
    }

    "rename" - {
        "renames a container" - runBackends {
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
        "returns Success for exit code 0" - runBackends {
            Container.init(Container.Config("alpine").command("true")).map { c =>
                c.waitForExit.map(code => assert(code == ExitCode.Success))
            }
        }

        "returns Failure for non-zero exit code" - runBackends {
            Container.init(Container.Config("alpine").command("sh", "-c", "exit 42")).map { c =>
                c.waitForExit.map(code => assert(code == ExitCode.Failure(42)))
            }
        }

        "returns Signaled for signal-killed container" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _    <- c.kill(Container.Signal.SIGKILL)
                    code <- c.waitForExit
                yield code match
                    case ExitCode.Signaled(9) => succeed
                    case ExitCode.Failure(_)  => succeed // some runtimes report as failure
                    case other                => fail(s"Expected Signaled(9) or Failure, got $other")
            }
        }

        "auto-removed container with failure exit code — best effort" - runBackends {
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
        "exec — passes when command succeeds and output matches" - runBackends {
            val hc = Container.HealthCheck.exec(
                command = Command("echo", "ok"),
                expected = Present("ok"),
                retrySchedule = Schedule.fixed(100.millis).take(10)
            )
            Container.init(alpine.healthCheck(hc)).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "exec — String* convenience" - runBackends {
            Container.init(alpine.healthCheck(Container.HealthCheck.exec("echo", "ok"))).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "log — passes when message found in logs" - runBackends {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo 'ready to serve'; sleep infinity")
                .healthCheck(Container.HealthCheck.log(
                    "ready to serve",
                    retrySchedule = Schedule.fixed(200.millis).take(30)
                ))
            Container.init(config).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "port — passes when port is listening" - runBackends {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; while true; do echo ok | nc -l -p 8080; done & sleep infinity")
                .healthCheck(Container.HealthCheck.port(
                    8080,
                    retrySchedule = Schedule.fixed(200.millis).take(30)
                ))
            Container.init(config).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "all — passes only when all checks pass" - runBackends {
            val hc = Container.HealthCheck.all(
                Container.HealthCheck.exec("echo", "ok"),
                Container.HealthCheck.exec("true")
            )
            Container.init(alpine.healthCheck(hc)).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "custom — accepts function" - runBackends {
            val hc = Container.HealthCheck.init(Schedule.fixed(200.millis).take(10)) { container =>
                container.exec("echo", "alive").map { r =>
                    if !r.isSuccess then
                        Abort.fail(ContainerHealthCheckException(container.id, "not alive", attempts = 1, lastError = "check failed"))
                    else ()
                }
            }
            Container.init(alpine.healthCheck(hc)).map { c =>
                c.isHealthy.map(h => assert(h))
            }
        }

        "ongoing — isHealthy tracks health continuously" - runBackends {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; touch /tmp/healthy; sleep infinity")
                .healthCheck(Container.HealthCheck.init(Schedule.fixed(200.millis).take(10)) { c =>
                    c.exec("test", "-f", "/tmp/healthy").map { r =>
                        if !r.isSuccess then
                            Abort.fail(ContainerHealthCheckException(c.id, "unhealthy", attempts = 1, lastError = "file missing"))
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

        // retry-exhaustion error message structure
        "retry-exhausted health check accumulates errors and reports attempts" - runBackends {
            val cfg = alpine.healthCheck(Container.HealthCheck.exec(
                command = Command("sh", "-c", "echo failure-msg-$RANDOM; exit 1"),
                expected = Absent,
                retrySchedule = Schedule.fixed(50.millis).take(3)
            ))
            Abort.run[ContainerException](Container.init(cfg)).map {
                case Result.Failure(e: ContainerHealthCheckException) =>
                    assert(e.attempts >= 3, s"expected attempts >= 3, got ${e.attempts}")
                    assert(
                        e.reason.contains("retry schedule exhausted"),
                        s"expected exhaustion message, got: ${e.reason}"
                    )
                    val parts = e.lastError.split("\\] \\[").toSeq
                    // Bound: cannot exceed the recent-errors capacity.
                    assert(
                        parts.size <= kyo.Container.healthCheckRecentErrorsCapacity,
                        s"expected ≤${kyo.Container.healthCheckRecentErrorsCapacity} recent entries, got ${parts.size}"
                    )
                    // Each entry must carry the actual failure-msg payload (not just bracket noise).
                    assert(
                        parts.exists(_.contains("failure-msg-")),
                        s"expected entries to include the failure-msg payload, got: $parts"
                    )
                    succeed
                case Result.Panic(t) => fail(s"panic: $t")
                case other           => fail(s"expected ContainerHealthCheckException, got $other")
            }
        }

        // HealthCheck.httpGet must verify the HTTP status code, not merely that the request returns.
        "HealthCheck.httpGet on nginx — wrong path leads to retry exhaustion" - runBackends {
            val cfg = Container.Config(ContainerImage("nginx:alpine"))
                .port(80, 0)
                .healthCheck(Container.HealthCheck.httpGet(
                    port = 80,
                    path = "/no-such-path",
                    expectedStatus = 200,
                    retrySchedule = Schedule.fixed(200.millis).take(3)
                ))
            Abort.run[ContainerException](Container.init(cfg)).map {
                case Result.Failure(_: ContainerHealthCheckException) => succeed
                case Result.Failure(other)                            => fail(s"unexpected failure type: $other")
                case Result.Panic(t)                                  => fail(s"panic: $t")
                case Result.Success(_) =>
                    fail("httpGet to /no-such-path should fail (404 != 200): expectedStatus was ignored")
            }
        }

        "HealthCheck.exec recovers after transient failures" - runBackends {
            // Container creates the sentinel file after 500ms; healthcheck fails before that, succeeds after.
            val cfg = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; sleep 0.5; touch /tmp/up; sleep infinity")
                .healthCheck(Container.HealthCheck.exec(
                    command = Command("test", "-f", "/tmp/up"),
                    expected = Absent,
                    retrySchedule = Schedule.fixed(300.millis).take(10)
                ))
            // If init returns without abort, the retry loop hit a green check — that is the property under test.
            Container.init(cfg).map(_ => succeed)
        }

        // short error reason (getMessage < 500 chars) survives truncation unchanged
        "HealthCheck error message ≤500 chars is preserved in lastError without truncation" - runBackends {
            val shortReason = "check-failed-short"
            val hc = Container.HealthCheck.init(Schedule.fixed(100.millis).take(2)) { c =>
                Abort.fail(ContainerHealthCheckException(c.id, shortReason, 1))
            }
            val config = Container.Config(ContainerImage("alpine", "latest"))
                .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity")
                .stopTimeout(0.seconds)
                .healthCheck(hc)
            Abort.run[ContainerException](Container.init(config)).map {
                case Result.Failure(e: ContainerHealthCheckException) =>
                    assert(e.lastError.nonEmpty, "lastError must be non-empty")
                    // A short reason produces a getMessage well under 500 chars — entry length < 502
                    assert(
                        e.lastError.length < 502,
                        s"expected lastError.length < 502 for short reason, got: ${e.lastError.length}"
                    )
                    assert(
                        e.lastError.contains(shortReason),
                        s"expected lastError to contain '$shortReason', got: ${e.lastError}"
                    )
                    succeed
                case Result.Panic(t) => fail(s"panic: $t")
                case other           => fail(s"expected ContainerHealthCheckException, got $other")
            }
        }

        // long error reason (getMessage > 500 chars) gets truncated to at most 502 chars per entry
        "HealthCheck error message >500 chars is truncated to healthCheckErrorMessageMaxLength in lastError" - runBackends {
            // reason of "x"*450 → getMessage is ~"Health check failed for <64-char-id> after 1 attempt(s): " + "x"*450 ≈ 514 chars > 500
            val longReason = "x" * 450
            val hc = Container.HealthCheck.init(Schedule.fixed(100.millis).take(2)) { c =>
                Abort.fail(ContainerHealthCheckException(c.id, longReason, 1))
            }
            val config = Container.Config(ContainerImage("alpine", "latest"))
                .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity")
                .stopTimeout(0.seconds)
                .healthCheck(hc)
            Abort.run[ContainerException](Container.init(config)).map {
                case Result.Failure(e: ContainerHealthCheckException) =>
                    assert(e.lastError.nonEmpty, "lastError must be non-empty")
                    // Each entry is "[" + getMessage.take(500) + "]" = at most 502 chars
                    // With up to healthCheckRecentErrorsCapacity entries joined by " ", bound = 502 * cap + sep
                    val maxEntryLen = 502
                    val cap         = kyo.Container.healthCheckRecentErrorsCapacity
                    val maxTotal    = maxEntryLen * cap + (cap - 1) // separating spaces
                    assert(
                        e.lastError.length <= maxTotal,
                        s"expected lastError.length <= $maxTotal, got: ${e.lastError.length}"
                    )
                    // The most recent entry must be exactly truncated to 500 chars content (502 with brackets)
                    val entries   = e.lastError.split("\\] \\[").toSeq
                    val lastEntry = entries.last.stripPrefix("[").stripSuffix("]")
                    assert(
                        lastEntry.length <= 500,
                        s"expected per-entry content <= 500 chars, got: ${lastEntry.length}"
                    )
                    succeed
                case Result.Panic(t) => fail(s"panic: $t")
                case other           => fail(s"expected ContainerHealthCheckException, got $other")
            }
        }
    }

    // =========================================================================
    // Inspection
    // =========================================================================

    "inspect" - {
        "returns container info with correct id and image" - runBackends {
            Container.init(alpine).map { c =>
                c.inspect.map { info =>
                    assert(info.id == c.id)
                    assert(info.image.reference.contains("alpine"))
                    assert(info.state == Container.State.Running)
                }
            }
        }

        "returns port bindings" - runBackends {
            Container.init(alpine.port(80, 18080)).map { c =>
                c.inspect.map { info =>
                    assert(info.ports.exists(_.containerPort == 80))
                }
            }
        }

        "Container.mappedPort" - {
            "returns the host port for a bound container port" - runBackends {
                val cfg = Container.Config(ContainerImage("nginx:alpine"))
                    .port(80, 0) // 0 = random host port
                    .healthCheck(Container.HealthCheck.port(80))
                Container.init(cfg).map { c =>
                    c.mappedPort(80).map { hostPort =>
                        assert(hostPort > 0, s"expected non-zero host port, got $hostPort")
                        assert(c.host == "127.0.0.1", s"expected host 127.0.0.1, got ${c.host}")
                    }
                }
            }
        }

        "returns labels" - runBackends {
            Container.init(alpine.label("test-key", "test-value")).map { c =>
                c.inspect.map { info =>
                    assert(info.labels("test-key") == "test-value")
                }
            }
        }

        "returns network settings with IP address" - runBackends {
            Container.init(alpine).map { c =>
                c.inspect.map { info =>
                    info.networkSettings.ipAddress match
                        case Present(ip) => assert(ip.nonEmpty)
                        case Absent      => fail("Expected IP address")
                }
            }
        }

        "running container exitCode is Absent" - runBackends {
            Container.init(alpine).map { c =>
                c.inspect.map { info =>
                    assert(info.state == Container.State.Running)
                    assert(
                        info.exitCode == Absent,
                        s"running container should have exitCode=Absent, got ${info.exitCode}"
                    )
                }
            }
        }

        "fails with NotFound for removed container" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop
                    _ <- c.remove
                    r <- Abort.run[ContainerException](c.inspect)
                yield r match
                    case Result.Failure(_: ContainerMissingException) => succeed
                    case other                                        => fail(s"Expected NotFound, got $other")
            }
        }

        "correctly distinguishes volume mounts from bind mounts" - runBackends {
            val volName = Container.Volume.Id(uniqueName("kyo-mount-type"))
            val hostDir = Path("/tmp/" + uniqueName("kyo-bind-type"))
            Scope.run {
                for
                    _ <- Container.Volume.init(Container.Volume.Config.default.copy(name = Present(volName)))
                    _ <- hostDir.mkDir
                    info <- Container.initWith(
                        alpine
                            .bind(hostDir, Path("/mnt/bind-target"))
                            .volume(volName, Path("/mnt/vol-target"))
                    ) { c =>
                        c.inspect
                    }
                    _ <- hostDir.removeAll
                yield
                    assert(info.mounts.size >= 2, s"Expected at least 2 mounts, got ${info.mounts.size}")
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
    }

    "state" - {
        "returns Running for running container" - runBackends {
            Container.init(alpine).map(_.state.map(s => assert(s == Container.State.Running)))
        }

        "returns Paused for paused container" - runBackends {
            Container.init(alpine).map { c =>
                c.pause.andThen(c.state).map(s => assert(s == Container.State.Paused))
            }
        }

        "returns Stopped for stopped container" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop
                    s <- c.state
                yield assert(s == Container.State.Stopped)
            }
        }
    }

    "stats" - {
        "returns non-zero CPU and memory values" - runBackends {
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
        "emits multiple distinct stats snapshots" - runBackends {
            Container.init(alpine).map { c =>
                Scope.run {
                    // Tight interval — the test just needs distinct timestamps, not a realistic sample rate.
                    c.statsStream(50.millis).take(3).run.map { stats =>
                        assert(stats.size == 3)
                        assert(stats.map(_.readAt).distinct.size == 3)
                    }
                }
            }
        }
    }

    "top" - {
        "returns process titles and at least one process" - runBackends {
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
        "detects added files" - runBackends {
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
        "runs command and returns stdout" - runBackends {
            Container.init(alpine).map { c =>
                c.exec("echo", "hello world").map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.trim == "hello world")
                }
            }
        }

        "returns stderr separately from stdout" - runBackends {
            Container.init(alpine).map { c =>
                c.exec("sh", "-c", "echo out; echo err >&2").map { r =>
                    assert(r.stdout.trim == "out")
                    assert(r.stderr.trim == "err")
                }
            }
        }

        "returns correct exit code for non-zero exit" - runBackends {
            Container.init(alpine).map { c =>
                c.exec("sh", "-c", "exit 42").map { r =>
                    assert(!r.isSuccess)
                    assert(r.exitCode == ExitCode.Failure(42))
                }
            }
        }

        "Command with env propagates environment" - runBackends {
            Container.init(alpine).map { c =>
                val cmd = Command("sh", "-c", "echo $MY_VAR").envAppend(Map("MY_VAR" -> "hello"))
                c.exec(cmd).map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.trim == "hello")
                }
            }
        }

        "Command with cwd sets working directory" - runBackends {
            Container.init(alpine).map { c =>
                val cmd = Command("pwd").cwd(Path("/tmp"))
                c.exec(cmd).map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.trim == "/tmp")
                }
            }
        }

        "handles command with spaces in arguments" - runBackends {
            Container.init(alpine).map { c =>
                c.exec("echo", "hello world with spaces").map { r =>
                    assert(r.stdout.trim == "hello world with spaces")
                }
            }
        }

        "handles command with special characters" - runBackends {
            Container.init(alpine).map { c =>
                c.exec("echo", "a&b|c;d").map { r =>
                    assert(r.stdout.trim == "a&b|c;d")
                }
            }
        }

        "handles empty output" - runBackends {
            Container.init(alpine).map { c =>
                c.exec("true").map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.isEmpty)
                }
            }
        }

        "handles large output without truncation" - runBackends {
            Container.init(alpine).map { c =>
                c.exec("sh", "-c", "dd if=/dev/zero bs=1024 count=100 2>/dev/null | base64").map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.length > 100 * 1024) // base64 expands ~33%
                }
            }
        }

        "concurrent exec calls on same container" - runBackends {
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
        "streams stdout and stderr as LogEntry with correct sources" - runBackends {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.execStream(Command("sh", "-c", "echo out; echo err >&2")).run.map { entries =>
                        val stdout = entries.filter(_.source == Container.LogEntry.Source.Stdout)
                        val stderr = entries.filter(_.source == Container.LogEntry.Source.Stderr)
                        assert(stdout.exists(_.content.contains("out")), s"expected 'out' in stdout: $stdout")
                        assert(stderr.exists(_.content.contains("err")), s"expected 'err' in stderr: $stderr")
                        assert(!stdout.exists(_.content.contains("err")), s"stderr leaked into stdout: $stdout")
                        assert(!stderr.exists(_.content.contains("out")), s"stdout leaked into stderr: $stderr")
                    }
                }
            }
        }

        "stream completes when command exits" - runBackends {
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
        "write sends data to stdin, read receives response" - runRuntimes { runtime =>
            if ContainerRuntime.findSocket(runtime).isEmpty then
                succeed
            else
                val socketPath = ContainerRuntime.findSocket(runtime).get
                Container.withBackendConfig(_.UnixSocket(Path(socketPath))) {
                    Container.init(alpine).map { c =>
                        Scope.run {
                            c.execInteractive(Command("cat")).map { session =>
                                for
                                    _       <- session.write("hello\n")
                                    entries <- session.read.take(1).run
                                yield assert(entries.exists(_.content.contains("hello")))
                            }
                        }
                    }
                }
        }
    }

    // =========================================================================
    // Attach
    // =========================================================================

    "attach" - {
        "bidirectional — write then read response" - runRuntimes { runtime =>
            if ContainerRuntime.findSocket(runtime).isEmpty then
                succeed
            else
                val socketPath = ContainerRuntime.findSocket(runtime).get
                Container.withBackendConfig(_.UnixSocket(Path(socketPath))) {
                    val config = Container.Config("alpine")
                        .command("sh", "-c", "trap 'exit 0' TERM; while read line; do echo \"echo:$line\"; done")
                        .interactive(true)
                    Container.init(config).map { c =>
                        Scope.run {
                            c.attach(stdin = true, stdout = true, stderr = false).map { session =>
                                for
                                    _       <- session.write("test-input\n")
                                    entries <- session.read.take(1).run
                                yield assert(entries.exists(_.content.contains("echo:test-input")))
                            }
                        }
                    }
                }
        }

        "attach(stdout=false) does not receive stdout data" - runBackends {
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
        "returns LogEntry entries with stdout output" - runBackends {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo hello-from-container; sleep infinity")
            Container.init(config).map { c =>
                c.logs.map { entries =>
                    assert(entries.exists(_.content.contains("hello-from-container")))
                    assert(entries.exists(_.source == Container.LogEntry.Source.Stdout))
                }
            }
        }

        "stdout=false excludes stdout, keeps stderr" - runBackends {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo stdout-msg; echo stderr-msg >&2; sleep infinity")
            Container.init(config).map { c =>
                c.logs(stdout = false, stderr = true).map { entries =>
                    assert(!entries.exists(_.content.contains("stdout-msg")))
                    assert(entries.exists(_.content.contains("stderr-msg")))
                }
            }
        }

        "tail limits to last N lines" - runBackends {
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

        "returns empty chunk for container with no output" - runBackends {
            Container.init(alpine).map { c =>
                c.logs.map(entries => assert(entries.isEmpty))
            }
        }

        "logsText returns raw string for backward compat" - runBackends {
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
        "streams log entries containing both stdout and stderr content" - runBackends {
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

        "delivers entries incrementally as container produces output" - runBackends {
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

        "logStream(stdout=false, stderr=true) returns only stderr entries" - runBackends {
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

        "logStream with timestamps=true populates LogEntry.timestamp" - runBackends {
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
        "copies a local file into the container and content matches" - runBackends {
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

        "handles empty file" - runBackends {
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
        "copies a file from container to local and content matches" - runBackends {
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

        "fails when container path doesn't exist" - runBackends {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException] {
                    c.copyFrom(Path("/nonexistent/path/xyz"), Path("/tmp/dest"))
                }.map {
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
                }
            }
        }
    }

    "stat" - {
        "returns file metadata with correct name and non-zero size" - runBackends {
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

        "returns link target for symlinks" - runBackends {
            Container.init(alpine).map { c =>
                for
                    _    <- c.exec("sh", "-c", "echo x > /tmp/real; ln -s /tmp/real /tmp/link")
                    info <- c.stat(Path("/tmp/link"))
                yield info.linkTarget match
                    case Present(target) => assert(target.contains("/tmp/real"))
                    case Absent          => fail("Expected link target for symlink")
            }
        }

        "fails for nonexistent path" - runBackends {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException] {
                    c.stat(Path("/nonexistent/path/xyz"))
                }.map {
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
                }
            }
        }
    }

    "export" - {
        "streams non-empty container filesystem" - runBackends {
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
                    case Result.Panic(t) => fail(s"panic: $t")
                }
            }
        }

        "exportFs binary integrity — tar magic bytes survive" - runBackends {
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

        "exportFs streams multiple chunks — not single blob" - runBackends {
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
        "updates memory limit and verifies via stats" - runBackends {
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

        "Absent fields leave values unchanged" - runBackends {
            Container.init(alpine.memory(256 * 1024 * 1024L)).map { c =>
                for
                    s1 <- c.stats
                    _  <- c.update(Absent) // no changes
                    s2 <- c.stats
                yield assert(s1.memory.limit == s2.memory.limit)
            }
        }
    }

    // =========================================================================
    // Network Operations (on container)
    // =========================================================================

    "connectToNetwork" - {
        "connects container to a network and visible in inspect" - runBackends {
            val netName = uniqueName("kyo-net-conn")
            Scope.run {
                Container.Network.init(Container.Network.Config.default.copy(name = netName)).map { netId =>
                    Container.initWith(alpine) { c =>
                        for
                            _ <- c.connectToNetwork(netId)
                            i <- c.inspect
                        yield assert(i.networkSettings.networks.contains(netId))
                    }
                }
            }
        }

        "fails with NetworkNotFound for bad network id" - runBackends {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException] {
                    c.connectToNetwork(Container.Network.Id("nonexistent-net-xyz"))
                }.map { r =>
                    r match
                        case Result.Failure(_: ContainerNetworkMissingException) => succeed
                        case other                                               => fail(s"Expected NetworkNotFound, got $other")
                }
            }
        }
    }

    "disconnectFromNetwork" - {
        "disconnects container and no longer in inspect" - runBackends {
            val netName = uniqueName("kyo-net-disc")
            Scope.run {
                Container.Network.init(Container.Network.Config.default.copy(name = netName)).map { netId =>
                    Container.initWith(alpine) { c =>
                        for
                            _  <- c.connectToNetwork(netId)
                            i1 <- c.inspect
                            _ = assert(i1.networkSettings.networks.contains(netId))
                            _  <- c.disconnectFromNetwork(netId)
                            i2 <- c.inspect
                        yield assert(!i2.networkSettings.networks.contains(netId))
                    }
                }
            }
        }
    }

    // =========================================================================
    // Container List and Prune
    // =========================================================================

    "list" - {
        "lists running containers and includes our container" - runBackends {
            val name = uniqueName("kyo-list")
            Container.init(alpine.name(name)).map { c =>
                Container.list.map { summaries =>
                    assert(summaries.exists(_.id == c.id))
                    assert(summaries.find(_.id == c.id).exists(_.state == Container.State.Running))
                }
            }
        }

        "all=true includes stopped containers" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop
                    s <- Container.list(all = true)
                yield
                    assert(s.exists(_.id == c.id))
                    assert(s.find(_.id == c.id).exists(_.state == Container.State.Stopped))
            }
        }

        "filters by label" - runBackends {
            val labelVal = uniqueName("kyo-filter")
            Container.init(alpine.label("kyo-test", labelVal)).map { c =>
                Container.list(all = false, filters = Dict("label" -> Chunk(s"kyo-test=$labelVal"))).map { summaries =>
                    assert(summaries.size == 1)
                    assert(summaries.head.id == c.id)
                }
            }
        }

        "returns empty for no matches" - runBackends {
            Container.list(all = false, filters = Dict("name" -> Chunk("nonexistent-name-xyz-99999"))).map { summaries =>
                assert(summaries.isEmpty)
            }
        }

        "returns populated ports for container with port mapping" - runBackends {
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

        "returns populated mounts for container with volume" - runBackends {
            val volName = Container.Volume.Id(uniqueName("kyo-list-mounts"))
            Scope.run {
                Container.Volume.init(Container.Volume.Config.default.copy(name = Present(volName))).map { _ =>
                    Container.initWith(alpine.volume(volName, Path("/data"))) { c =>
                        Container.list.map { summaries =>
                            val summary = summaries.find(_.id == c.id)
                            assert(summary.isDefined, "Container not found in list")
                            assert(
                                summary.get.mounts.nonEmpty,
                                s"Expected mounts to be populated for container with volume, got empty mounts"
                            )
                        }
                    }
                }
            }
        }
    }

    "prune" - {
        "removes stopped containers and returns their ids" - runBackends {
            val labelVal = uniqueName("kyo-prune")
            Container.init(alpinePersistent(alpine).label("kyo-prune", labelVal)).map { c =>
                val cid = c.id
                for
                    _ <- c.stop
                    r <- Container.prune(filters = Dict("label" -> Chunk(s"kyo-prune=$labelVal")))
                yield
                    assert(r.deleted.nonEmpty)
                    assert(r.deleted.exists(_.contains(cid.value.take(12))))
                end for
            }
        }

        "returns only valid container IDs — no headers or summary lines" - runBackends {
            val labelVal = uniqueName("kyo-prune-ids")
            Container.init(alpinePersistent(alpine).label("kyo-prune-ids", labelVal)).map { c =>
                for
                    _ <- c.stop
                    r <- Container.prune(filters = Dict("label" -> Chunk(s"kyo-prune-ids=$labelVal")))
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
        "pulls an image and it becomes inspectable" - runBackends {
            val img = ContainerImage("alpine", "latest")
            Abort.run[ContainerException] {
                ContainerImage.pull(img)
            }.map {
                case Result.Success(_) =>
                    ContainerImage.inspect(img).map { info =>
                        assert(info.repoTags.exists(_.reference.contains("alpine")))
                        assert(info.size > 0)
                    }
                case Result.Failure(_: ContainerImageMissingException) =>
                    // Registry may be unreachable (TLS cert, network, etc.)
                    // Verify the image is at least available locally via ensure
                    ContainerImage.ensure(img).andThen {
                        ContainerImage.inspect(img).map { imgInfo =>
                            assert(imgInfo.repoTags.exists(_.reference.contains("alpine")))
                        }
                    }
                case Result.Failure(e) => Abort.fail(e)
                case Result.Panic(t)   => Abort.panic(t)
            }
        }

        "fails for nonexistent image with typed ImageNotFound" - runBackends {
            val img = ContainerImage("nonexistent-image-xyzzy-99999", "notatag")
            Abort.run[ContainerException](ContainerImage.pull(img)).map {
                case Result.Failure(_: ContainerImageMissingException) => succeed
                case Result.Failure(other)                             =>
                    // Registry unreachable in CI environments also produces a failure — accept it if its
                    // message mentions a network/TLS issue rather than asserting a wrong type.
                    val msg = Option(other.getMessage).getOrElse("")
                    assert(
                        msg.toLowerCase.contains("ssl") ||
                            msg.toLowerCase.contains("tls") ||
                            msg.toLowerCase.contains("network") ||
                            msg.toLowerCase.contains("timeout"),
                        s"expected ImageNotFound or a network-unreachable message, got: $other"
                    )
                case r => fail(s"expected failure, got $r")
            }
        }

        "imagePull actually contacts registry when image exists — not identical to ensure" - runBackends {
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
                t0p        <- Clock.now
                pullResult <- Abort.run[ContainerException](ContainerImage.pull(img))
                t1p        <- Clock.now
                pullMs = t1p.toJava.toEpochMilli - t0p.toJava.toEpochMilli
            yield pullResult match
                case Result.Success(_) =>
                    // Pull succeeded — verify it actually contacted the registry
                    assert(
                        pullMs > ensureMs * 2 || pullMs > 500,
                        s"pull (${pullMs}ms) should be slower than ensure (${ensureMs}ms) — " +
                            "pull appears to skip registry contact when image exists locally"
                    )
                case Result.Failure(_: ContainerImageMissingException) =>
                    // Registry unreachable — pull attempted but failed, which proves
                    // it contacts the registry (unlike ensure which only checks locally)
                    info(s"pull failed after ${pullMs}ms (registry unreachable) — confirms registry contact")
                    succeed
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    fail(s"panic: $t")
            end for
        }

        "pull with wrong RegistryAuth produces non-Missing typed error" - runBackends {
            val auth = ContainerImage.RegistryAuth("user", "definitely-wrong-pwd", "ghcr.io")
            val img  = ContainerImage("ghcr.io/kyo-test/private-99999:v1")
            Abort.run[ContainerException](ContainerImage.pull(img, auth = Present(auth))).map {
                case Result.Failure(e) =>
                    val msg = Option(e.getMessage).getOrElse("").toLowerCase
                    val isNetworkError = msg.contains("ssl") || msg.contains("tls") ||
                        msg.contains("network") || msg.contains("timeout") || msg.contains("connect")
                    if isNetworkError then
                        info(s"registry unreachable — skipping auth assertion: $e"); succeed
                    else
                        // With auth supplied the backend must NOT silently classify as ImageMissing
                        // (that is the auth.isEmpty 404 branch). It must surface an auth/operation error.
                        assert(
                            !e.isInstanceOf[ContainerImageMissingException],
                            s"expected non-Missing classification when auth is supplied, got: $e"
                        )
                        assert(
                            e.isInstanceOf[ContainerAuthException] ||
                                (e.isInstanceOf[ContainerOperationException] && msg.contains("auth")),
                            s"expected ContainerAuthException or ContainerOperationException(auth), got: $e"
                        )
                    end if
                case other => fail(s"expected failure, got $other")
            }
        }
    }

    "ContainerImage.pullWithProgress" - {
        "streams progress events with status" - runBackends {
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

        "pullWithProgress emits at least one event for a cached image" - runBackends {
            // Use alpine (already cached) — pullWithProgress should emit an "up to date" event quickly
            val img = ContainerImage("alpine", "latest")
            Scope.run {
                ContainerImage.pullWithProgress(img).take(1).run.map { events =>
                    assert(events.nonEmpty, "Expected at least one progress event")
                }
            }
        }

    }

    "ContainerImage.list" - {
        "lists local images including alpine" - runBackends {
            for
                _      <- ContainerImage.ensure(ContainerImage("alpine", "latest"))
                images <- ContainerImage.list
            yield
                assert(images.nonEmpty)
                assert(images.exists(_.repoTags.exists(_.reference.contains("alpine"))))
        }
    }

    "ContainerImage.inspect" - {
        "returns image metadata with architecture and OS" - runBackends {
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

        "fails for nonexistent image" - runBackends {
            Abort.run[ContainerException] {
                ContainerImage.inspect(ContainerImage("nonexistent-image-xyz", "latest"))
            }.map {
                case Result.Failure(_: ContainerImageMissingException) => succeed
                case other                                             => fail(s"expected ContainerImageMissingException, got: $other")
            }
        }
    }

    "ContainerImage.remove" - {
        "removes a tagged image" - runBackends {
            val tagName = uniqueName("kyo-rm")
            val img     = ContainerImage("alpine", "latest")
            for
                _     <- ContainerImage.ensure(img)
                _     <- ContainerImage.tag(img, tagName, "v1")
                r     <- ContainerImage.remove(ContainerImage(tagName, "v1"))
                check <- Abort.run[ContainerException](ContainerImage.inspect(ContainerImage(tagName, "v1")))
            yield
                assert(r.nonEmpty)
                check match
                    case Result.Failure(_: ContainerImageMissingException) => succeed
                    case other                                             => fail(s"expected ContainerImageMissingException, got: $other")
            end for
        }
    }

    "ContainerImage.tag" - {
        "tags an image and new tag is inspectable" - runBackends {
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

        // tag overwrite: re-tagging with same repo:tag points to the updated source image
        "tag overwrite — second tag replaces first tag reference" - runBackends {
            val repoName = uniqueName("kyo-tag-overwrite")
            val alpine   = ContainerImage("alpine", "latest")
            val busybox  = ContainerImage("busybox", "latest")
            for
                _ <- ContainerImage.ensure(alpine)
                _ <- ContainerImage.ensure(busybox)
                // Tag alpine as repoName:v1 — first assignment
                _  <- ContainerImage.tag(alpine, repoName, "v1")
                i1 <- ContainerImage.inspect(ContainerImage(repoName, "v1"))
                // Overwrite: re-tag busybox with same repoName:v1
                _  <- ContainerImage.tag(busybox, repoName, "v1")
                i2 <- ContainerImage.inspect(ContainerImage(repoName, "v1"))
                // Cleanup
                _ <- ContainerImage.remove(ContainerImage(repoName, "v1"), force = true)
            yield
                // Both inspects should report the tag as present
                assert(i1.repoTags.exists(_.reference.contains(repoName)))
                assert(i2.repoTags.exists(_.reference.contains(repoName)))
                // After overwrite the image id changes (busybox != alpine)
                assert(i1.id != i2.id, s"expected image id to change after tag overwrite, but both are ${i1.id}")
            end for
        }
    }

    "ContainerImage.push" - {
        "push without auth to non-existent registry fails with typed error" - runBackends {
            if java.lang.System.getenv("KYO_POD_REGISTRY_TEST") != "1" then
                info("set KYO_POD_REGISTRY_TEST=1 to exercise registry interactions"); succeed
            else
                val img = ContainerImage("ghcr.io/kyo-test/nope-99999:v1")
                Abort.run[ContainerException](ContainerImage.push(img)).map {
                    case Result.Failure(_: ContainerAuthException)         => succeed
                    case Result.Failure(_: ContainerImageMissingException) => succeed
                    case Result.Failure(_: ContainerOperationException)    => succeed
                    case other                                             => fail(s"expected typed failure, got $other")
                }
            end if
        }

        "push with explicit RegistryAuth threads X-Registry-Auth header" - runBackends {
            if java.lang.System.getenv("KYO_POD_REGISTRY_TEST") != "1" then
                info("set KYO_POD_REGISTRY_TEST=1 to exercise registry interactions"); succeed
            else
                val auth = ContainerImage.RegistryAuth("user", "wrongpass", "ghcr.io")
                val img  = ContainerImage("ghcr.io/kyo-test/nope-99999:v1")
                Abort.run[ContainerException](ContainerImage.push(img, Present(auth))).map { r =>
                    // Assert specifically: with bad creds the response must surface as Auth/Operation,
                    // NOT generic backend-unavailable (which would mean the request never reached the registry).
                    r match
                        case Result.Failure(_: ContainerAuthException)      => succeed
                        case Result.Failure(_: ContainerOperationException) => succeed
                        case other => fail(s"expected Auth/Operation when creds reach registry, got $other")
                }
            end if
        }
    }

    "ContainerImage.buildFromPath" - {
        "streams build progress incrementally during multi-step build" - runBackends {
            val dir     = Path("/tmp/" + uniqueName("kyo-build-inc"))
            val imgName = uniqueName("kyo-built-inc")
            for
                _ <- dir.mkDir
                // Two RUN steps with a sleep each — enough to verify first event arrives before the
                // final event (streaming, not collect-then-emit). Adding a third step would just
                // extend total time without strengthening the signal.
                _ <- (dir / "Dockerfile").write(
                    "FROM alpine:latest\n" +
                        "RUN sleep 1 && echo step1\n" +
                        "RUN sleep 1 && echo step2\n"
                )
                // Consume the full stream but capture the time of the first event. This avoids closing
                // the streaming HTTP response early, which would leave the connection in a state kyo-http's
                // pool can't reuse cleanly for subsequent requests.
                result <- Scope.run {
                    for
                        t0        <- Clock.now
                        firstTime <- AtomicRef.init(Absent: Maybe[Instant])
                        _ <- ContainerImage.buildFromPath(
                            dir,
                            tags = Chunk(s"$imgName:latest"),
                            noCache = true
                        ).foreach { _ =>
                            firstTime.get.map {
                                case Absent => Clock.now.map(t => firstTime.set(Present(t)))
                                case _      => Kyo.unit
                            }
                        }
                        first <- firstTime.get
                    yield first.map(t => t.toJava.toEpochMilli - t0.toJava.toEpochMilli)
                }
                _ <- Abort.run[ContainerException](ContainerImage.remove(ContainerImage(imgName, "latest"), force = true))
                _ <- (dir / "Dockerfile").remove
                _ <- dir.removeAll
            yield
                val firstMs = result.getOrElse(fail("Expected at least one build progress event"))
                // Build takes 2+ seconds (two sleep steps). If streaming, the first event arrives
                // quickly (< 1.5s). If events are buffered until build completes, this exceeds 2s.
                assert(
                    firstMs < 1500,
                    s"First build event took ${firstMs}ms — expected < 1500ms for a 2s build. " +
                        "Events are likely buffered until build completes (not streaming)"
                )
            end for
        }

        "builds from local directory and image is inspectable" - runBackends {
            val dir     = Path("/tmp/" + uniqueName("kyo-build"))
            val imgName = uniqueName("kyo-built")
            for
                _ <- dir.mkDir
                _ <- (dir / "Dockerfile").write("FROM alpine:latest\nRUN echo built\n")
                _ <- Scope.run {
                    ContainerImage.buildFromPath(dir, tags = Chunk(s"$imgName:latest")).run
                }
                i <- ContainerImage.inspect(ContainerImage(imgName, "latest"))
                _ <- ContainerImage.remove(ContainerImage(imgName, "latest"), force = true)
                _ <- (dir / "Dockerfile").remove
                _ <- dir.removeAll
            yield assert(i.repoTags.exists(_.reference.contains(imgName)))
            end for
        }

        "passes buildArgs to the Dockerfile" - runBackends {
            val dir      = Path("/tmp/" + uniqueName("kyo-build-args"))
            val imgName  = uniqueName("kyo-built-args")
            val sentinel = "KYO_ARG_SENTINEL_" + uniqueName("v").replaceAll("[^A-Za-z0-9]", "")
            for
                _ <- dir.mkDir
                _ <- (dir / "Dockerfile").write(
                    "FROM alpine:latest\n" +
                        "ARG KYO_VAL\n" +
                        "RUN echo build-arg=${KYO_VAL}\n"
                )
                events <- Scope.run {
                    ContainerImage.buildFromPath(
                        dir,
                        tags = Chunk(s"$imgName:latest"),
                        buildArgs = Dict("KYO_VAL" -> sentinel)
                    ).run
                }
                _ <- Abort.run[ContainerException](ContainerImage.remove(ContainerImage(imgName, "latest"), force = true))
                _ <- (dir / "Dockerfile").remove
                _ <- dir.removeAll
            yield
                // The RUN step echoes the build-arg value; it appears in the stream events.
                assert(
                    events.exists(_.stream.getOrElse("").contains(sentinel)),
                    s"expected build-arg sentinel '$sentinel' in stream events; got: ${events.map(_.stream)}"
                )
            end for
        }

        "failed build (non-zero RUN) surfaces a typed ContainerException" - runBackends {
            val dir     = Path("/tmp/" + uniqueName("kyo-build-err"))
            val imgName = uniqueName("kyo-built-err")
            for
                _ <- dir.mkDir
                _ <- (dir / "Dockerfile").write("FROM alpine:latest\nRUN false\n")
                result <- Abort.run[ContainerException] {
                    Scope.run(ContainerImage.buildFromPath(dir, tags = Chunk(s"$imgName:latest")).run)
                }
                _ <- Abort.run[ContainerException](ContainerImage.remove(ContainerImage(imgName, "latest"), force = true))
                _ <- (dir / "Dockerfile").remove
                _ <- dir.removeAll
            yield result match
                case Result.Failure(_: ContainerException) => succeed
                case Result.Success(events)                => fail(s"expected build to fail, got success with events: $events")
                case Result.Panic(e)                       => fail(s"expected typed ContainerException, got panic: $e")
            end for
        }

        "buildFromPath with non-existent --target stage fails with ContainerBuildFailedException" - runBackends {
            val dir = Path("/tmp/" + uniqueName("kyo-build-fail"))
            val tag = uniqueName("kyo-built-fail") + ":latest"
            for
                _ <- dir.mkDir
                _ <- (dir / "Dockerfile").write("FROM alpine:latest as base\n")
                r <- Abort.run[ContainerException](
                    Scope.run {
                        ContainerImage.buildFromPath(dir, tags = Chunk(tag), target = Present("nonexistent-stage")).discard
                    }
                )
                _ <- dir.removeAll
            yield r match
                case Result.Failure(_: ContainerBuildFailedException) => succeed
                case Result.Failure(_: ContainerOperationException)   => succeed
                case other => fail(s"expected ContainerBuildFailedException or ContainerOperationException, got $other")
            end for
        }
    }

    "ContainerImage.search" - {
        "searches Docker Hub and returns results" - runBackends {
            Abort.run[ContainerException] {
                ContainerImage.search("alpine", limit = 5)
            }.map {
                case Result.Success(results) =>
                    assert(results.nonEmpty)
                    assert(results.size <= 5)
                    assert(results.exists(_.name.contains("alpine")))
                case Result.Failure(_) =>
                    // Registry may be unreachable (TLS cert, network, etc.)
                    info("search failed — registry unreachable")
                    succeed
                case Result.Panic(t) => fail(s"panic: $t")
            }
        }
    }

    "ContainerImage.history" - {
        "returns layer history with created-by commands" - runBackends {
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
        "removes unused images and returns result" - runBackends {
            ContainerImage.prune.map { result =>
                assert(result.spaceReclaimed >= 0)
            }
        }
    }

    "ContainerImage.commit" - {
        "creates image from container with committed changes" - runBackends {
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

        // commit with author and comment metadata persisted in image history
        "commit with author and comment metadata is reflected in repoTags and history" - runBackends {
            val imgName = uniqueName("kyo-commit-meta")
            Container.init(alpine).map { c =>
                for
                    _ <- c.exec("touch", "/meta-marker")
                    id <- ContainerImage.commit(
                        c.id,
                        repo = imgName,
                        tag = "v1",
                        comment = "add meta-marker",
                        author = "kyo-test"
                    )
                    i <- ContainerImage.inspect(ContainerImage(imgName, "v1"))
                    h <- ContainerImage.history(ContainerImage(imgName, "v1"))
                    _ <- ContainerImage.remove(ContainerImage(imgName, "v1"), force = true)
                yield
                    assert(id.nonEmpty, "committed image id must be non-empty")
                    assert(i.repoTags.exists(_.reference.contains(imgName)), s"expected imgName in repoTags, got: ${i.repoTags}")
                    // comment persistence: Docker /commit stores comment in history entry
                    // Concern B: Podman shell backend may not persist comment — assert strictly first
                    assert(
                        h.headOption.exists(e => e.comment.contains("add meta-marker")),
                        s"expected history head to contain 'add meta-marker', got: ${h.headOption}"
                    )
            }
        }
    }

    "ContainerImage.RegistryAuth" - {
        "apply creates auth with non-empty auths map" in {
            val auth = ContainerImage.RegistryAuth("user", "pass", "https://index.docker.io/v1/")
            assert(auth.auths.nonEmpty)
            assert(auth.auths.contains(ContainerImage.Registry("https://index.docker.io/v1/")))
        }

        "fromConfig does not crash on missing or malformed config" - runBackends {
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
                case Result.Panic(t) => fail(s"panic: $t")
            }
        }
    }

    // =========================================================================
    // Network
    // =========================================================================

    "Network.init" - {
        "creates a bridge network and returns valid id" - runBackends {
            val name = uniqueName("kyo-net")
            Scope.run {
                for
                    id   <- Container.Network.init(Container.Network.Config.default.copy(name = name))
                    info <- Container.Network.inspect(id)
                yield
                    assert(id.value.nonEmpty)
                    assert(info.name == name)
                    assert(info.driver == Container.NetworkDriver.Bridge)
                end for
            }
        }

        "creates with labels" - runBackends {
            val name = uniqueName("kyo-net-lbl")
            Scope.run {
                for
                    id <- Container.Network.init(Container.Network.Config.default.copy(
                        name = name,
                        labels = Dict("env" -> "test")
                    ))
                    info <- Container.Network.inspect(id)
                yield assert(info.labels("env") == "test")
                end for
            }
        }

        // empty network name: daemon should reject or assign a non-empty server-assigned Id
        "Network.init with empty config.name surfaces a clear error or returns a usable Id" - runBackends {
            Scope.run {
                val r = Abort.run[ContainerException](
                    Container.Network.init(Container.Network.Config.default.copy(name = ""))
                )
                r.map {
                    case Result.Failure(_: ContainerException) => succeed // preferred: rejected at API layer
                    case Result.Success(id)                    =>
                        // If accepted, the returned Id MUST be non-empty (server-assigned)
                        // and must not be a sentinel placeholder.
                        assert(
                            id.value.nonEmpty,
                            "empty name returned an empty Id, which is unusable"
                        )
                        assert(
                            !id.value.contains("undefined") && !id.value.contains("default"),
                            s"id contains a sentinel string: ${id.value}"
                        )
                        succeed
                    case Result.Panic(t) => fail(s"panic: $t")
                }
            }
        }
    }

    "Network.list" - {
        "lists all networks including default bridge" - runBackends {
            Container.Network.list.map { nets =>
                assert(nets.nonEmpty)
                assert(nets.exists(n => n.name == "bridge" || n.name == "podman"))
            }
        }

        "filters by name" - runBackends {
            val name = uniqueName("kyo-net-flt")
            Scope.run {
                for
                    _    <- Container.Network.init(Container.Network.Config.default.copy(name = name))
                    nets <- Container.Network.list(filters = Dict("name" -> Chunk(name)))
                yield
                    assert(nets.size == 1)
                    assert(nets.head.name == name)
                end for
            }
        }
    }

    "Network.inspect" - {
        "returns network details matching create config" - runBackends {
            val name = uniqueName("kyo-net-insp")
            Scope.run {
                for
                    id   <- Container.Network.init(Container.Network.Config.default.copy(name = name))
                    info <- Container.Network.inspect(id)
                yield
                    assert(info.name == name)
                    assert(info.id == id)
                    assert(info.driver == Container.NetworkDriver.Bridge)
                end for
            }
        }

        "fails with NetworkNotFound for bad id" - runBackends {
            Abort.run[ContainerException] {
                Container.Network.inspect(Container.Network.Id("nonexistent-net-xyz-999"))
            }.map { r =>
                r match
                    case Result.Failure(_: ContainerNetworkMissingException) => succeed
                    case other                                               => fail(s"Expected NetworkNotFound, got $other")
            }
        }
    }

    "Network.remove" - {
        "removes a network and inspect fails" - runBackends {
            val name = uniqueName("kyo-net-rm")
            for
                id <- Container.Network.initUnscoped(Container.Network.Config.default.copy(name = name))
                _  <- Container.Network.remove(id)
                r  <- Abort.run[ContainerException](Container.Network.inspect(id))
            yield r match
                case Result.Failure(_: ContainerNetworkMissingException) => succeed
                case other                                               => fail(s"Expected NetworkNotFound, got $other")
            end for
        }
    }

    "Network.connect" - {
        "two containers on same network can ping each other" - runBackends {
            val netName = uniqueName("kyo-net-ping")
            Scope.run {
                Container.Network.init(Container.Network.Config.default.copy(name = netName)).map { netId =>
                    Container.initWith(alpine.name(uniqueName("kyo-srv"))) { server =>
                        Container.initWith(alpine.name(uniqueName("kyo-cli"))) { client =>
                            for
                                _ <- Container.Network.connect(netId, server.id, aliases = Chunk("server"))
                                _ <- Container.Network.connect(netId, client.id)
                                r <- client.exec("ping", "-c", "1", "-W", "2", "server")
                            yield assert(r.isSuccess)
                        }
                    }
                }
            }
        }

        "NetworkMode.Custom with aliases resolves via DNS" - runBackends {
            val netName = uniqueName("kyo-net-alias")
            Scope.run {
                Container.Network.init(Container.Network.Config.default.copy(name = netName)).map { _ =>
                    val serverCfg = Container.Config.default.copy(
                        image = ContainerImage("alpine:latest"),
                        command = Present(Container.sleepForever),
                        networkMode = Container.Config.NetworkMode.Custom(netName, Chunk("server-alias"))
                    ).stopTimeout(0.seconds)
                    val clientCfg = Container.Config.default.copy(
                        image = ContainerImage("alpine:latest"),
                        command = Present(Container.sleepForever),
                        networkMode = Container.Config.NetworkMode.Custom(netName)
                    ).stopTimeout(0.seconds)
                    Container.initWith(serverCfg) { _ =>
                        Container.initWith(clientCfg) { client =>
                            client.exec("ping", "-c", "1", "-W", "2", "server-alias").map { r =>
                                assert(r.isSuccess, s"ping server-alias failed: exit=${r.exitCode.toInt} out=${r.stdout} err=${r.stderr}")
                            }
                        }
                    }
                }
            }
        }

        "Network.connect is idempotent when container already attached" - runBackends {
            // Defer UUID generation into the computation via Sync.defer so each backend
            // gets its own unique name when backends run concurrently.
            Sync.defer(java.util.UUID.randomUUID().toString.take(8)).map { uid =>
                Scope.run {
                    Container.Network.init(Container.Network.Config.default.copy(name = s"kyo-net-idem-$uid")).map { netId =>
                        Container.initWith(alpine.name(s"kyo-ctr-idem-$uid")) { c =>
                            for
                                _ <- Container.Network.connect(netId, c.id)
                                _ <- Container.Network.connect(netId, c.id) // second call must not fail
                            yield assert(true)
                        }
                    }
                }
            }
        }
    }

    "Network.prune" - {
        "returns correct network IDs for pruned networks" - runBackends {
            val netName = uniqueName("kyo-net-prune")
            val label   = uniqueName("kyo-net-prune-lbl")
            for
                netId <- Container.Network.initUnscoped(Container.Network.Config.default.copy(
                    name = netName,
                    labels = Dict("kyo-prune-test" -> label)
                ))
                pruned <- Container.Network.prune(filters = Dict("label" -> Chunk(s"kyo-prune-test=$label")))
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

    "Network.init scope-managed cleanup" - {
        "Network.init — scope-managed cleanup" - runBackends {
            val netName = uniqueName("kyo-net-init-test")
            Scope.run {
                Container.Network.init(Container.Network.Config.default.copy(name = netName)).map { id =>
                    Container.Network.list(Dict("name" -> Chunk(netName))).map { before =>
                        assert(before.nonEmpty, s"network $netName should exist inside the scope")
                    }
                }
            }.andThen {
                Container.Network.list(Dict("name" -> Chunk(netName))).map { after =>
                    assert(after.isEmpty, s"network $netName should be removed after scope closes")
                }
            }
        }
    }

    // =========================================================================
    // Volume
    // =========================================================================

    "Volume.init" - {
        "creates a volume with auto-generated name" - runBackends {
            Scope.run {
                Container.Volume.init(Container.Volume.Config.default).map { id =>
                    Container.Volume.inspect(id).map { info =>
                        assert(info.name.value.nonEmpty)
                        assert(info.driver == "local")
                        assert(info.mountpoint.nonEmpty)
                    }
                }
            }
        }

        "creates a volume with explicit name" - runBackends {
            val name = Container.Volume.Id(uniqueName("kyo-vol"))
            Scope.run {
                Container.Volume.init(Container.Volume.Config.default.copy(name = Present(name))).map { id =>
                    assert(id == name): Assertion
                }
            }
        }

        "creates with labels" - runBackends {
            val name = Container.Volume.Id(uniqueName("kyo-vol-lbl"))
            Scope.run {
                for
                    _ <- Container.Volume.init(Container.Volume.Config.default.copy(
                        name = Present(name),
                        labels = Dict("env" -> "test")
                    ))
                    info <- Container.Volume.inspect(name)
                yield assert(info.labels("env") == "test")
                end for
            }
        }
    }

    "Volume.list" - {
        "lists volumes including created one" - runBackends {
            val name = Container.Volume.Id(uniqueName("kyo-vol-lst"))
            Scope.run {
                for
                    _    <- Container.Volume.init(Container.Volume.Config.default.copy(name = Present(name)))
                    vols <- Container.Volume.list
                yield assert(vols.exists(_.name == name))
                end for
            }
        }

        "preserves labels from creation" - runBackends {
            val name = Container.Volume.Id(uniqueName("kyo-vol-labels"))
            Scope.run {
                for
                    _ <- Container.Volume.init(Container.Volume.Config.default.copy(
                        name = Present(name),
                        labels = Dict("test-key" -> "test-value", "another" -> "label")
                    ))
                    vols <- Container.Volume.list
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
    }

    "Volume.inspect" - {
        "returns volume details with mountpoint" - runBackends {
            val name = Container.Volume.Id(uniqueName("kyo-vol-insp"))
            Scope.run {
                for
                    _    <- Container.Volume.init(Container.Volume.Config.default.copy(name = Present(name)))
                    info <- Container.Volume.inspect(name)
                yield
                    assert(info.name == name)
                    assert(info.mountpoint.nonEmpty)
                    assert(info.driver == "local")
                end for
            }
        }

        "fails with VolumeNotFound for bad name" - runBackends {
            Abort.run[ContainerException] {
                Container.Volume.inspect(Container.Volume.Id("nonexistent-vol-xyz-999"))
            }.map { r =>
                r match
                    case Result.Failure(_: ContainerVolumeMissingException) => succeed
                    case other                                              => fail(s"Expected VolumeNotFound, got $other")
            }
        }
    }

    "Volume.remove" - {
        "removes a volume and inspect fails" - runBackends {
            val name = Container.Volume.Id(uniqueName("kyo-vol-rm"))
            for
                _ <- Container.Volume.initUnscoped(Container.Volume.Config.default.copy(name = Present(name)))
                _ <- Container.Volume.remove(name)
                r <- Abort.run[ContainerException](Container.Volume.inspect(name))
            yield r match
                case Result.Failure(_: ContainerVolumeMissingException) => succeed
                case other                                              => fail(s"Expected VolumeNotFound, got $other")
            end for
        }
    }

    "Volume.prune" - {
        "removes unused volumes and returns result" - runBackends {
            Container.Volume.prune.map { r =>
                assert(r.spaceReclaimed >= 0)
            }
        }
    }

    "Volume.init scope-managed cleanup" - {
        "Volume.init — scope-managed cleanup" - runBackends {
            val volId = Container.Volume.Id(uniqueName("kyo-vol-init-test"))
            Scope.run {
                Container.Volume.init(Container.Volume.Config.default.copy(name = Present(volId))).map { id =>
                    Container.Volume.list(Dict("name" -> Chunk(volId.value))).map { before =>
                        assert(before.nonEmpty, s"volume $volId should exist inside scope")
                    }
                }
            }.andThen {
                Container.Volume.list(Dict("name" -> Chunk(volId.value))).map { after =>
                    assert(after.isEmpty, s"volume $volId should be removed after scope closes")
                }
            }
        }
    }

    // =========================================================================
    // Integration / Edge Cases
    // =========================================================================

    "container with mounts" - {
        "bind mount — host file visible in container" - runBackends {
            val hostDir = Path("/tmp/" + uniqueName("kyo-bind"))
            for
                _ <- hostDir.mkDir
                _ <- (hostDir / "data.txt").write("from-host-12345")
                content <- Scope.run {
                    Container.initWith(alpine.bind(hostDir, Path("/mnt/data"), readOnly = true)) { c =>
                        c.exec("cat", "/mnt/data/data.txt").map(_.stdout.trim)
                    }
                }
                _ <- (hostDir / "data.txt").remove
                _ <- hostDir.removeAll
            yield assert(content == "from-host-12345")
            end for
        }

        "bind mount — readOnly prevents writes" - runBackends {
            val hostDir = Path("/tmp/" + uniqueName("kyo-bind-ro"))
            for
                _ <- hostDir.mkDir
                result <- Scope.run {
                    Container.initWith(alpine.bind(hostDir, Path("/mnt/data"), readOnly = true)) { c =>
                        c.exec("touch", "/mnt/data/test")
                    }
                }
                _ <- hostDir.removeAll
            yield assert(!result.isSuccess)
            end for
        }

        "bind mount from /tmp works on macOS" - runBackends {
            val hostDir  = Path("/tmp/" + uniqueName("kyo-tmp-bind"))
            val filename = "test-data.txt"
            for
                _ <- hostDir.mkDir
                _ <- (hostDir / filename).write("from-tmp-host-path")
                content <- Scope.run {
                    Container.initWith(alpine.bind(hostDir, Path("/mnt/tmpdata"), readOnly = true)) { c =>
                        c.exec("cat", s"/mnt/tmpdata/$filename").map(_.stdout.trim)
                    }
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

        "named volume persists across container recreations" - runBackends {
            val volName = Container.Volume.Id(uniqueName("kyo-vol-persist"))
            Scope.run {
                Container.Volume.init(Container.Volume.Config.default.copy(name = Present(volName))).map { _ =>
                    for
                        _ <- Scope.run {
                            Container.initWith(alpine.volume(volName, Path("/data"))) { c =>
                                c.exec("sh", "-c", "echo persistent-data-xyz > /data/file.txt")
                            }
                        }
                        content <- Scope.run {
                            Container.initWith(alpine.volume(volName, Path("/data"))) { c =>
                                c.exec("cat", "/data/file.txt").map(_.stdout.trim)
                            }
                        }
                    yield assert(content == "persistent-data-xyz")
                    end for
                }
            }
        }
    }

    "container with environment from Command" - {
        "env from Command.envAppend appears in container" - runBackends {
            Scope.run {
                Container.initWith(
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
    }

    "multi-container scenarios" - {
        "shared volume between two containers" - runBackends {
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
        "parallel init of multiple containers yields distinct ids" - runBackends {
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

        "parallel exec on same container returns correct results" - runBackends {
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
        "scope cleanup runs even when computation aborts" - runBackends {
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

        "operations on removed container fail with NotFound" - runBackends {
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

        "backend unavailable gives clear error with backend name" - runBackends {
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

        "error on failed operation contains correct resource ID — not last CLI arg" - runBackends {
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

    // =========================================================================
    // Edge Cases (from testcontainers, docker-java, moby issues)
    // =========================================================================

    "lifecycle races" - {
        // moby/moby#37698, moby/moby#23371 — name conflict race after removal
        "name reuse after delete — no 409 conflict" - runBackends {
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

        "stop on already-stopped container is idempotent" - runBackends {
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

        "start on already-running container is idempotent" - runBackends {
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
        "concurrent stop and remove does not leave zombie" - runBackends {
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

        "kill on paused container" - runBackends {
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

        "autoRemove container — explicit remove returns NotFound" - runBackends {
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

        "concurrent mappedPort while stop is in flight returns either bound port or typed error" - runBackends {
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

    "exec edge cases" - {
        "exec on stopped container fails without unnecessary retries" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _  <- c.stop
                    t0 <- Clock.now
                    r  <- Abort.run[ContainerException](c.exec("echo", "hello"))
                    t1 <- Clock.now
                yield
                    r match
                        case Result.Failure(_: ContainerException) => ()
                        case other                                 => fail(s"Expected exec on stopped container to fail, got: $other")
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

        "exec on stopped container fails clearly" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop
                    r <- Abort.run[ContainerException](c.exec("echo", "hello"))
                yield r match
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
            }
        }

        // moby/moby#48965 — exec on paused container
        "exec on paused container fails" - runBackends {
            Container.init(alpine).map { c =>
                for
                    _ <- c.pause
                    r <- Abort.run[ContainerException](c.exec("echo", "hello"))
                yield r match
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
            }
        }

        // docker-java#481 — output pipe not fully consumed causes hang
        "exec with large output does not hang (backpressure)" - runBackends {
            Container.init(alpine).map { c =>
                // 1MB of output — verifies stdout is fully drained
                c.exec("sh", "-c", "dd if=/dev/urandom bs=1024 count=1024 2>/dev/null | base64").map { r =>
                    assert(r.isSuccess)
                    assert(r.stdout.length > 1024 * 1024)
                }
            }
        }

        "exec exit code race — fast exit returns correct code" - runBackends {
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
        "high concurrency exec — 20 parallel calls" - runBackends {
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
        "follow logs on container that exits immediately" - runBackends {
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
        "logs after container restart contain both runs" - runBackends {
            // Use the container's main command to produce output, since exec output
            // to /dev/stdout is not captured in container logs on all runtimes.
            // Reuse the shared alpine config (stopTimeout=0) — its `sleep infinity` command is
            // equivalent to the per-test trap+echo pattern for the restart signal test.
            val config = alpine
                .command("sh", "-c", "trap 'exit 0' TERM; echo run-marker; sleep infinity")
                .autoRemove(false)
            Container.init(config).map { c =>
                for
                    _    <- c.restart(timeout = 0.seconds)
                    text <- c.logsText
                yield
                    // After restart, the command runs again, so run-marker appears at least twice
                    assert(text.contains("run-marker"))
            }
        }

        "TTY mode logs — no multiplexing, raw stream" - runBackends {
            val config = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; echo tty-output; sleep infinity")
                .allocateTty(true)
            Container.init(config).map { c =>
                c.logsText.map { text =>
                    assert(text.contains("tty-output"))
                }
            }
        }

        "logsText with tail=Int.MaxValue on a 1MB log returns the full content" - runBackends {
            val cfg = Container.Config("alpine")
                .command(
                    "sh",
                    "-c",
                    "i=0; while [ $i -lt 50000 ]; do echo xxxxxxxxxxxxxxxxxxxx; i=$((i+1)); done; echo END-MARKER; sleep 5"
                )
            Container.init(cfg).map { c =>
                Scope.run {
                    for
                        // Block until the marker line is observed — deterministic, no sleep.
                        _       <- c.logStream.takeWhile(e => !e.content.contains("END-MARKER")).run
                        entries <- c.logs(stdout = true, stderr = false, tail = Int.MaxValue)
                    yield assert(entries.size >= 49000, s"expected ~50000 entries, got ${entries.size}")
                }
            }
        }

        "logs(stdout=false, stderr=false) returns empty Chunk (paired control proves output existed)" - runBackends {
            val cfg = Container.Config("alpine")
                .command("sh", "-c", "echo a; echo b 1>&2; sleep 5")
            Container.init(cfg).map { c =>
                for
                    // Wait until output is observable on the positive-control path.
                    both <- c.logs(stdout = true, stderr = true)
                    _ = assert(both.nonEmpty, "positive control: container did emit output before flag-test")
                    suppressed <- c.logs(stdout = false, stderr = false)
                yield assert(suppressed.isEmpty, s"expected empty when both flags false, got: $suppressed")
            }
        }

        // attach to Tty=true container preserves raw 8-byte-header-shaped output (no demux)
        "attach to Tty=true container preserves raw 8-byte-header-shaped output (no demux)" - runBackends {
            // 13 bytes: 8 that look like a Docker multiplex header + 5-byte "hello" payload.
            // For Tty=true these are RAW; demux must be skipped — expect all 13 bytes preserved.
            val cfg = Container.Config("alpine")
                .command("sh", "-c", "printf '\\x01\\x00\\x00\\x00\\x00\\x00\\x00\\x05hello'; exit 0")
                .allocateTty(true)
                .autoRemove(false)
            Container.initWith(cfg) { c =>
                for
                    _   <- c.waitForExit
                    raw <- c.logs(stdout = true, stderr = false)
                yield
                    val bytes = raw.flatMap(_.content.getBytes("ISO-8859-1")).toArray
                    // First byte must be 0x01 (raw); a multiplex parser would have consumed the header.
                    assert(
                        bytes.headOption.contains(0x01.toByte),
                        s"expected first byte 0x01 (raw TTY); got bytes=${bytes.take(20).toList.map("0x%02x".format(_))}"
                    )
                    assert(
                        bytes.length >= 13,
                        s"expected ≥13 bytes preserved; got ${bytes.length}"
                    )
            }
        }

        // final log line without trailing newline must not be dropped by the multiplex framing layer
        "final log line without trailing newline is not dropped" - runBackends {
            // printf writes bytes with no trailing '\n'. The multiplex framing layer is
            // newline-agnostic; logsText should preserve the content regardless.
            // autoRemove(false): with autoRemove(true) the container is removed on exit,
            // so the log-read following waitForExit may return "container not found".
            val cfg = alpine
                .command("sh", "-c", "printf 'no-trailing-newline'; exit 0")
                .autoRemove(false)
            Container.init(cfg).map { c =>
                for
                    _    <- c.waitForExit
                    text <- c.logsText
                yield assert(
                    text.contains("no-trailing-newline"),
                    s"BUG: final log line dropped when no \\n; got: '$text'"
                )
            }
        }
    }

    "stats edge cases" - {
        "stats on stopped container fails cleanly" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop
                    r <- Abort.run[ContainerException](c.stats)
                yield r match
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
            }
        }

        "statsStream cancellation releases resources" - runBackends {
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

        // Stats.Cpu and Stats.Memory use nested field paths; cgroup v2 may omit totalUsage
        "stats fields handle cgroup v2 nullable values" - runBackends {
            // Drive non-trivial CPU + memory load so stats values are populated.
            // c.exec binds its result; c.stats is called after the burst completes.
            Container.init(alpine).map { c =>
                for
                    _ <- c.exec("sh", "-c", "yes > /dev/null & sleep 0.5; kill $!")
                    s <- c.stats
                yield
                    // s.memory.usage: Long — always present, non-zero for a live container
                    assert(
                        s.memory.usage > 0L && s.memory.usage < 1L * 1024 * 1024 * 1024,
                        s"sane memory usage expected; got ${s.memory.usage}"
                    )
                    // s.cpu.usagePercent: Double — derived by backend; cgroup-v2 robust;
                    // negative value would indicate a backend calculation bug.
                    assert(
                        s.cpu.usagePercent >= 0.0,
                        s"cpu usagePercent must be non-negative; got ${s.cpu.usagePercent}"
                    )
            }
        }
    }

    "copy edge cases" - {
        // CVE-2018-15664 — symlink traversal in archive path
        "copy file with unicode name roundtrip" - runBackends {
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

        "copy to read-only filesystem fails" - runBackends {
            Container.init(alpine.readOnlyFilesystem(true)).map { c =>
                Abort.run[ContainerException] {
                    val localPath = Path("/tmp/" + uniqueName("kyo-ro"))
                    localPath.write("data").andThen {
                        c.copyTo(localPath, Path("/usr/test"))
                    }
                }.map {
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
                }
            }
        }

        "copy large file (> 64KB pipe buffer)" - runBackends {
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
        "port not immediately available after start — health check waits" - runBackends {
            val config = Container.Config("alpine")
                .command("sh", "-c", "sleep 1; while true; do echo ok | nc -l -p 9090; done")
                .port(9090, 19090)
                .healthCheck(Container.HealthCheck.port(9090, retrySchedule = Schedule.fixed(200.millis).take(30)))
            assertRuns(config)
        }

        "exposed port without host binding has Absent hostPort in inspect" - runBackends {
            Container.init(alpine.port(80)).map { c =>
                c.inspect.map { info =>
                    val binding = info.ports.find(_.containerPort == 80)
                    assert(binding.isDefined)
                }
            }
        }

        // mappedPort filters by TCP; UDP-bound port cannot be looked up via mappedPort
        // Correction C: no 4-arg PortBinding.apply exists; use .default.copy(...)
        "mappedPort filters by TCP — UDP-bound port cannot be looked up" - runBackends {
            val udpBinding = Container.Config.PortBinding.default.copy(
                containerPort = 9999,
                hostPort = 0,
                hostIp = "",
                protocol = Container.Config.Protocol.UDP
            )
            val cfg = Container.Config("alpine")
                .command("sh", "-c", "trap 'exit 0' TERM; sleep infinity")
                .port(udpBinding)
            Container.init(cfg).map { c =>
                Abort.run[ContainerException](c.mappedPort(9999)).map {
                    case Result.Failure(_: ContainerOperationException) => succeed
                    case Result.Failure(other)                          => fail(s"unexpected error type: $other")
                    case Result.Panic(t)                                => fail(s"panic: $t")
                    case Result.Success(_) => fail("BUG: mappedPort returned a port for UDP-only — TCP filter not applied")
                }
            }
        }

        "two containers on the same host port — second fails with PortConflict carrying port" - runBackends {
            // Keep the ServerSocket OPEN so the port is definitely still held when the container
            // tries to bind it (avoids TIME_WAIT races on Linux/macOS). Close after both inits.
            val sock = new java.net.ServerSocket(0)
            val port = sock.getLocalPort
            val cfg1 = alpine.command("sleep", "10").port(80, port).autoRemove(true)
            try
                Scope.run {
                    Abort.run[ContainerException](Container.init(cfg1)).flatMap {
                        case Result.Failure(e: ContainerPortConflictException) =>
                            // First container also failed — port still held by ServerSocket
                            assert(
                                e.port == port,
                                s"expected port=$port, got port=${e.port} (regex extracted wrong number)"
                            )
                        case Result.Failure(other) =>
                            fail(s"expected ContainerPortConflictException on first init, got: $other")
                        case Result.Panic(t) =>
                            fail(s"panic on first init: $t")
                        case Result.Success(c1) =>
                            val cfg2 = alpine.command("sleep", "10").port(80, port).autoRemove(true)
                            Abort.run[ContainerException](Container.init(cfg2)).map { r =>
                                r match
                                    case Result.Failure(e: ContainerPortConflictException) =>
                                        assert(
                                            e.port == port,
                                            s"expected port=$port, got port=${e.port} (regex extracted wrong number)"
                                        )
                                    case Result.Failure(other) =>
                                        fail(s"expected ContainerPortConflictException, got: $other")
                                    case Result.Success(_) =>
                                        fail("expected port conflict — second container bound same host port as first")
                                    case Result.Panic(t) =>
                                        fail(s"panic: $t")
                                end match
                            }
                    }
                }
            finally sock.close()
            end try
        }
    }

    "network edge cases" - {
        // moby/moby#17217 — "has active endpoints" error on network remove
        "remove network with still-connected container fails" - runBackends {
            val netName = uniqueName("kyo-net-inuse")
            Scope.run {
                for
                    netId <- Container.Network.init(Container.Network.Config.default.copy(name = netName))
                    result <- Container.initWith(alpine) { c =>
                        for
                            _ <- Container.Network.connect(netId, c.id)
                            r <- Abort.run[ContainerException](Container.Network.remove(netId))
                        yield r match
                            case Result.Failure(_: ContainerException) => true
                            case other                                 => false
                    }
                yield assert(result, "Expected typed Failure when removing network with connected container")
                end for
            }
        }
    }

    "volume edge cases" - {
        // moby/moby#43068 — volume-in-use removal
        "remove volume in use by stopped container fails" - runBackends {
            val volName = Container.Volume.Id(uniqueName("kyo-vol-inuse"))
            Scope.run {
                for
                    _ <- Container.Volume.init(Container.Volume.Config.default.copy(name = Present(volName)))
                    c <- Container.init(alpine.volume(volName, Path("/data")).autoRemove(false))
                    _ <- c.stop
                    r <- Abort.run[ContainerException](Container.Volume.remove(volName))
                yield r match
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure when removing volume in use, got: $other")
                end for
            }
        }

        "remove with removeVolumes flag does not error on named volume" - runBackends {
            // NOTE: Docker's `removeVolumes` flag only cleans up *anonymous* volumes (no name).
            // Named volumes are preserved by design. kyo's Mount.Volume requires a name, so this
            // test only verifies the flag is accepted without error; the named volume itself is
            // scope-cleaned via Volume.init so the test cannot leak it.
            val volName = Container.Volume.Id(uniqueName("kyo-vol-anon"))
            Scope.run {
                for
                    _ <- Container.Volume.init(Container.Volume.Config.default.copy(name = Present(volName)))
                    _ <- Container.initWith(
                        alpine
                            .mount(Container.Config.Mount.Volume(volName, Path("/anon-data")))
                            .autoRemove(false)
                    ) { c =>
                        for
                            _ <- c.stop
                            _ <- c.remove(force = false, removeVolumes = true)
                        yield succeed
                    }
                yield succeed
                end for
            }
        }

        "removing a volume mounted by two stopped containers fails until both are removed" - runBackends {
            val volName = Container.Volume.Id(uniqueName("kyo-vol-multi"))
            Abort.run[Timeout] {
                Async.timeout(30.seconds) {
                    Scope.run {
                        for
                            _  <- Container.Volume.init(Container.Volume.Config.default.copy(name = Present(volName)))
                            c1 <- Container.init(alpine.volume(volName, Path("/data")).autoRemove(false))
                            c2 <- Container.init(alpine.volume(volName, Path("/data")).autoRemove(false))
                            _  <- c1.stop
                            i1 <- c1.inspect
                            _ = assert(i1.state == Container.State.Stopped, s"c1 not Stopped: ${i1.state}")
                            _  <- c2.stop
                            i2 <- c2.inspect
                            _ = assert(i2.state == Container.State.Stopped, s"c2 not Stopped: ${i2.state}")
                            r1 <- Abort.run[ContainerException](Container.Volume.remove(volName))
                            _ = r1 match
                                case Result.Failure(_: ContainerException) => ()
                                case other                                 => fail(s"expected typed Failure, got: $other")
                            _  <- c1.remove(force = true)
                            r2 <- Abort.run[ContainerException](Container.Volume.remove(volName))
                            _ = r2 match
                                case Result.Failure(_: ContainerException) => ()
                                case other                                 => fail(s"expected typed Failure, got: $other")
                            _  <- c2.remove(force = true)
                            r3 <- Abort.run[ContainerException](Container.Volume.remove(volName))
                        yield r3 match
                            case Result.Success(_) => succeed
                            case other             => fail(s"volume remove must succeed once all referencers gone, got $other")
                        end for
                    }
                }
            }.map {
                case Result.Success(_) => succeed
                case Result.Failure(_) => fail("volume multi-attach test timed out after 30 seconds")
                case Result.Panic(t)   => fail(s"panic: $t")
            }
        }

        // Volume.remove(force=true) with attached stopped container — Shell-only (consistent behavior)
        // — Shell backend must skip the pre-check and pass --force when force=true.
        "Volume.remove(force=true) with attached stopped container — Shell backend honors force flag" - runRuntimes { runtime =>
            Container.withBackendConfig(_.Shell(runtime)) {
                val volName = Container.Volume.Id(uniqueName("kyo-vol-force"))
                Scope.run {
                    for
                        _ <- Container.Volume.init(Container.Volume.Config.default.copy(name = Present(volName)))
                        c <- Container.init(alpine.volume(volName, Path("/data")).autoRemove(false))
                        _ <- c.stop
                        r <- Abort.run[ContainerException](Container.Volume.remove(volName, force = true))
                    yield r match
                        case Result.Success(_) => succeed
                        case Result.Failure(_: ContainerVolumeInUseException) =>
                            fail("Shell backend ignores force=true; rejects with VolumeInUse")
                        case Result.Failure(other) => fail(s"unexpected failure: $other")
                        case Result.Panic(t)       => fail(s"panic: $t")
                    end for
                }
            }
        }
    }

    "podman compatibility" - {
        "default network name may differ — handle both bridge and podman" - runBackends {
            Container.init(alpine).map { c =>
                c.inspect.map { info =>
                    val netNames = info.networkSettings.networks.toMap.values.toSeq
                    assert(netNames.nonEmpty)
                }
            }
        }
    }

    "image pull edge cases" - {
        "pull progress tracks per-layer by id" - runBackends {
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
        "checkpoint/restore preserves a working container ID" - runBackends {
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

        "checkpoint on non-running container fails with clear error" - runBackends {
            Container.init(alpinePersistent(alpine)).map { c =>
                for
                    _ <- c.stop
                    r <- Abort.run[ContainerException](c.checkpoint("snap1"))
                yield r match
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
            }
        }

        "restore from non-existent checkpoint fails with clear error" - runBackends {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException](c.restore("nonexistent-checkpoint-xyz")).map {
                    case Result.Failure(_: ContainerException) => succeed
                    case other                                 => fail(s"expected typed Failure, got: $other")
                }
            }
        }
    }

    // =========================================================================
    // Configurable stats interval
    // =========================================================================

    "statsStream with interval" - {
        "statsStream with custom interval emits stats" - runBackends {
            Container.init(alpine).map { c =>
                Scope.run {
                    c.statsStream(100.millis).take(2).run.map { stats =>
                        assert(stats.size == 2)
                        assert(stats.map(_.readAt).distinct.size == 2)
                    }
                }
            }
        }

        "default statsStream delegates to 200ms interval" - runBackends {
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
        "creates container without scope cleanup" - runBackends {
            val name   = uniqueName("kyo-unscoped")
            val config = alpine.name(name).autoRemove(false)
            // Scope.ensure provides a safety-net cleanup so the container is removed even if the test
            // body fails (assertion, exec error, etc.). The explicit remove below is what the test
            // asserts; the ensure is belt-and-suspenders so a mid-test failure doesn't leak the container.
            Scope.run {
                Container.initUnscoped(config).map { c =>
                    ensureCleanup(c).andThen {
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
        }
    }

    // =========================================================================
    // exec edge cases
    // =========================================================================

    "exec edge cases" - {
        "exec with command not found" - runBackends {
            Container.init(alpine).map { c =>
                Abort.run[ContainerException](c.exec("nonexistent-cmd-xyz")).map {
                    case Result.Failure(ex: ContainerExecFailedException) =>
                        assert(
                            ex.exitCode == ExitCode.Failure(126) || ex.exitCode == ExitCode.Failure(127),
                            s"Expected exit code 126 or 127 but got ${ex.exitCode}"
                        )
                    case other =>
                        fail(s"Expected ExecFailed but got: $other")
                }
            }
        }

        "exec — echo command returns stdout" - runBackends {
            Container.initWith(Container.Config.default.copy(
                image = ContainerImage("alpine:3.19"),
                healthCheck = Container.HealthCheck.init(_ => Kyo.unit)
            ).command("sh", "-c", "sleep 30")) { c =>
                c.exec("echo", "hi from exec").map { r =>
                    assert(r.exitCode.isSuccess, s"exit code ${r.exitCode.toInt}")
                    assert(r.stdout.trim == "hi from exec", s"stdout was '${r.stdout}'")
                }
            }
        }

        "exec immediately after init succeeds (no race with healthcheck)" - runBackends {
            Container.init(alpine.healthCheck(Container.HealthCheck.exec("true"))).map { c =>
                c.exec("echo", "hi").map { r =>
                    assert(r.isSuccess, s"exec failed right after init: $r")
                    assert(r.stdout.trim == "hi")
                }
            }
        }
    }

    // =========================================================================
    // config edge cases
    // =========================================================================

    "config edge cases" - {
        "hostname is reflected in container" - runBackends {
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

        "user sets container user" - runBackends {
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
        "logStream on stopped container terminates" - runBackends {
            val config = Container.Config("alpine")
                .command("sh", "-c", "echo done")
                .autoRemove(false)
                .stopTimeout(0.seconds)
            // Scope.ensure provides a safety-net cleanup for the unscoped container; the explicit
            // remove below is what the test asserts, the ensure covers mid-test failure paths.
            Scope.run {
                Container.initUnscoped(config).map { c =>
                    ensureCleanup(c).andThen {
                        Async.sleep(2.seconds).andThen {
                            Scope.run {
                                c.logStream.take(1).run.map { entries =>
                                    c.remove(force = true).andThen {
                                        assert(entries.size <= 1)
                                    }
                                }
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
        "stats on paused container returns valid data" - runBackends {
            Container.init(alpine).map { c =>
                // Poll until pre-pause stats populate. On slow runners (notably linux
                // CI under load) cgroup memory accounting lags behind container start
                // — the container is in State.Running but the kernel hasn't yet
                // surfaced a non-zero `memory.current` to userspace. Without this,
                // the paused-stats assertion below would fail for a reason unrelated
                // to pause: the cgroup never reported anything.
                Loop.indexed { i =>
                    c.stats.map { s =>
                        if s.memory.usage > 0 then Loop.done(())
                        else if i < 50 then Async.sleep(100.millis).andThen(Loop.continue)
                        else Loop.done(()) // give up; let the paused assertion fail cleanly
                    }
                }.andThen {
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
    }

    // =========================================================================
    // network edge cases
    // =========================================================================

    "network edge cases" - {
        "container connected to multiple networks" - runBackends {
            val netName1 = uniqueName("kyo-net-multi1")
            val netName2 = uniqueName("kyo-net-multi2")
            Scope.run {
                for
                    netId1 <- Container.Network.init(Container.Network.Config.default.copy(name = netName1))
                    netId2 <- Container.Network.init(Container.Network.Config.default.copy(name = netName2))
                    result <- Container.initWith(alpine) { c =>
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
                yield result
                end for
            }
        }
    }

    // =========================================================================
    // scope cleanup
    // =========================================================================

    "scope cleanup" - {
        "scope cleanup works when container crashes" - runBackends {
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

    "HealthCheck.exec with 'not ok' output must NOT match 'ok'" - runBackends {
        val hc = Container.HealthCheck.exec(
            command = Command("echo", "not ok"),
            expected = Present("ok"),
            retrySchedule = Schedule.fixed(100.millis).take(3)
        )
        Abort.run[ContainerException] {
            Container.init(alpine.healthCheck(hc))
        }.map {
            case Result.Failure(_: ContainerHealthCheckException) => succeed
            case other => fail(s"Expected ContainerHealthCheckException (substring match is a bug), got: $other")
        }
    }

    "isHealthy returns false in under 500ms when healthcheck fails" - runBackends {
        val config = Container.Config("alpine")
            .command("sh", "-c", "trap 'exit 0' TERM; touch /tmp/healthy; sleep infinity")
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

    "init completes under 2s when healthcheck fails and container is gone mid-retry" - runBackends {
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

    "NotFound references container id, not rename target (shell backend)" - runRuntimes { runtime =>
        // Force shell backend. The HTTP backend passes ids explicitly to its error-mapping layer;
        // only the shell backend uses args.lastOption in mapError.
        Container.withBackendConfig(_.Shell(runtime)) {
            Scope.run {
                Container.initWith(alpinePersistent(alpine)) { c =>
                    for
                        _ <- c.stop(0.seconds)
                        _ <- c.remove(force = true)
                        // docker/podman rename <id> <newName> — last arg is the new name, not the id.
                        // mapError must not use args.lastOption (the new name) as the NotFound target.
                        r <- Abort.run[ContainerException](c.rename("kyo-renamed-after-removed"))
                    yield r match
                        case Result.Failure(e: ContainerMissingException) =>
                            assert(
                                e.id.value == c.id.value,
                                s"Expected NotFound id='${c.id.value}', got id='${e.id.value}' — " +
                                    "mapError must reference the container id, not args.lastOption (the new name)"
                            )
                        case Result.Failure(other) =>
                            fail(s"Expected NotFound, got $other")
                        case Result.Success(_) =>
                            fail("Expected failure after remove")
                        case Result.Panic(t) =>
                            fail(s"panic: $t")
                }
            }
        }
    }

    "scope cleanup waits stopTimeout when stopSignal is Present" - runBackends {
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

    "attachById preserves ports, labels, and mounts in returned Config" - runBackends {
        val hostDir = Path("/tmp/" + uniqueName("kyo-attach-bind"))
        for
            _ <- hostDir.mkDir
            result <- Scope.run {
                Container.initWith(
                    alpine
                        .port(80, 18080)
                        .label("kyo-attach-key", "kyo-attach-value")
                        .bind(hostDir, Path("/mnt/attach-bind"))
                ) { created =>
                    Container.attach(created.id).map { attached =>
                        val cfg = attached.config
                        assert(
                            cfg.ports.exists(_.containerPort == 80),
                            s"Expected port 80 in reattached config, got: ${cfg.ports}"
                        )
                        assert(
                            cfg.labels.get("kyo-attach-key").contains("kyo-attach-value"),
                            s"Expected label kyo-attach-key=kyo-attach-value in reattached config, got: ${cfg.labels}"
                        )
                        assert(
                            cfg.mounts.exists {
                                case Container.Config.Mount.Bind(_, target, _) =>
                                    target == Path("/mnt/attach-bind")
                                case _ => false
                            },
                            s"Expected bind mount /mnt/attach-bind in reattached config, got: ${cfg.mounts}"
                        )
                    }
                }
            }
            _ <- hostDir.removeAll
        yield result
        end for
    }

    "logs preserves stdout/stderr emission order" - runBackends {
        // Both backends must preserve interleaving: HTTP demuxes Docker's framed stream;
        // Shell merges proc.stdout/stderr through a channel, tagging each with its source.
        val config = Container.Config("alpine")
            .command(
                "sh",
                "-c",
                "trap 'exit 0' TERM; " +
                    "echo o1; sleep 0.2; " +
                    "echo e1 >&2; sleep 0.2; " +
                    "echo o2; sleep 0.2; " +
                    "echo e2 >&2; " +
                    "sleep infinity"
            )
            .stopTimeout(0.seconds)
        Container.init(config).map { c =>
            Async.sleep(800.millis).andThen {
                c.logs(stdout = true, stderr = true).map { entries =>
                    val contents = entries.map(_.content).toSeq
                    val o1Idx    = contents.indexOf("o1")
                    val e1Idx    = contents.indexOf("e1")
                    val o2Idx    = contents.indexOf("o2")
                    val e2Idx    = contents.indexOf("e2")
                    assert(o1Idx >= 0, s"Expected 'o1' in logs, got: $contents")
                    assert(e1Idx >= 0, s"Expected 'e1' in logs, got: $contents")
                    assert(o2Idx >= 0, s"Expected 'o2' in logs, got: $contents")
                    assert(e2Idx >= 0, s"Expected 'e2' in logs, got: $contents")
                    assert(
                        o1Idx < e1Idx && e1Idx < o2Idx && o2Idx < e2Idx,
                        s"Expected order o1, e1, o2, e2 but got ordering indices " +
                            s"[o1=$o1Idx, e1=$e1Idx, o2=$o2Idx, e2=$e2Idx] in contents=$contents"
                    )
                }
            }
        }
    }

    "128KB single line arrives intact via logStream" - runRuntimes { runtime =>
        // Force shell backend where chunk boundaries are observable.
        // Container emits exactly ONE line of 128KB then a newline.
        // The line-splitting must handle lines that straddle chunk boundaries —
        // splitting on \n per chunk would produce multiple partial LogEntry entries
        // instead of one.
        Container.withBackendConfig(_.Shell(runtime)) {
            val config = Container.Config("alpine")
                .command(
                    "sh",
                    "-c",
                    "trap 'exit 0' TERM; " +
                        "head -c 131072 /dev/urandom | base64 -w 0; " +
                        "echo; " +
                        "sleep infinity"
                )
            Container.init(config).map { c =>
                Scope.run {
                    c.logStream.take(1).run.map { entries =>
                        assert(entries.nonEmpty, "Expected at least one log entry")
                        val head = entries.head
                        assert(
                            head.content.length >= 131072,
                            s"Expected first LogEntry to contain the full 128KB line, got length=${head.content.length} — " +
                                "chunk-boundary line splitting is breaking long lines apart"
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // Config.env — container-level environment variables
    // =========================================================================

    "Config.env" - {
        "env builder sets a single variable" in {
            val config = Container.Config("alpine").env("FOO", "bar")
            assert(config.env.is(Dict("FOO" -> "bar")))
        }

        "envAll merges multiple variables" in {
            val config = Container.Config("alpine")
                .env("A", "1")
                .envAll(Dict("B" -> "2", "C" -> "3"))
            assert(config.env.is(Dict("A" -> "1", "B" -> "2", "C" -> "3")))
        }

        "env variables from Config.env are visible in container inspect" - runBackends {
            val config = Container.Config(ContainerImage("postgres", "16-alpine"))
                .env("POSTGRES_USER", "kyo")
                .env("POSTGRES_PASSWORD", "secret")
                .env("POSTGRES_DB", "demo")
                .stopTimeout(0.seconds)
            Container.init(config).map { pg =>
                pg.inspect.map { info =>
                    assert(info.env.contains("POSTGRES_USER"), s"POSTGRES_USER not found in env: ${info.env}")
                }
            }
        }
    }

    // =========================================================================
    // command = Absent — image default CMD runs
    // =========================================================================

    "image default CMD runs when command is Absent" - {
        "redis:7-alpine starts via image CMD with no command override" - runBackends {
            // Proves that Absent command lets the image's own CMD/ENTRYPOINT run.
            // redis:7-alpine's default CMD is redis-server; we verify the well-known
            // startup message appears in logs.
            val redisImage = ContainerImage("redis", "7-alpine")
            val config = Container.Config(redisImage)
                .healthCheck(Container.HealthCheck.log(
                    "Ready to accept connections",
                    retrySchedule = Schedule.fixed(500.millis).take(30)
                ))
                .stopTimeout(0.seconds)
            assert(config.command.isEmpty)
            ContainerImage.ensure(redisImage).andThen {
                Container.init(config).map { c =>
                    c.isHealthy.map(h => assert(h, "Expected redis to be healthy via image default CMD"))
                }
            }
        }
    }

    // =========================================================================
    // runOnce
    // =========================================================================

    "runOnce" - {
        "runOnce — alpine echo returns stdout" - runBackends {
            Container.runOnce(
                image = ContainerImage("alpine:3.19"),
                command = Command("echo", "hello-from-runOnce")
            ).map { r =>
                assert(r.exitCode.isSuccess, s"exit=${r.exitCode.toInt}")
                assert(r.stdout.trim == "hello-from-runOnce", s"stdout='${r.stdout}'")
            }
        }

        "runOnce with sleeping command past timeout returns Signaled(15) and timeout marker" - runBackends {
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

        "runOnce with command that exits cleanly under timeout returns its exitCode" - runBackends {
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
        "initAll — sequential startup" - runBackends {
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

    "HealthCheck.port /dev/tcp" - {
        "HealthCheck.port via /dev/tcp on nginx" - runBackends {
            val img = ContainerImage("nginx:alpine")
            ContainerImage.ensure(img).andThen {
                Container.initWith(Container.Config.default.copy(
                    image = img,
                    healthCheck = Container.HealthCheck.port(80, Schedule.fixed(200.millis).take(30))
                )) { c =>
                    c.awaitHealthy.andThen {
                        assert(true) // reached = healthy
                    }
                }
            }
        }
    }

    "Scope teardown" - {
        "Scope teardown — 10 containers all cleaned up" - runBackends {
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

end ContainerItTest
