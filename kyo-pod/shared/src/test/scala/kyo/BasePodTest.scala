package kyo

abstract class BasePodTest extends kyo.test.Test[Any]:

    /** Test infrastructure runs synchronously at registration time (`runBackends`/`runRuntimes` test-scope registration), so we provide a
      * single given `AllowUnsafe` here and inherit it in subclasses. This lets `ContainerRuntime`'s file/env primitives delegate to kyo's
      * portable sync `Unsafe` APIs without each call site repeating the import.
      */
    given AllowUnsafe = AllowUnsafe.embrace.danger

    override def timeout = 60.seconds

    // A per-process random token folded into every test resource name: per-runtime suites (`#podman`/`#docker`) fork
    // concurrently sharing `/tmp`, so a bare per-JVM counter would emit the same `prefix-N` and collide on bind-mounts.
    private val runToken: String = java.lang.Long.toHexString(new java.util.Random().nextLong())

    private val nameCounter = new java.util.concurrent.atomic.AtomicLong(0L)

    /** A test resource name of the form `prefix-<process token>-<counter>`, unique within this JVM and across the concurrently-running
      * per-runtime test forks that share this machine's filesystem.
      */
    def uniqueName(prefix: String): String =
        s"$prefix-$runToken-${nameCounter.incrementAndGet()}"

    // Container ops contend on a single daemon, so leaves must run sequentially (runBackends assumes <=1
    // in-flight op per daemon); parallel leaves produce port conflicts, already-exists, and pull errors.
    //
    // Only socket leak-checking is disabled: the NIO transport defers a connection's fd close to its idle selector's
    // next select() (which nothing wakes), so the fd outlives the run and its opaque socket:[inode] matches no allowlist.
    override def config = super.config.sequential.leakCheckSockets(false)

    // Linux CI's container runtime (podman REST API) intermittently takes longer than
    // the production 5-second `HttpClientConfig.timeout` default for ordinary Container ops
    // (init, exec, stats) under load — every test request would fail with HttpTimeoutException.
    // Tests get a 60s client request timeout to match the per-test budget; production users
    // still see the 5s default until they set their own via withConfig.
    // For tests that explicitly need a longer timeout (e.g. image pulls), use runBackendsLong /
    // runBackendLong which scope an even longer 5-minute timeout inside the test body.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds))(body)

    /** Fails the leaf if it leaves behind a container it created (the body runs under its own `Scope` first).
      * Unchecked, leaks exhaust the rootless kernel keyring (`runc create` session keys, capped at `kernel.keys.maxkeys`).
      *
      * Asks about the containers this leaf created rather than diffing the daemon's whole list around the leaf. The
      * diff could not tell one leaf's container from another writer's: container suites fork once per runtime and the
      * forks run concurrently (`Test / testForkedParallel`), and where a host's docker socket is a symlink to podman's
      * (a podman machine on macOS installs one) those forks share a daemon, so each saw the other's containers appear
      * mid-leaf and called them leaks. It also missed the leak of a container that already existed. Recording what the
      * leaf's own backend created answers exactly the question the check is named for.
      */
    private def checkingContainerLeak(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[Any] & Scope) =
        Container.currentBackend.map { backend =>
            AtomicRef.init(Chunk.empty[Container.Id]).map { created =>
                Container.withBackend(RecordingBackend(backend, created))(Scope.run(v)).andThen {
                    created.get.map { ids =>
                        // The daemon's listing lags inspect on podman: a just-removed container can still be listed.
                        // Ask inspect, which is authoritative, and treat a missing container as the freed one it is.
                        Kyo.foreach(ids) { id =>
                            Abort.run[ContainerException](backend.state(id)).map {
                                case Result.Failure(_: ContainerMissingException) => Maybe.empty[(Container.Id, Container.State)]
                                case Result.Success(state)                        => Maybe((id, state))
                                case _                                            => Maybe.empty[(Container.Id, Container.State)]
                            }
                        }.map { results =>
                            val leaked = results.flatMap(m => Chunk.from(m.toList))
                            if leaked.isEmpty then Kyo.unit
                            else
                                fail(
                                    s"leaf leaked ${leaked.size} container(s) not freed before exit: " +
                                        leaked.map((id, state) => s"${id.value.take(12)}[$state]").mkString(", ")
                                )
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
                    // The http arm above needs a socket to talk to; this one needs a CLI that reaches the
                    // daemon. A runtime reached through a mounted socket with no CLI installed (a build
                    // container, and any CI runner wired the same way) is genuinely available for HTTP and
                    // cannot serve Shell at all. Cancelled rather than unregistered so the skip is visible in
                    // the run's own totals instead of the leaf silently not existing.
                    requireRuntimeCli(runtime)
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
                    // The http arm above needs a socket to talk to; this one needs a CLI that reaches the
                    // daemon. A runtime reached through a mounted socket with no CLI installed (a build
                    // container, and any CI runner wired the same way) is genuinely available for HTTP and
                    // cannot serve Shell at all. Cancelled rather than unregistered so the skip is visible in
                    // the run's own totals instead of the leaf silently not existing.
                    requireRuntimeCli(runtime)
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

    /** Ensures `image` is present, retrying a failing registry but never a genuinely absent image.
      *
      * A precondition pull reaches the real registry, and Docker Hub intermittently answers 5xx: a manifest
      * HEAD can return 500 for an image that exists. `Container.init` deliberately fails fast
      * on a registry error (a permanently absent image never becomes present by retrying), so the
      * resilience belongs at the test call site rather than in the library.
      *
      * Scoping the retry to `ContainerOperationException` is what keeps it from hiding a defect. Both
      * backends classify a failing registry there: the shell backend reports the pull's own failure, and the
      * HTTP backend maps a 5xx through its generic-status branch, having deliberately excluded 5xx from the
      * missing-image conflation. `ContainerImageMissingException` is a `ContainerNotFoundException`, so an
      * absent image still aborts on the first attempt and the leaves that assert on it are unaffected. A
      * real failure fails again on every attempt and still reds the leaf; only a fault that clears on its
      * own is absorbed.
      *
      * The backoff is real time, unavoidably: the registry is a real service and a virtual clock would not
      * advance it. No leaf asserts on the elapsed time, so nothing here is a timing pass condition.
      */
    private[kyo] def ensureImage(image: ContainerImage)(using Frame): Unit < (Async & Abort[ContainerException]) =
        Retry[ContainerOperationException](
            Schedule.exponentialBackoff(initial = 1.second, factor = 2, maxBackoff = 8.seconds).jitter(0.2).take(3)
        ) {
            ContainerImage.ensure(image)
        }

    /** Cancels the leaf when `runtime`'s CLI is absent, for leaves that construct a Shell backend themselves.
      *
      * The shell backend shells out to `podman` or `docker`, so a host that reaches a daemon over a socket alone,
      * such as one mounted into a container, has no binary for it to run. `runBackends` gates its own shell arm the
      * same way; a leaf that picks the backend in its body has to say so itself. Cancelling names the reason, which
      * is what a leaf that cannot apply here should do rather than failing on a missing binary.
      */
    private[kyo] def requireRuntimeCli(runtime: String)(using Frame, kyo.test.AssertScope): Unit =
        if !ContainerRuntime.cliExists(runtime) then
            cancel(s"the $runtime CLI is not available on this host, so the shell backend cannot run")

end BasePodTest

/** A backend that remembers every container created through it, so a leaf's leak check can ask about the containers
  * that leaf made. Everything else is the backend it wraps.
  */
final private class RecordingBackend(under: kyo.internal.ContainerBackend, created: AtomicRef[Chunk[Container.Id]])
    extends kyo.internal.ContainerBackend(under.meter):

    export under.{create as _, meter as _, *}

    def create(config: Container.Config)(using Frame): Container.Id < (Async & Abort[ContainerException]) =
        under.create(config).map(id => created.updateAndGet(_.append(id)).andThen(id))

end RecordingBackend
