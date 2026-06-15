package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.TlsTestCertShared
import kyo.net.TransportConfig
import kyo.net.internal.tls.TlsProviderPlatform

/** Deterministic, memory-tool-free reproduce-first for issue #243 (the io_uring handshake-timeout use-after-free).
  *
  * #243 at the resource level is an ORDERING violation: when a finite `handshakeTimeout` reaps a server TLS handshake that is parked in
  * `awaitReadCiphertext` with an in-flight io_uring recv SQE pointed at the handle's `readBuffer`, the teardown must NOT free that `readBuffer`
  * while the recv SQE is still kernel-owned (in-flight count > 0). The buggy teardown freed it directly (`PosixHandle.close`) while the recv was
  * in flight; the fix routes the free through `ioDriver.closeHandle`, which on io_uring DEFERS the free until the in-flight recv CQE reaps and
  * forces that recv to complete with `shutdown(SHUT_RDWR)`.
  *
  * This test asserts that ordering directly, no Valgrind / ASan required. It drives the real `PosixTransport.handleAccepted` teardown path on a
  * REAL io_uring ring (a server `listen(tls)` plus a raw client that completes the TCP accept but sends no ClientHello, so the server handshake
  * parks with an in-flight recv), then lets the finite `handshakeTimeout` fire. The teardown's `shutdown(SHUT_RDWR)` is the ONLY thing that can
  * complete that in-flight recv (the stalled client sends nothing and io_uring's cancel submits no async-cancel SQE), so on the fixed code the
  * recv CQE reaps and the deferred close frees the `readBuffer` AS PART of that reap. On the buggy code the `readBuffer` is freed synchronously in
  * the teardown while the recv is still kernel-owned, and that recv CQE never reaps. The observation mechanism is the [[RecordingIoUringBindings]]
  * reap latch, exactly as the sibling `IoUringDriverTest` -> "closeHandle defers PosixHandle.close until the in-flight read CQE is reaped" uses it,
  * but driven through the #243 teardown rather than a direct `closeHandle` call.
  *
  * io_uring-only (`assumeUring`): this is the validated platform exception. The poller path is already UAF-safe (it never hands the kernel the
  * read buffer), so the deferred-free ordering distinction only exists on io_uring. The cross-backend reap BEHAVIOR is proven by the shared soak
  * in `TransportHandshakeTimeoutTest`; this test is the deterministic fail-on-buggy ordering guard that complements it.
  */
class IoUringHandshakeTimeoutOrderingTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    // ioPoolSize = 1 pins the io_uring ring depth to max(256, 1*64) = 256, which fits the privileged-container cgroup `io_uring.max` cap (the
    // production default depth is rejected there and falls back to epoll). assumeUring probes at this same depth, so the gate matches the ring
    // the transport will build. handshakeTimeout = 1s is finite (so the deadline arms and reaps the stalled handshake) yet long enough that the
    // test reliably detects the in-flight recv and registers its reap latch BEFORE the deadline fires.
    private val transportConfig: TransportConfig = TransportConfig.default.copy(ioPoolSize = 1, handshakeTimeout = 1.second)

    private def assumeTls(): Unit =
        if !TlsProviderPlatform.registered.exists(_.isAvailable) then cancel("no TLS provider available on this backend")

    /** Build a REAL io_uring ring at depth 256, wrap it in a [[RecordingIoUringBindings]] spy (every op runs for real; the spy only observes the
      * recv buffers and fires a latch on each CQE reap), build an [[IoUringDriver]] over it, start its reap loop, and build a [[PosixTransport]]
      * over the SAME driver and the real socket bindings. Tears the ring down on exit.
      */
    private def withRecordingTransport[A](
        body: (PosixTransport, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = 256
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        // io_uring_queue_init returns 0 / -errno and does NOT set the global errno; read the return value, not the stale
        // captured errno (a prior call's leftover errno would spuriously fail this, #258).
        if rc != 0 then
            realRing.close()
            throw Closed("IoUringHandshakeTimeoutOrderingTest", summon[Frame], s"queue_init rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        // backendIsEpoll = false: the driver is io_uring, so the regular-file fallback never applies.
        val transport = TestTransports.forTesting(transportConfig, driver, sock, backendIsEpoll = false)
        Sync.ensure(Sync.defer(transport.close()))(body(transport, recording))
    end withRecordingTransport

    /** Open a raw client socket and connect it to `port` on 127.0.0.1, returning the client fd. The client then sends NOTHING, so the server-side
      * TLS handshake it triggers parks waiting for a ClientHello. The connect completes inline on loopback.
      */
    private def rawStallingClient(port: Int)(using Frame, kyo.test.AssertScope): Int < (Abort[Closed] & Async) =
        val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0))).map(_ => client)
    end rawStallingClient

    /** Poll a real condition (no settle) until it holds or the bound elapses, re-checking each turn after a short Async.sleep. Returns whether the
      * condition held within the bound. Used to wait on the in-flight recv being submitted and on the recv buffer being closed: both are real
      * driver-carrier state transitions, not a timer-based settle.
      */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "IoUringDriver handshake-timeout teardown (#243)" - {

        "the stalled-handshake recv readBuffer is freed only AFTER its in-flight recv CQE reaps, never while the recv is kernel-owned" in {
            PosixTestSockets.assumeUring(transportConfig)
            assumeTls()
            given Frame = Frame.internal
            TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
                val serverTls = NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath))
                withRecordingTransport { (transport, recording) =>
                    // A finite, short handshakeTimeout so the deadline reaps the stalled server handshake. The plaintext raw client never sends a
                    // ClientHello, so the server handshake parks in awaitReadCiphertext with exactly ONE in-flight io_uring op: the recv SQE into
                    // the server handle's readBuffer (the server has sent nothing, the client has sent nothing, so no other CQE precedes the reap).
                    transport.listen("127.0.0.1", 0, 16, serverTls) { _ => () }.safe.get.map { listener =>
                        rawStallingClient(listener.port).map { clientFd =>
                            // Wait until the server handshake has submitted its in-flight recv SQE. The recording spy records every recv buffer the
                            // driver hands the kernel; during the stalled handshake the ONLY driver recv is the server handshake's awaitRead, so the
                            // first recorded recv buffer IS the server handle's readBuffer (the kernel-owned buffer at the heart of #243).
                            awaitCondition(5.seconds)(!recording.recvBufs.isEmpty).map { sawRecv =>
                                assert(sawRecv, "the server handshake must submit an in-flight recv SQE before the deadline reaps it")
                                val recvBuf = recording.recvBufs.peek()
                                assert(recvBuf != null, "recorded recv buffer must be present")
                                // The recv is in flight (kernel-owned). It must NOT be closed yet: the handshake is parked, nothing has reaped it.
                                assert(
                                    !recvBuf.isClosed,
                                    "the in-flight recv readBuffer must be open while the recv SQE is kernel-owned (before any teardown)"
                                )
                                // Register a reap latch NOW, before the deadline fires. On the FIXED teardown the deadline routes the free through
                                // ioDriver.closeHandle (deferred) and forces the recv to EOF via shutdown(SHUT_RDWR); that recv CQE reaps, completing
                                // this latch, and the deferred close frees the readBuffer AS PART of that reap (closeNow runs inside complete(), before
                                // cqe_seen fires this latch). On the BUGGY teardown the readBuffer is freed synchronously in the teardown while the recv
                                // is still kernel-owned, and no shutdown is issued, so the recv CQE NEVER reaps and this latch NEVER completes.
                                val reap = recording.awaitReap()
                                // Let the finite handshakeTimeout fire and run the #243 teardown, then wait for the recv CQE to reap. The bound is
                                // generous; the deadline is short, so on the fixed code the reap arrives well within it.
                                Abort.run[Timeout](Async.timeout(8.seconds)(reap.safe.get)).map { reapOutcome =>
                                    // Snapshot the buffer-close state at the moment the reap resolved (or the bound expired).
                                    val recvClosedAfter = recvBuf.isClosed
                                    discard(sock.close(clientFd))
                                    listener.close()
                                    reapOutcome match
                                        case Result.Success(_) =>
                                            // FIXED path: the in-flight recv CQE reaped (shutdown-forced EOF), and the deferred PosixHandle.close ran
                                            // as part of that reap, so the readBuffer is now closed. The ordering invariant held: the kernel-owned
                                            // buffer was never freed while the recv SQE was in flight; the free waited for the reap.
                                            assert(
                                                recvClosedAfter,
                                                "deferred PosixHandle.close must free the recv readBuffer once its in-flight recv CQE reaps"
                                            )
                                        case _ =>
                                            // BUGGY path: the recv CQE never reaped within the bound. Characterize WHY, so the failure names the #243
                                            // ordering violation rather than a generic timeout. The buggy teardown freed the readBuffer directly while
                                            // the recv SQE was still kernel-owned (in-flight count > 0) and issued no shutdown, so the recv never
                                            // completes: the buffer is observed CLOSED with no reaping CQE, which is exactly the use-after-free ordering.
                                            fail(
                                                s"#243 ordering violation: the in-flight recv CQE never reaped after the handshake-timeout teardown " +
                                                    s"(recv readBuffer isClosed=$recvClosedAfter while the recv SQE was still kernel-owned). The teardown " +
                                                    s"must defer the readBuffer free through ioDriver.closeHandle and force the recv to complete via " +
                                                    s"shutdown(SHUT_RDWR), so the kernel-owned buffer is freed only after its recv CQE reaps."
                                            )
                                    end match
                                }
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringHandshakeTimeoutOrderingTest
