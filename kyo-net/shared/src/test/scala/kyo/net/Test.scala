package kyo.net

import kyo.*
import kyo.net.internal.tls.TlsProviderPlatform

abstract class Test extends kyo.test.Test[Any]:

    // 60s per-leaf budget for the whole module. CI runners are far slower than a local box, and the heaviest leaves here
    // drive software TLS over BoringSSL/OpenSSL with dozens of concurrent connections (a few seconds idle on the JVM,
    // several times that on Scala Native and under load). The generous ceiling absorbs that variance while a true
    // deadlock still fails loudly rather than hanging.
    override def timeout = Duration.fromJava(java.time.Duration.ofSeconds(60))

    // Disable ONLY socket-descriptor leak detection; fiber, thread, and file-descriptor checks stay on. The cross-backend harness builds and
    // tears down a Transport per leaf (eachBackend/eachBackendTls), and on io_uring/epoll the driver's deferred fd close can be orphaned by the
    // synchronous pool teardown, leaving ~1 CLOSE_WAIT/LISTEN socket past suite end. A long-lived (production) transport is never torn down, so it
    // never hits this; kyo-http's BaseHttpTest exempts the same category for the same reason. The underlying transport-teardown gap is a known
    // low-severity limitation (it does not affect the process-shared transport real callers use).
    override def config = super.config.leakCheckSockets(false)

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
    def eachBackend(scenario: Transport => (kyo.test.AssertScope ?=> Unit < (Async & Abort[Closed] & Scope)))(using Frame): Unit =
        TestBackends.all.foreach { entry =>
            s"[${entry.name}]" in {
                if !entry.isAvailable then cancel(s"backend ${entry.name} not available on this host")
                else withTransport(entry)(scenario)
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
        scenario: (Transport, NetTlsConfig, NetTlsConfig) => (kyo.test.AssertScope ?=> Unit < (Async & Abort[Closed] & Scope))
    )(using Frame): Unit =
        eachBackendTls(TransportConfig.default)(scenario)

    /** As [[eachBackendTls]], but builds each cell's transport with `config` instead of `TransportConfig.default`, so a config-driven behavior
      * (e.g. a finite `handshakeTimeout`) is asserted across the same backend x TLS-impl matrix. The cell's provider pin and lifecycle handling
      * are identical to the no-config form.
      */
    def eachBackendTls(config: TransportConfig)(
        scenario: (Transport, NetTlsConfig, NetTlsConfig) => (kyo.test.AssertScope ?=> Unit < (Async & Abort[Closed] & Scope))
    )(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        for
            entry    <- TestBackends.all
            provider <- TlsProviderPlatform.registered
        do
            s"[${entry.name} / ${provider.name}]" in {
                if !entry.isAvailable then cancel(s"backend ${entry.name} not available on this host")
                else if !provider.isAvailable then cancel(s"TLS impl ${provider.name} not available on this host")
                else
                    // Build the cell's transport eagerly so its TLS capability can be queried for a clean, visible cancel BEFORE the scenario runs
                    // (the same eager-cancel placement the availability checks above use). An unsupported cell closes the just-built transport and
                    // cancels rather than running a mislabeled handshake on a substituted implementation.
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
            }
        end for
    end eachBackendTls

    /** Build the entry's transport with `TransportConfig.default`, register a [[Scope.ensure]] that closes it on scope exit, and run
      * `scenario` against it. The close runs whether the scenario succeeds, fails, or aborts, so no backend leaks its driver pool.
      */
    private def withTransport(entry: TestBackends.Entry)(
        scenario: Transport => (kyo.test.AssertScope ?=> Unit < (Async & Abort[Closed] & Scope))
    )(using frame: Frame, as: kyo.test.AssertScope): Unit < (Async & Abort[Closed] & Scope) =
        import AllowUnsafe.embrace.danger
        Sync.defer(entry.build(TransportConfig.default, frame)).map { transport =>
            Scope.ensure(Sync.defer(transport.close())).andThen(scenario(transport))
        }
    end withTransport

end Test
