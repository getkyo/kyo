package kyo

import kyo.internal.LeafContainers

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
    // globallySequential, not just sequential: the resource these suites share is the container daemon, which reaches beyond any one suite.
    // `sequential` only orders a suite's own leaves inside the process-global pool, so two container suites sharing a fork would still
    // interleave their operations on the daemon. The build puts every daemon-touching suite in one fork per daemon so this flag covers
    // all of them.
    //
    // Only socket leak-checking is disabled: the NIO transport defers a connection's fd close to its idle selector's
    // next select() (which nothing wakes), so the fd outlives the run and its opaque socket:[inode] matches no allowlist.
    //
    // A fork that does not own the host's leaves keeps only the leaves the runtime helpers register, which carry `runtimeLeaf`; the rest
    // run in the owner fork alone (ContainerRuntime.runsHostLeaves). A `--filter` or `--tag` flag replaces this filter, so a filtered run
    // selects as asked in both forks.
    override def config =
        val base = super.config.sequential.globallySequential(true).leakCheckSockets(false)
        if ContainerRuntime.runsHostLeaves then base else base.filter(base.filter.copy(tagsInclude = Set(runtimeLeaf)))
    end config

    /** Tag on every leaf the runtime helpers register: a leaf bound to the runtime its fork is pinned to. */
    private val runtimeLeaf = "kyo.pod.runtime-leaf"

    // Linux CI's container runtime (podman REST API) intermittently takes longer than
    // the production 5-second `HttpClientConfig.timeout` default for ordinary Container ops
    // (init, exec, stats) under load — every test request would fail with HttpTimeoutException.
    // Tests get a 60s client request timeout to match the per-test budget; production users
    // still see the 5s default until they set their own via withConfig.
    // For tests that explicitly need a longer timeout (e.g. image pulls), use runBackendsLong /
    // runBackendLong which scope an even longer 5-minute timeout inside the test body.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.withConfig(_.timeout(60.seconds))(body)

    /** Fails the leaf if it leaves a container behind (diffs the container set around the body, run under its own `Scope`
      * first). Unchecked, leaks exhaust the rootless kernel keyring (`runc create` session keys, capped at `kernel.keys.maxkeys`).
      */
    private def checkingContainerLeak(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[Any] & Scope) =
        leafCandidates(v).map { (_, candidates) =>
            // Removal is asynchronous: the scope's `remove` returns when the daemon has accepted it, not when the
            // container is gone, and under load the daemon takes seconds over it.
            // The check waits on the daemon through the leaf's barrier
            // rather than a fixed window, and still demands every candidate be gone: a container that really leaked
            // never goes, since nothing else is going to remove it.
            assertEventually {
                stillPresent(candidates).map { leaked =>
                    leaked.isEmpty || fail(
                        s"leaf leaked ${leaked.size} container(s) not freed before exit: " +
                            leaked.map(s => s"${s.id.value.take(12)}[${s.state}]").mkString(", ")
                    )
                }
            }
        }
    end checkingContainerLeak

    /** Runs `v` under its own `Scope` and returns its result with the containers the leaf may have left behind.
      *
      * The daemon is shared with other suites and other builds, so a before/after diff of its container set would count their containers
      * too. Every container `v` causes kyo-pod to create carries this leaf's label instead, and only those are candidates. The labels also
      * name this process as the owner, so a later process removes them if this one dies before its `Scope` does.
      */
    private[kyo] def leafCandidates[A](v: A < (Async & Abort[Any] & Scope))(using
        Frame
    ): (A, Chunk[Container.Summary]) < (Async & Abort[Any]) =
        LeafContainers.sweepOnce.andThen(Random.nextStringAlphanumeric(16)).map { leaf =>
            Container.ambientLabels.let(LeafContainers.labels(leaf))(Scope.run(v)).map { result =>
                Container.list(all = true, filters = Dict("label" -> Chunk(s"${LeafContainers.leafLabelKey}=$leaf")))
                    .map(candidates => (result, candidates))
            }
        }

    /** The candidates the daemon still holds. The daemon's listing lags inspect on podman: a just-removed container can still appear in
      * `list`, so each candidate is confirmed through an authoritative inspect before it counts.
      */
    private[kyo] def stillPresent(candidates: Chunk[Container.Summary])(using
        Frame
    ): Chunk[Container.Summary] < (Async & Abort[Any]) =
        Container.currentBackend.map { backend =>
            Kyo.foreach(candidates) { s =>
                Abort.run[ContainerException](backend.state(s.id)).map {
                    case Result.Failure(_: ContainerMissingException) => Chunk.empty[Container.Summary]
                    case _                                            => Chunk(s)
                }
            }.map(_.flattenChunk)
        }

    /** Register one leaf test per available `(runtime, backend)` combination. Each registered test runs `v` with the appropriate
      * `Container.withBackendConfig` wrapper. Use as the body of `String -` in test declarations:
      * {{{"my container test" - runBackends { Container.init(image).map(_ => ()) }}}}
      *
      * The runtime scope is rendered as `[podman]` / `[docker]` in test names — bracketed so the build's testGrouping can detect, by
      * inspecting test names, which suites need per-runtime forking. Each forked JVM is pinned to a single runtime via `KYO_POD_RUNTIME`, so
      * this method registers leaves only for the pinned runtime; combined with sequential leaves, ≤1 in-flight container op per daemon.
      */
    def runBackends(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        runBackendsOf(_ => v)

    /** [[runBackends]] whose body gets the runtime name, for a leaf whose expected outcome depends on which daemon it reaches. */
    def runBackendsOf(v: String => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        registerBackends(
            (runtime, path) => Container.withBackendConfig(_.UnixSocket(Path(path)))(checkingContainerLeak(v(runtime))),
            runtime => Container.withBackendConfig(_.Shell(runtime))(checkingContainerLeak(v(runtime)))
        )

    /** [[runBackends]] without the leak check around the body, for the leaves that test the leak check itself; the body gets the runtime
      * name. A leaf registered here cleans up after itself explicitly.
      */
    def runBackendsUnchecked(v: String => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        registerBackends(
            (runtime, path) => Container.withBackendConfig(_.UnixSocket(Path(path)))(v(runtime)),
            runtime => Container.withBackendConfig(_.Shell(runtime))(v(runtime))
        )

    /** The `[runtime] › http` and `[runtime] › shell` leaves of the [[runBackends]] family, for every runtime this process answers for.
      *
      * A runtime that cannot run here gets the same two leaves cancelled with its reason, so a filter that matches the real leaves where
      * the runtime is present matches their cancelled twins where it is absent, and the per-runtime fork never runs an empty selection.
      */
    private def registerBackends(
        http: (String, String) => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope),
        shell: String => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope)
    )(using Frame): Unit =
        ContainerRuntime.assigned.foreach { (runtime, cannotRun) =>
            s"[$runtime]" - {
                cannotRun match
                    case Present(reason) =>
                        "http".tagged(runtimeLeaf) in cancel(reason)
                        "shell".tagged(runtimeLeaf) in cancel(reason)
                    case Absent =>
                        ContainerRuntime.findSocket(runtime).foreach { path =>
                            "http".tagged(runtimeLeaf) in http(runtime, path)
                        }
                        "shell".tagged(runtimeLeaf) in {
                            // The http arm above needs a socket to talk to; this one needs a CLI that reaches the
                            // daemon. A runtime reached through a mounted socket with no CLI installed (a build
                            // container, and any CI runner wired the same way) is genuinely available for HTTP and
                            // cannot serve Shell at all. Cancelled rather than unregistered so the skip is visible in
                            // the run's own totals instead of the leaf silently not existing.
                            requireRuntimeCli(runtime)
                            shell(runtime)
                        }
                end match
            }
        }
    end registerBackends

    /** Like [[runBackends]] but raises the HTTP client's per-request timeout to 5 minutes for the http arm. Use for integration tests that
      * pull or build large images (e.g. mongo:7, mysql:8, postgres) where the default 5-second `HttpClientConfig` timeout is too short for
      * streaming `/images/create` responses on a cold cache.
      *
      * The shell arm is unaffected — it delegates to the container CLI which uses its own process timeout.
      */
    def runBackendsLong(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        registerBackends(
            (_, path) =>
                Container.withBackendConfig(_.UnixSocket(Path(path))) {
                    HttpClient.withConfig(_.timeout(5.minutes))(checkingContainerLeak(v))
                },
            runtime => Container.withBackendConfig(_.Shell(runtime))(checkingContainerLeak(v))
        )

    /** Register one leaf test per available container runtime (docker, podman). The test body receives the runtime name as a parameter —
      * use this when the test logic needs to construct a backend config explicitly or branch on runtime identity. The body picks its own
      * backend (HTTP or Shell); no outer `withBackendConfig` is applied.
      *
      * Use as the body of `String -` in test declarations: {{{"auto-detect prefers HTTP" - runRuntimes { runtime => ... }}}}
      */
    def runRuntimes(f: String => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        ContainerRuntime.assigned.foreach { (runtime, cannotRun) =>
            s"[$runtime]".tagged(runtimeLeaf) in {
                cannotRun match
                    case Present(reason) => cancel(reason)
                    case Absent          => f(runtime)
            }
        }

    /** Register a single leaf using the HTTP backend over the auto-detected runtime socket. Use this when the test exercises kyo-pod's
      * higher-level Container API (predefs, demos, parser-specific stress) and the choice of backend (HTTP vs Shell) or runtime (Podman vs
      * Docker) does not add coverage. The test runs once per host: the build puts the suite in both per-runtime forks, and only the fork
      * [[ContainerRuntime.singleLeg]] names runs the leaf while the other registers it cancelled. For tests that need runtime variation use
      * [[runBackends]] (both backends per runtime) or [[runRuntimes]] (one leaf per runtime, body picks the backend).
      */
    def runBackend(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        registerSingleLeg(path => Container.withBackendConfig(_.UnixSocket(Path(path)))(checkingContainerLeak(v)))

    /** Like [[runBackend]] but raises the HTTP client's per-request timeout to 5 minutes. Use for single-leaf integration tests that pull
      * or build large images on a cold cache (e.g. predef DB tests).
      */
    def runBackendLong(v: kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        registerSingleLeg { path =>
            Container.withBackendConfig(_.UnixSocket(Path(path))) {
                HttpClient.withConfig(_.timeout(5.minutes))(checkingContainerLeak(v))
            }
        }

    /** The `http` leaf of [[runBackend]] over the socket [[ContainerRuntime.singleLeg]] assigns this process, or that leaf cancelled with
      * the reason when it assigns none, for the same reason [[registerBackends]] registers cancelled twins.
      */
    private def registerSingleLeg(http: String => kyo.test.AssertScope ?=> Unit < (Async & Abort[Any] & Scope))(using Frame): Unit =
        ContainerRuntime.singleLeg match
            case Right(path)  => "http".tagged(runtimeLeaf) in http(path)
            case Left(reason) => "http".tagged(runtimeLeaf) in cancel(reason)
    end registerSingleLeg

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
