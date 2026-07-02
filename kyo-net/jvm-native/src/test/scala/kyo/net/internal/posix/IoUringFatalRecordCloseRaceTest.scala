package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.tls.TlsEngine
import kyo.net.internal.tls.TlsEngineLoopback
import kyo.net.internal.tls.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first regression guard for the io_uring fatal-TLS-record teardown race (P10, the residual behind the io_uring/boringssl
  * `IoUringMutualTlsStressTest` intermittent failure, cell-8's #10 in the flaky inventory).
  *
  * The defect: [[TlsEngineIo.feedAndDecrypt]] (shared by [[PollerIoDriver]] and [[IoUringDriver]]) tears a connection down on a fatal TLS
  * record (`readPlain == -2`, RFC 5246 7.2.2) via an `onFatal` hook. For `PollerIoDriver` this hook is `() => handle.requestClose()`, which is
  * safe there ONLY because every poller read runs inside [[PosixHandle.beginDispatch]]/`endDispatch`, so `requestClose` correctly DEFERS the
  * free until that dispatch (and any concurrent write) releases the guard. `IoUringDriver` never acquires the guard for reads (its reads are
  * async, kernel-owned SQEs tracked by its own `inFlight`/`pendingCloses`/`closeAfterDrain` bookkeeping instead, see
  * [[PosixHandle.isClosing]]'s doc), so a bare `requestClose()` there would free the handle's buffers and TLS engine IMMEDIATELY and
  * UNCONDITIONALLY -- including while ANOTHER kernel-owned op for the SAME handle is still genuinely in flight (the STARTTLS upgrade-handoff
  * design deliberately allows more than one recv in flight per handle across a handshake). A second op that already captured a reference to
  * those buffers before the free would then touch freed off-heap memory when it runs, surfacing as a native "Already closed" failure far from
  * the actual defect. `IoUringDriver`'s real call site routes `onFatal` through `closeHandle(handle)` instead, which defers the actual free
  * until every op still in flight for the handle (including whichever read hit the fatal record) has drained via the driver's own bookkeeping.
  *
  * This test drives the exact mechanism directly (no `Connection`/`Transport`, no STARTTLS, no timing dependence): a REAL in-flight recv is
  * armed and never completes (nothing is ever sent on `client`), so the driver's own `inFlight` count for the handle is genuinely non-zero for
  * the whole test. A fatal record is then fed through the SAME driver call production uses
  * (`feedAndDecrypt(engine, cipher, len, handle, onFatal)`), and the observable side effect -- whether `handle.tls` survives -- is captured
  * synchronously inside the SAME engine op that ran the feed, communicated back to the test fiber via a promise (no polling, no timing window:
  * `onFatal`'s synchronous portion has either freed the resources or deferred it by the time `feedAndDecrypt` returns).
  *
  * Fails-before / passes-after is demonstrated by the `onFatal` callback the test itself supplies: `requestClose()` (the shape a bare,
  * guard-only teardown would take on io_uring) reproduces the bug (buffers freed immediately, out from under the still-in-flight recv);
  * `closeHandle(handle)` (the actual production wiring at [[IoUringDriver]]'s real call site) is the fix. Both leaves exercise the identical
  * shared `feedAndDecrypt` fatal-record path; only the `onFatal` hook differs, isolating exactly the mechanism the fix changes.
  *
  * Engine ownership (avoiding a SEPARATE double-free hazard in the test itself, not the production defect under test): the server engine is
  * built via [[TlsRealEngines.singleEngine]] (caller-owned, no auto-free), matching [[IoUringEngineFifoFreeOrderingTest]]'s precedent for a
  * driver test that attaches a real engine to a handle and drives its close. `TlsRealEngines.withEngines` auto-frees both engines the instant
  * the test body's result value completes, which races the driver's OWN asynchronous close teardown (still running on the reap carrier)
  * touching the very same engine; `singleEngine` leaves the free entirely to whichever path (the driver's `engineFreeSink`, or this test's own
  * cleanup) actually needs it, and `NativeSslEngine.free()` is CAS-guarded exactly-once, so calling it from both is harmless.
  *
  * io_uring-only ([[PosixTestSockets.assumeUring]]): the poller backends synchronously hold the dispatch guard around every read, so
  * `requestClose()` is already safe there and this race does not apply.
  *
  * Anti-flakiness: no `Thread.sleep`, no busy-spin, no polling loop. The recv never completes (no peer send), and the only wait is a single
  * promise `.get` on a signal the test's own engine op completes synchronously before returning.
  */
class IoUringFatalRecordCloseRaceTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Encrypt one application-data TLS record through `engine` and flip a body byte so its AEAD tag fails, mirroring
      * [[TlsEngineIoCorruptRecordTest]]'s corruption recipe.
      */
    private def corruptedApplicationRecord(engine: TlsEngine)(using AllowUnsafe): Array[Byte] =
        val record    = TlsEngineLoopback.encrypt(engine, "TAMPERED-application-record".getBytes("UTF-8"))
        val corrupted = record.clone()
        corrupted(corrupted.length - 1) = (corrupted(corrupted.length - 1) ^ 0xff).toByte
        corrupted
    end corruptedApplicationRecord

    /** Drive one fatal-record feed through the handle's real staging buffer with the given `onFatal`, and report synchronously (via the
      * returned promise) whether `handle.tls` survived the call. Runs as a single driver engine op, exactly mirroring how
      * [[IoUringDriver]]'s real read-completion closure invokes `feedAndDecrypt`.
      */
    private def feedFatalAndObserve(
        driver: IoUringDriver,
        engine: TlsEngine,
        handle: PosixHandle,
        corrupted: Array[Byte],
        onFatal: () => Unit
    )(using AllowUnsafe, Frame): Promise.Unsafe[Boolean, Abort[Closed]] =
        val observed = Promise.Unsafe.init[Boolean, Abort[Closed]]()
        driver.submitEngineOp { () =>
            val cipher = Buffer.fromArray[Byte](corrupted)
            val plain =
                try driver.feedAndDecrypt(engine, cipher, corrupted.length, handle, onFatal)
                finally cipher.close()
            discard(plain) // empty on the fatal path; not the subject of this test (TlsEngineIoCorruptRecordTest covers that)
            observed.completeDiscard(Result.succeed(handle.tls.isDefined))
        }
        observed
    end feedFatalAndObserve

    /** Poll a real condition until it holds or the bound elapses, re-checking each turn after a short Async.sleep. Mirrors
      * [[IoUringCloseHalfCloseRaceTest.awaitCondition]] / [[IoUringHandshakeTimeoutOrderingTest.awaitCondition]].
      */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    private def newRing()(using Frame): Buffer[Byte] =
        val depth = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
        val uring = Ffi.load[IoUringBindings]
        val ring  = Buffer.alloc[Byte](uring.kyo_uring_sizeof().toInt)
        val rc    = uring.io_uring_queue_init(depth, ring, 0)
        if rc != 0 then
            ring.close()
            throw Closed("IoUringFatalRecordCloseRaceTest", summon[Frame], s"queue_init failed: rc=$rc")
        ring
    end newRing

    "IoUringDriver fatal-TLS-record teardown while another op is in flight" - {

        "a bare requestClose() onFatal frees the handle immediately, out from under a still-in-flight recv (reproduces the bug)" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            given Frame = Frame.internal
            val uring   = Ffi.load[IoUringBindings]
            val driver  = TestDrivers.forBindings(uring, newRing())
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                    val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                    Sync.ensure(Sync.defer { clientEngine.free(); serverEngine.free() }) {
                        assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake did not complete")
                        val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                        acceptedH.tls = Present(serverEngine)
                        // Arm a real in-flight recv: nothing is ever sent on `client`, so the driver's own in-flight count for this handle
                        // stays non-zero for the whole test (the exact "another kernel-owned op still in flight" condition the bug ignores).
                        val recvPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(acceptedH, recvPromise)
                        val corrupted = corruptedApplicationRecord(clientEngine)
                        val observed  = feedFatalAndObserve(driver, serverEngine, acceptedH, corrupted, () => acceptedH.requestClose())
                        observed.safe.get.map { tlsSurvived =>
                            assert(
                                !tlsSurvived,
                                "expected the bug to reproduce: a bare requestClose() onFatal must free handle.tls immediately even " +
                                    "though a recv is still genuinely in flight (proves the test's own mechanism, not a vacuous pass)"
                            )
                            // Close both sides: `client` (the peer) and `accepted` (the driver-managed handle this test drove directly, never
                            // wired to a Connection). requestClose() frees handle.tls but never the raw fd; only closing `client` leaves
                            // `accepted`'s fd in CLOSE_WAIT forever (driver.close() below tears down the ring, not individually-registered
                            // handle fds).
                            discard(sock.close(client))
                            discard(sock.close(accepted))
                            succeed
                        }
                    }
                }
            }
        }

        "closeHandle(handle) onFatal (the real IoUringDriver wiring) defers the free until the in-flight recv is accounted for" in {
            PosixTestSockets.assumeUring()
            TlsRealEngines.assumeTlsReady()
            given Frame = Frame.internal
            val uring   = Ffi.load[IoUringBindings]
            val driver  = TestDrivers.forBindings(uring, newRing())
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                    val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                    val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                    // The driver's OWN closeHandle machinery (triggered inside the test body below) asynchronously touches serverEngine on the
                    // reap carrier (shutdownTls's engine.shutdownStep(), a native SSL_shutdown call) well AFTER this test body's result value
                    // completes: closeHandle only ENQUEUES its cancel/shutdown/registerDeferredClose work. A plain Sync.ensure cleanup racing
                    // that async work with its own serverEngine.free() is a genuine native use-after-free (SSL_shutdown on an already-freed
                    // session) -- a hazard in THIS TEST's construction, not the production defect under test. The body therefore waits for
                    // acceptedH.tls to actually go Absent (the driver's own close fully settled: cancel -> shutdownTls -> the SHUT_RD-forced
                    // EOF that finally drains the still-armed recv -> closeNow -> PosixHandle.close) before returning, so the cleanup's free
                    // never races the driver's own engine touch.
                    Sync.ensure(Sync.defer { clientEngine.free(); serverEngine.free() }) {
                        assert(TlsEngineLoopback.handshake(clientEngine, serverEngine), "handshake did not complete")
                        val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                        acceptedH.tls = Present(serverEngine)
                        val recvPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(acceptedH, recvPromise)
                        val corrupted = corruptedApplicationRecord(clientEngine)
                        // The real production onFatal (IoUringDriver.scala's actual read-completion call site).
                        val observed = feedFatalAndObserve(driver, serverEngine, acceptedH, corrupted, () => driver.closeHandle(acceptedH))
                        observed.safe.get.map { tlsSurvived =>
                            assert(
                                tlsSurvived,
                                "closeHandle(handle) must defer the free while the armed recv is still in flight (handle.tls must still be " +
                                    "Present); freeing here would UAF the still-outstanding recv's eventual CQE processing"
                            )
                            discard(sock.close(client))
                            awaitCondition(5.seconds)(!acceptedH.tls.isDefined).map { settled =>
                                assert(settled, "the driver's own close sequence never settled (a hang, not the race under test)")
                                succeed
                            }
                        }
                    }
                }
            }
        }
    }

end IoUringFatalRecordCloseRaceTest
