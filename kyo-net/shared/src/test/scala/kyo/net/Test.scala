package kyo.net

import kyo.*
import kyo.net.internal.TlsProviderPlatform

abstract class Test extends kyo.test.Test[Any]:

    // KYO_NET_ONLY is the single source of truth TestBackends.all filters on for the parameterized eachBackend/eachBackendTls leaves, but a
    // leaf that touches the process-shared NetPlatform.transport directly (e.g. TransportListenerTest, deliberately testing the single
    // production entry point rather than a per-backend fan-out) selects through -Dkyo.net.backend instead (see IoBackend.select), which
    // KYO_NET_ONLY never reached. Bridging it here, once per process and only when no explicit -D override is already present, makes both
    // selection paths agree, so a KYO_NET_ONLY=epoll cell-isolation run stays isolated even through NetPlatform.transport's process-lifetime
    // cache (discovered when a KYO_NET_ONLY=epoll Native run still hit an io_uring-only accept-loop bug through TransportListenerTest).
    // Idempotent: every suite in the process derives the same value from the same env var, so concurrent construction on Native races harmlessly.
    locally:
        if java.lang.System.getProperty("kyo.net.backend") == null then
            sys.env.get("KYO_NET_ONLY").foreach(name => java.lang.System.setProperty("kyo.net.backend", name))

    // 60s per-leaf budget for the whole module. CI runners are far slower than a local box, and the heaviest leaves here
    // drive software TLS over BoringSSL/OpenSSL with dozens of concurrent connections (a few seconds idle on the JVM,
    // several times that on Scala Native and under load). The generous ceiling absorbs that variance while a true
    // deadlock still fails loudly rather than hanging.
    override def timeout = Duration.fromJava(java.time.Duration.ofSeconds(60))

    /** Register one leaf test per registered I/O backend, each running `scenario` against a freshly built [[Transport]] over that backend.
      *
      * Use as the body of a FreeSpec `-` branch, exactly as kyo-pod's `runBackends` is used:
      * {{{
      * "echo round-trips a message" - eachBackend { transport =>
      *     for
      *         listener <- transport.listen("127.0.0.1", 0, 128)(echo).safe.get
      *         conn     <- transport.connect("127.0.0.1", listener.port).safe.get
      *         ...
      *     yield assert(...)
      * }
      * }}}
      * The leaf names render the backend in brackets, e.g. `echo round-trips a message [io_uring]`, so the same scenario, written once, is
      * driven over io_uring, epoll, kqueue, and the NIO floor (JVM), io_uring/epoll/kqueue (Native), and node (JS). The scenario receives the
      * per-backend transport plus an [[kyo.test.AssertScope]] so its body can `assert`.
      *
      * The transport is delivered as an explicit parameter rather than ambiently. The shared net suites already open with `val transport =
      * NetPlatform.transport` and thread that value through `listen`/`connect`; binding the per-backend transport to the same name leaves the
      * rest of a migrated body unchanged, whereas an ambient `given Transport` would force every body to summon it. The scenario is a function
      * value invoked once per leaf, so it is evaluated once per backend, the same once-per-leaf semantics kyo-pod's by-name `v` gives.
      *
      * Env filtering follows kyo-browser's visible-cancel style, not kyo-pod's silent filter-at-registration: a leaf is registered for EVERY
      * registered backend, and the unavailable ones `cancel(...)` rather than vanishing, so a backend that should be available but probes false
      * stays visible as a canceled leaf instead of hiding a probe regression. The built transport is closed via [[Scope.ensure]], so it is
      * released regardless of how the scenario completes.
      */
    def eachBackend(scenario: Transport => (kyo.test.AssertScope ?=> Unit < (Async & Abort[NetException | Closed] & Scope)))(using
        Frame
    ): Unit =
        backendLeaves(_ => Absent)(scenario)

    /** Like [[eachBackend]] but cancels (with the returned reason) the one cell whose backend name `skipCell` maps to `Present`, while every other
      * cell still runs. Pins a documented PENDING / known-broken reason on a single genuinely-broken cell without dropping coverage on the passing
      * backends; the skip is keyed by backend name (combine with a platform check inside the predicate to target a backend-and-platform cell). The
      * skipped cell reports cancelled with the reason, exactly like the not-available cancel.
      */
    def eachBackendExcept(scenario: Transport => (kyo.test.AssertScope ?=> Unit < (Async & Abort[NetException | Closed] & Scope)))(
        skipCell: String => Maybe[String]
    )(using Frame): Unit =
        backendLeaves(skipCell)(scenario)

    private def backendLeaves(skipCell: String => Maybe[String])(
        scenario: Transport => (kyo.test.AssertScope ?=> Unit < (Async & Abort[NetException | Closed] & Scope))
    )(using Frame): Unit =
        TestBackends.all.foreach { entry =>
            s"[${entry.name}]" in {
                if !entry.isAvailable then cancel(s"backend ${entry.name} not available on this host")
                else
                    skipCell(entry.name) match
                        case Present(reason) => cancel(reason)
                        case Absent          => withTransport(entry)(scenario)
            }
        }

    /** Like [[eachBackend]] but additionally wires the shared test certificate and fans the scenario over the full BACKEND x TLS-IMPLEMENTATION
      * matrix: one leaf per (registered backend, registered TLS provider) pair, labelled `[backend / impl]`, so the same TLS scenario runs over
      * io_uring/epoll/kqueue/nio (JVM) and node (JS) against every TLS implementation the platform registers (BoringSSL and the JDK SslEngine on
      * JVM; BoringSSL and system OpenSSL on Native; Node on JS). The scenario receives the per-cell transport plus a server-side [[NetTlsConfig]]
      * carrying the test cert + key and a `trustAll` client config, both PINNED to the cell's provider via [[NetTlsConfig.tlsProvider]] so the
      * handshake actually runs on that implementation, not the platform default:
      * {{{
      * "TLS echo round-trips a message" - eachBackendTls { (transport, serverTls, clientTls) =>
      *     for
      *         listener <- transport.listen("127.0.0.1", 0, 16, serverTls)(echo).safe.get
      *         conn     <- transport.connect("127.0.0.1", listener.port, clientTls).safe.get
      *         ...
      *     yield assert(...)
      * }
      * }}}
      * Which (backend, impl) pairs are VALID is never hard-coded here: each cell asks the freshly built transport whether it can drive the cell's
      * provider via [[Transport.supportedTlsProviders]] (the posix transport drives every registered engine provider; the NIO floor drives only
      * `jdk` inline; JS only `node`) and CANCELS when it cannot, so the valid-combination knowledge lives in production and evolves with the
      * transports rather than as a fixed table in the tests. A cell also cancels when its backend is unavailable on the host, or when the
      * provider's library is not staged ([[TlsProvider.isAvailable]]) -- so an absent BoringSSL bundle shows up as canceled cells, not failures.
      * Transport lifecycle matches [[eachBackend]] (visible cancel, [[Scope.ensure]] close); the cert is written cross-platform through
      * [[TlsTestCertShared.writePems]].
      */
    def eachBackendTls(
        scenario: (
            Transport,
            NetTlsConfig,
            NetTlsConfig
        ) => (kyo.test.AssertScope ?=> Unit < (Async & Abort[NetException | Closed] & Scope))
    )(using Frame): Unit =
        eachBackendTls(TransportConfig.default)(scenario)

    /** Like [[eachBackendTls]] but cancels (with the returned reason) the one (backend, provider) cell `skipCell` maps to `Present`, while every
      * other cell still runs. Pins a documented PENDING reason on a single genuinely-broken (backend, provider) cell without dropping the passing
      * pairs' coverage. The skipped cell reports cancelled with the reason, like the not-available / unsupported cancels.
      */
    def eachBackendTlsExcept(
        scenario: (
            Transport,
            NetTlsConfig,
            NetTlsConfig
        ) => (kyo.test.AssertScope ?=> Unit < (Async & Abort[NetException | Closed] & Scope))
    )(skipCell: (String, String) => Maybe[String])(using Frame): Unit =
        tlsLeaves(TransportConfig.default)(skipCell)(scenario)

    /** As [[eachBackendTls]], but builds each cell's transport with `config` instead of `TransportConfig.default`, so a config-driven behavior
      * (e.g. a finite `handshakeTimeout`) is asserted across the same backend x TLS-impl matrix. The cell's provider pin and lifecycle handling
      * are identical to the no-config form.
      */
    def eachBackendTls(config: TransportConfig)(
        scenario: (
            Transport,
            NetTlsConfig,
            NetTlsConfig
        ) => (kyo.test.AssertScope ?=> Unit < (Async & Abort[NetException | Closed] & Scope))
    )(using Frame): Unit =
        tlsLeaves(config)((_, _) => Absent)(scenario)

    private def tlsLeaves(config: TransportConfig)(skipCell: (String, String) => Maybe[String])(
        scenario: (
            Transport,
            NetTlsConfig,
            NetTlsConfig
        ) => (kyo.test.AssertScope ?=> Unit < (Async & Abort[NetException | Closed] & Scope))
    )(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        for
            entry <- TestBackends.all
            // KYO_NET_TLS_ONLY=<provider> restricts the matrix to one TLS provider (mirrors KYO_NET_ONLY for backends), so a single
            // (backend, provider) cell can be isolated WITHOUT the concurrent dual-provider leaves confounding per-round attribution.
            // Inert by default (unset = every registered provider).
            provider <- TlsProviderPlatform.registered.filter(p => sys.env.get("KYO_NET_TLS_ONLY").forall(_ == p.name))
        do
            s"[${entry.name} / ${provider.name}]" in {
                if !entry.isAvailable then cancel(s"backend ${entry.name} not available on this host")
                else if !provider.isAvailable then cancel(s"TLS impl ${provider.name} not available on this host")
                else
                    // Per-cell skip: a test pins a documented reason for ONE genuinely-broken (backend, provider) cell while every other cell still
                    // runs, so a single bad cell does not drop coverage on the passing pairs. Default is run-everywhere; the skipped cell reports
                    // cancelled (with the reason), exactly like the not-available / unsupported cancels around it. Checked before the eager build.
                    skipCell(entry.name, provider.name) match
                        case Present(reason) => cancel(reason)
                        case Absent          =>
                            // Build the cell's transport eagerly so its TLS capability can be queried for a clean, visible cancel BEFORE the scenario
                            // runs (the same eager-cancel placement the availability checks above use). An unsupported cell closes the just-built
                            // transport and cancels rather than running a mislabeled handshake on a substituted implementation.
                            val transport = entry.build(config, summon[Frame])
                            if !transport.supportedTlsProviders.contains(provider.name) then
                                transport.close()
                                cancel(s"backend ${entry.name} does not drive TLS impl ${provider.name}")
                            else
                                Scope.ensure(Sync.defer(transport.close())).andThen {
                                    TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
                                        val serverTls = NetTlsConfig(
                                            certChainPath = Present(certPath),
                                            privateKeyPath = Present(keyPath),
                                            tlsProvider = Present(provider.name)
                                        )
                                        val clientTls = NetTlsConfig(trustAll = true, tlsProvider = Present(provider.name))
                                        scenario(transport, serverTls, clientTls)
                                    }
                                }
                            end if
                    end match
            }
        end for
    end tlsLeaves

    /** Build the entry's transport with `TransportConfig.default`, register a [[Scope.ensure]] that closes it on scope exit, and run
      * `scenario` against it. The close runs whether the scenario succeeds, fails, or aborts, so no backend leaks its driver pool.
      */
    private def withTransport(entry: TestBackends.Entry)(
        scenario: Transport => (kyo.test.AssertScope ?=> Unit < (Async & Abort[NetException | Closed] & Scope))
    )(using frame: Frame, as: kyo.test.AssertScope): Unit < (Async & Abort[NetException | Closed] & Scope) =
        import AllowUnsafe.embrace.danger
        Sync.defer(entry.build(TransportConfig.default, frame)).map { transport =>
            Scope.ensure(Sync.defer(transport.close())).andThen(scenario(transport))
        }
    end withTransport

end Test
