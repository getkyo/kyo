package kyo.net.internal

import kyo.*
import kyo.ffi.Buffer
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.Test

/** Engine construction over recording bindings instead of a real TLS library.
  *
  * What this covers is native OWNERSHIP, which no end-to-end TLS suite can see: an `SSL_CTX` is refcounted, `SSL_new` takes its own reference,
  * and only `SSL_free` releases that one, so a context whose creation reference is never dropped leaks silently along with the chains and keys
  * loaded into it. A leak has no observable behaviour, so counting the calls is the only way to assert it, and driving the provider over stub
  * bindings does that without crypto, without staged libraries, and on every platform.
  *
  * It also reaches the fail-closed identity path, which real material cannot: `SSL_set1_host` on a fresh SSL essentially cannot fail, so the
  * second-level failure (the unmatchable identity itself failing to bind) is unreachable with a real library and reachable here.
  */
class SslLibProviderOwnershipTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** Records the allocation and release calls, and lets a leaf choose which of them reports failure. Handles are plain integers: nothing
      * dereferences them, so the provider cannot tell them from real pointers.
      */
    private class StubBindings(
        ctxNewResult: Long = 10L,
        sslNewResult: Long = 100L,
        minMaxVersionResult: Int = 0,
        systemCaResult: Int = 1,
        verifyNameResult: Int = 1,
        unmatchableResult: Int = 1
    ) extends SslLibBindings:
        var ctxNewCalls       = 0
        var ctxFreeCalls      = 0
        var sslNewCalls       = 0
        var sslFreeCalls      = 0
        var unmatchableCalls  = 0
        var connectStateCalls = 0

        /** Handles still outstanding, by identity. Counting news against frees would accept a build that freed the same handle twice while
          * stranding another, which is the shape both native lifetime bugs in this module have taken.
          */
        val liveCtxs   = scala.collection.mutable.Set.empty[Long]
        val liveSsls   = scala.collection.mutable.Set.empty[Long]
        var strayFrees = 0

        /** Fails the `failAt`-th fallible binding call, whatever that call happens to be.
          *
          * Indexing by call ORDER rather than by method name is what lets the sweep cover construction exhaustively without a table of
          * known failure points: a fallible call added to engine construction later becomes one more index the sweep visits, with nothing
          * to remember to register.
          */
        var failAt: Int = 0

        private var handles       = 0L
        private var fallibleCalls = 0

        def fallibleCallCount: Int = fallibleCalls

        private def fallible[A](failure: A)(ok: => A): A =
            fallibleCalls += 1
            if fallibleCalls == failAt then failure else ok

        private def freshHandle(): Long =
            handles += 1
            handles

        def ctxNew(isServer: Int)(using AllowUnsafe): Long =
            ctxNewCalls += 1
            fallible(0L) {
                if ctxNewResult == 0L then 0L
                else
                    val h = freshHandle()
                    liveCtxs += h
                    h
                end if
            }
        end ctxNew

        def ctxFree(ctx: Long)(using AllowUnsafe): Unit =
            ctxFreeCalls += 1
            if !liveCtxs.remove(ctx) then strayFrees += 1

        def sslNew(ctx: Long, hostname: String)(using AllowUnsafe): Long =
            sslNewCalls += 1
            fallible(0L) {
                if sslNewResult == 0L then 0L
                else
                    val h = freshHandle()
                    liveSsls += h
                    h
                end if
            }
        end sslNew

        def sslFree(ssl: Long)(using AllowUnsafe): Unit =
            sslFreeCalls += 1
            if !liveSsls.remove(ssl) then strayFrees += 1

        def sslSetVerifyName(ssl: Long, hostname: String)(using AllowUnsafe): Int = fallible(0)(verifyNameResult)
        def sslRequireUnmatchableIdentity(ssl: Long)(using AllowUnsafe): Int =
            unmatchableCalls += 1
            fallible(0)(unmatchableResult)
        def sslSetConnectState(ssl: Long)(using AllowUnsafe): Unit = connectStateCalls += 1
        def sslSetAcceptState(ssl: Long)(using AllowUnsafe): Unit  = ()

        def ctxSetCert(ctx: Long, certPem: String, keyPem: String)(using AllowUnsafe): Int = fallible(-1)(0)
        def ctxSetVerifyMode(ctx: Long, mode: Int)(using AllowUnsafe): Unit                = ()
        def ctxLoadCa(ctx: Long, caPem: String)(using AllowUnsafe): Int                    = fallible(0)(1)
        def ctxLoadSystemCa(ctx: Long)(using AllowUnsafe): Int                             = fallible(0)(systemCaResult)
        def ctxSetMinMaxVersion(ctx: Long, min: Int, max: Int)(using AllowUnsafe): Int     = fallible(-1)(minMaxVersionResult)

        def doHandshakeStep(ssl: Long)(using AllowUnsafe): Int                                   = 1
        def feedCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int       = len
        def drainCiphertext(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int      = 0
        def readPlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int            = 0
        def writePlain(ssl: Long, buf: Buffer[Byte], len: Int)(using AllowUnsafe): Int           = len
        def pending(ssl: Long)(using AllowUnsafe): Int                                           = 0
        def shutdownStep(ssl: Long)(using AllowUnsafe): Int                                      = 1
        def peerCertSha256(ssl: Long, outBuf: Buffer[Byte], outLen: Int)(using AllowUnsafe): Int = -1
        def probeAvailable()(using AllowUnsafe): Boolean                                         = true
    end StubBindings

    private class StubProvider(bindings: StubBindings) extends SslLibProvider:
        def name                                                     = "stub"
        def priority                                                 = 0
        def libraryIds: Chunk[String]                                = Chunk("stub")
        private[internal] def lib(using AllowUnsafe): SslLibBindings = bindings
    end StubProvider

    private def build(bindings: StubBindings, config: NetTlsConfig = NetTlsConfig(), hostname: String = "example.com") =
        StubProvider(bindings).createEngine(config, hostname, isServer = false)

    "a built engine leaves the context's creation reference released" in {
        // The engine's SSL holds its own reference, so releasing the creation reference here is what lets the context go when the engine is
        // freed. Skipping it leaks a context per engine, which is invisible at every layer above this one.
        val bindings = StubBindings()
        val engine   = build(bindings)
        assert(
            bindings.ctxNewCalls == 1 && bindings.ctxFreeCalls == 1,
            s"context: new=${bindings.ctxNewCalls} free=${bindings.ctxFreeCalls}"
        )
        assert(bindings.sslFreeCalls == 0, "the SSL belongs to the engine and must outlive construction")
        engine.free()
        assert(bindings.sslFreeCalls == 1, s"the engine must release its SSL exactly once; got ${bindings.sslFreeCalls}")
    }

    "a context whose SSL could not be created is released" in {
        val bindings = StubBindings(sslNewResult = 0L)
        val ex       = intercept[NetTlsConfigException](build(bindings))
        discard(ex)
        assert(bindings.ctxFreeCalls == 1, s"expected the context released; got ${bindings.ctxFreeCalls}")
        assert(bindings.sslFreeCalls == 0, "no SSL was created, so none may be freed")
    }

    "a context rejected during configuration is released before any SSL exists" in {
        val bindings = StubBindings(minMaxVersionResult = -1)
        val ex       = intercept[NetTlsConfigException](build(bindings))
        discard(ex)
        assert(bindings.sslNewCalls == 0, "configuration failed, so no SSL should have been created")
        assert(bindings.ctxFreeCalls == 1, s"expected the context released; got ${bindings.ctxFreeCalls}")
    }

    "an identity that cannot be bound fails closed and releases both the SSL and the context" in {
        // A verifying client with no reference identity binds an unmatchable one so the handshake rejects every peer. When THAT fails there is
        // no name bound at all and the handshake would take any chain-valid certificate, so the engine build must fail rather than proceed.
        // It fails after the SSL exists, which is the path that would otherwise strand the SSL and its two memory BIOs.
        val bindings = StubBindings(unmatchableResult = 0)
        val ex       = intercept[NetTlsConfigException](build(bindings, hostname = ""))
        discard(ex)
        assert(bindings.unmatchableCalls == 1, "the unmatchable identity should have been attempted")
        assert(bindings.sslFreeCalls == 1, s"expected the SSL released on the failure path; got ${bindings.sslFreeCalls}")
        assert(bindings.ctxFreeCalls == 1, s"expected the context released; got ${bindings.ctxFreeCalls}")
        assert(bindings.connectStateCalls == 0, "the engine must not reach role selection after a failed identity bind")
    }

    "a verifying client whose reference identity binds keeps the unmatchable fallback unused" in {
        val bindings = StubBindings()
        val engine   = build(bindings)
        assert(bindings.unmatchableCalls == 0, "a host that bound needs no unmatchable fallback")
        assert(bindings.connectStateCalls == 1, "the client role should have been selected")
        engine.free()
    }

    /** Sweeps every fallible call in engine construction and asserts the same ownership invariant on all of them.
      *
      * The leaves above pin four paths that someone thought to write down, and the context leak this suite exists for lived on a fifth,
      * the successful one. Six more throwing paths carry no leaf at all: the two PEM reads, the configured CA load, the certificate and
      * key load, the system-trust load, and a reference identity that fails to bind and then fails again on the fallback. Rather than add
      * six more hand-written cases, this drives construction once per fallible call, failing that call and no other, and asserts that
      * whatever happened, nothing native is left outstanding.
      *
      * The invariant is uniform because it has to hold on every path: the context and the SSL are either owned by a returned engine, and
      * released when it is freed, or released by construction itself. There is no third outcome, and a failure that produces one is a leak
      * whether or not anyone predicted the path.
      */
    "every fallible call in engine construction leaves nothing outstanding" in {
        Scope.run(Path.run(Path.tempDir("kyo-tls-ownership").map { dir =>
            val material = dir / "material.pem"
            // The stub never parses it; the file only has to be readable, because readPem fails closed on a path it cannot read and that
            // is one of the construction failures under test.
            material.write("stub material, never parsed").andThen {
                val configured = Present(material.unsafe.show)
                // Three shapes, chosen so that between them construction reaches every fallible call: a verifying client with a host
                // reaches the system trust load and the reference identity, the same client with no host reaches the fail-closed
                // fallback, and the configured client reaches the CA load and the certificate load.
                val shapes = Seq(
                    ("verifying client", NetTlsConfig(), "example.com"),
                    ("verifying client with no host", NetTlsConfig(), ""),
                    (
                        "client with a configured CA and certificate",
                        NetTlsConfig(caCertPath = configured, certChainPath = configured, privateKeyPath = configured),
                        "example.com"
                    )
                )

                // Returns whether construction failed. A NetTlsConfigException is the expected shape of every construction failure, so
                // anything else escapes and fails the leaf rather than being counted as a covered path.
                def buildAndRelease(bindings: StubBindings, config: NetTlsConfig, hostname: String): Boolean =
                    try
                        StubProvider(bindings).createEngine(config, hostname, isServer = false).free()
                        false
                    catch case _: NetTlsConfigException => true

                val plans = shapes.map { (label, config, hostname) =>
                    val counting = StubBindings()
                    discard(buildAndRelease(counting, config, hostname))
                    (label, config, hostname, counting.fallibleCallCount)
                }

                val runs = plans.flatMap { (label, config, hostname, calls) =>
                    (1 to calls).map { n =>
                        val bindings = StubBindings()
                        bindings.failAt = n
                        val failed = buildAndRelease(bindings, config, hostname)
                        val problems =
                            (if bindings.liveCtxs.nonEmpty then
                                 Seq(s"$label, failing call $n: ${bindings.liveCtxs.size} context(s) stranded")
                             else Seq.empty) ++
                                (if bindings.liveSsls.nonEmpty then
                                     Seq(s"$label, failing call $n: ${bindings.liveSsls.size} SSL(s) stranded")
                                 else Seq.empty) ++
                                (if bindings.strayFrees > 0 then
                                     Seq(s"$label, failing call $n: freed ${bindings.strayFrees} handle(s) that were not outstanding")
                                 else Seq.empty)
                        (failed, problems)
                    }
                }

                // Fixture check before the negative is trusted: a sweep that never made construction fail, or that visited no calls at
                // all, would report clean for the same reason a broken injector would.
                assert(plans.forall(_._4 > 0), s"construction made no fallible calls: ${plans.map(p => p._1 -> p._4)}")
                assert(runs.exists(_._1), "no injected failure ever failed a build, so the sweep proves nothing")
                val problems = runs.flatMap(_._2)
                assert(problems.isEmpty, problems.mkString("; "))
            }
        }))
    }

end SslLibProviderOwnershipTest
