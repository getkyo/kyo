package kyo

abstract class BasePodTest extends kyo.test.Test[Any]:

    /** Test infrastructure runs synchronously at registration time (`runBackends`/`runRuntimes` test-scope registration), so we provide a
      * single given `AllowUnsafe` here and inherit it in subclasses. This lets `ContainerRuntime`'s file/env primitives delegate to kyo's
      * portable sync `Unsafe` APIs without each call site repeating the import.
      */
    given AllowUnsafe = AllowUnsafe.embrace.danger

    override def timeout = 60.seconds

    // A per-process random token folded into every generated test resource name.
    //
    // Container-runtime suites are forked once per runtime (`#podman` / `#docker`, pinned via
    // KYO_POD_RUNTIME) and those forks run concurrently on one machine, sharing its `/tmp`. A bare
    // per-JVM counter resets to the same sequence in each fork, so both would emit `prefix-1`,
    // `prefix-2`, … and collide on the same host bind-mount directories (one fork's rootful-docker
    // cleanup then leaves a root-owned dir the other fork cannot write to or remove). The token
    // disambiguates the forks (and any concurrent CI job on the same runner) so names stay unique
    // on the shared filesystem. Hex of a random Long keeps it valid for container/volume/network
    // names and host paths alike.
    private val runToken: String = java.lang.Long.toHexString(new java.util.Random().nextLong())

    private val nameCounter = new java.util.concurrent.atomic.AtomicLong(0L)

    /** A test resource name of the form `prefix-<process token>-<counter>`, unique within this JVM and across the concurrently-running
      * per-runtime test forks that share this machine's filesystem.
      */
    def uniqueName(prefix: String): String =
        s"$prefix-$runToken-${nameCounter.incrementAndGet()}"

    // Container ops contend on a single daemon, so leaves must run sequentially: the runBackends design (below)
    // assumes "<=1 in-flight container op per daemon", which only holds with sequential leaves. kyo-test defaults to
    // parallel leaves whereas the ScalaTest base ran them sequentially, so restore that. Without it, parallel leaves
    // race the daemon and produce port conflicts, already-exists, image-pull, and backend errors.
    //
    // Only the socket category is disabled. These suites reach the podman/docker daemon REST API over HttpClient, and
    // those connections are Scope-closed on scope exit, but the NIO transport defers a connection's real fd close to its
    // idle selector's next select(), which nothing wakes, so the fd outlives the run. That fix belongs to the transport
    // (frozen for the kyo-net rewrite); the socket is an opaque socket:[inode] no allowlist can match. File-descriptor,
    // thread, and fiber detection stay on.
    override def config = super.config.sequential.leakCheckSockets(false)

    // Linux CI's container runtime (podman REST API) intermittently takes longer than
    // the production 5-second `HttpClientConfig.timeout` default for ordinary Container ops
    // (init, exec, stats) under load — every test request would fail with HttpTimeoutException.
    // Tests get a 60s client request timeout to match the per-test budget; production users
    // still see the 5s default until they set their own via withConfig.
    // For tests that explicitly need a longer timeout (e.g. image pulls), use runBackendsLong /
    // runBackendLong which scope an even longer 5-minute timeout inside the test body.
    // Ported from the ScalaTest base's `run` override to kyo-test's `aroundLeaf` hook.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds))(body)

    /** Fails the leaf if it leaves a container behind. Snapshots the daemon's container set (all states) around the body, running the body
      * under its own `Scope` first so scope-managed containers are torn down; any container present afterward that was not present before is
      * a resource the leaf failed to free. This is the container analogue of kyo-test's socket/fd/thread leak checks: an accumulating
      * daemon-side resource that otherwise passes silently and, across a full container-test run, exhausts the rootless per-user kernel
      * keyring (each `runc create` allocates a session key; once `kernel.keys.maxkeys` is hit `runc create` fails "unable to create session
      * key: disk quota exceeded" and every later exec/top sees "container state improper"). Diff-based, so a container leaked by an earlier
      * leaf is attributed to that leaf, not this one.
      */
    private def checkingContainerLeak(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[Any] & Scope) =
        Container.currentBackend.map { backend =>
            Container.list(all = true).map { before =>
                val beforeIds = before.map(_.id).toSet
                Scope.run(v).andThen {
                    Container.list(all = true).map { after =>
                        val candidates = after.filterNot(s => beforeIds.contains(s.id))
                        // The daemon's listing side lags behind inspect on podman: a just-removed container can still show
                        // up in `list` for a moment (the same lag HttpContainerBackend.awaitTerminalState documents). Confirm
                        // each candidate is genuinely still present via an authoritative inspect before flagging it, so a
                        // removed-but-still-listed container is not reported as a false leak.
                        Kyo.foreach(candidates) { s =>
                            Abort.run[ContainerException](backend.state(s.id)).map {
                                case Result.Failure(_: ContainerMissingException) => Maybe.empty[Container.Summary]
                                case _                                            => Maybe(s)
                            }
                        }.map { results =>
                            val leaked = results.flatMap(m => Chunk.from(m.toList))
                            if leaked.isEmpty then Kyo.unit
                            else
                                Kyo.foreach(leaked) { s =>
                                    Abort.run[ContainerException](backend.inspect(s.id)).map { insp =>
                                        val detail = insp match
                                            case Result.Success(i) =>
                                                s"state=${i.state} exit=${i.exitCode} restarts=${i.restartCount} pid=${i.pid} " +
                                                    s"created=${i.createdAt} started=${i.startedAt} finished=${i.finishedAt} health=${i.healthStatus}"
                                            case other => s"inspect=$other"
                                        Abort.run[ContainerException](backend.remove(s.id, force = true, removeVolumes = true)).map { rr =>
                                            Abort.run[ContainerException](backend.state(s.id)).map { after =>
                                                val rrStr = rr match
                                                    case Result.Success(_) => "ok"
                                                    case Result.Failure(e) => s"FAIL:${e.getClass.getSimpleName}:${e.getMessage}"
                                                    case Result.Panic(e)   => s"PANIC:${e.getMessage}"
                                                val afterStr = after match
                                                    case Result.Failure(_: ContainerMissingException) => "GONE"
                                                    case Result.Success(st)                           => s"still=$st"
                                                    case o                                            => s"$o"
                                                s"${s.id.value.take(12)}: $detail | reRemove=$rrStr -> $afterStr"
                                            }
                                        }
                                    }
                                }.map { lines =>
                                    fail(s"leaf leaked ${leaked.size} container(s) not freed before exit:\n" + lines.mkString("\n"))
                                }
                            end if
                        }
                    }
                }
            }
        }
    end checkingContainerLeak

    /** Register one leaf test per available `(runtime, backend)` combination. Each registered test runs `v` with the appropriate
      * `Container.withBackendConfig` wrapper. Use as the body of `String -` in test declarations:
      * {{{"my container test" - runBackends { Container.init(image).map(_ => ()) }}}}
      *
      * The runtime scope is rendered as `[podman]` / `[docker]` in test names — bracketed so the build's testGrouping can detect, by
      * inspecting test names, which suites need per-runtime forking. Each forked JVM is pinned to a single runtime via `KYO_POD_RUNTIME`, so
      * this method registers leaves only for the pinned runtime; combined with sequential leaves, ≤1 in-flight container op per daemon.
      */
    def runBackends(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        Seq("podman", "docker").filter(ContainerRuntime.isAvailable).foreach { runtime =>
            s"[$runtime]" - {
                ContainerRuntime.findSocket(runtime).foreach { path =>
                    "http" in {
                        Container.withBackendConfig(_.UnixSocket(Path(path)))(checkingContainerLeak(v))
                    }
                }

                "shell" in {
                    Container.withBackendConfig(_.Shell(runtime))(checkingContainerLeak(v))
                }
            }
        }

    /** Like [[runBackends]] but raises the HTTP client's per-request timeout to 5 minutes for the http arm. Use for integration tests that
      * pull or build large images (e.g. mongo:7, mysql:8, postgres) where the default 5-second `HttpClientConfig` timeout is too short for
      * streaming `/images/create` responses on a cold cache.
      *
      * The shell arm is unaffected — it delegates to the container CLI which uses its own process timeout.
      */
    def runBackendsLong(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        Seq("podman", "docker").filter(ContainerRuntime.isAvailable).foreach { runtime =>
            s"[$runtime]" - {
                ContainerRuntime.findSocket(runtime).foreach { path =>
                    "http" in {
                        Container.withBackendConfig(_.UnixSocket(Path(path))) {
                            HttpClient.withConfig(_.timeout(5.minutes))(checkingContainerLeak(v))
                        }
                    }
                }

                "shell" in {
                    Container.withBackendConfig(_.Shell(runtime))(checkingContainerLeak(v))
                }
            }
        }

    /** Register one leaf test per available container runtime (docker, podman). The test body receives the runtime name as a parameter —
      * use this when the test logic needs to construct a backend config explicitly or branch on runtime identity. The body picks its own
      * backend (HTTP or Shell); no outer `withBackendConfig` is applied.
      *
      * Use as the body of `String -` in test declarations: {{{"auto-detect prefers HTTP" - runRuntimes { runtime => ... }}}}
      */
    def runRuntimes(f: String => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        Seq("podman", "docker").filter(ContainerRuntime.isAvailable).foreach { runtime =>
            s"[$runtime]" in {
                f(runtime)
            }
        }

    /** Register a single leaf using the HTTP backend over the auto-detected runtime socket. Use this when the test exercises kyo-pod's
      * higher-level Container API (predefs, demos, parser-specific stress) and the choice of backend (HTTP vs Shell) or runtime (Podman vs
      * Docker) does not add coverage. The test runs once: one leaf, one fork — no `[runtime]` marker is registered, so the build's
      * testGrouping does not fork the suite per runtime. For tests that need runtime variation use [[runBackends]] (both backends per
      * runtime) or [[runRuntimes]] (one leaf per runtime, body picks the backend).
      */
    def runBackend(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        val socket = Seq("podman", "docker")
            .filter(ContainerRuntime.isAvailable)
            .iterator
            .flatMap(rt => ContainerRuntime.findSocket(rt).iterator)
            .nextOption()
        socket.foreach { path =>
            "http" in {
                Container.withBackendConfig(_.UnixSocket(Path(path)))(checkingContainerLeak(v))
            }
        }
    end runBackend

    /** Like [[runBackend]] but raises the HTTP client's per-request timeout to 5 minutes. Use for single-leaf integration tests that pull
      * or build large images on a cold cache (e.g. predef DB tests).
      */
    def runBackendLong(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        val socket = Seq("podman", "docker")
            .filter(ContainerRuntime.isAvailable)
            .iterator
            .flatMap(rt => ContainerRuntime.findSocket(rt).iterator)
            .nextOption()
        socket.foreach { path =>
            "http" in {
                Container.withBackendConfig(_.UnixSocket(Path(path))) {
                    HttpClient.withConfig(_.timeout(5.minutes))(checkingContainerLeak(v))
                }
            }
        }
    end runBackendLong

    /** Returns `config` with `autoRemove = false` so tests can inspect container state after stopping.
      *
      * Use in place of `config.autoRemove(false)` to reduce boilerplate at integration-test call sites.
      */
    private[kyo] def alpinePersistent(config: Container.Config): Container.Config =
        config.autoRemove(false)

    /** Asserts that `config` produces a container in the `Running` state. Use in tests that only need to verify the container starts up. */
    private[kyo] def assertRuns(config: Container.Config)(using Frame, kyo.test.AssertScope): Unit < (Async & Abort[Any] & Scope) =
        Container.init(config).map(c => c.state.map(s => assert(s == Container.State.Running)))

    /** Registers a `Scope.ensure` that removes `c` (force = true) on scope exit regardless of test outcome.
      *
      * Use inside `Scope.run { Container.initUnscoped(...).map { c => ... } }` blocks as a belt-and-suspenders cleanup for containers
      * created with `initUnscoped`. The explicit `remove` in the test body is what the test asserts; this ensure covers mid-test failure.
      */
    private[kyo] def ensureCleanup(c: Container)(using Frame): Unit < (Async & Abort[Any] & Scope) =
        Scope.ensure(Abort.run[ContainerException](c.remove(force = true)).unit)
end BasePodTest
