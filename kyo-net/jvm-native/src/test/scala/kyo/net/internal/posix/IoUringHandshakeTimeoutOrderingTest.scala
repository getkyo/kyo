package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetConfig
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.TlsTestCertShared
import kyo.net.internal.TlsProviderPlatform

/** Deterministic, memory-tool-free reproduce-first for the io_uring handshake-timeout use-after-free.
  *
  * At the resource level this is an ORDERING violation: when a finite `handshakeTimeout` reaps a server TLS handshake that is parked in
  * `awaitReadCiphertext` with an in-flight io_uring recv SQE pointed at the handle's `readBuffer`, the teardown must NOT free that `readBuffer`
  * while the recv SQE is still kernel-owned (in-flight count > 0). Freeing it directly (`PosixHandle.close`) while the recv is
  * in flight would violate that ordering; the free routes through `ioDriver.closeHandle`, which on io_uring DEFERS the free until the in-flight recv CQE reaps and
  * forces that recv to complete with `shutdown(SHUT_RDWR)`.
  *
  * This test asserts that ordering directly, no Valgrind / ASan required. It drives the real `PosixTransport.handleAccepted` teardown path on a
  * REAL io_uring ring (a server `listen(tls)` plus a raw client that completes the TCP accept but sends no ClientHello, so the server handshake
  * parks with an in-flight recv), then lets the finite `handshakeTimeout` fire. The teardown's `shutdown(SHUT_RDWR)` is the ONLY thing that can
  * complete that in-flight recv (the stalled client sends nothing and io_uring's cancel submits no async-cancel SQE), so the correct teardown makes
  * the recv CQE reap and the deferred close frees the `readBuffer` AS PART of that reap. Freeing the `readBuffer` synchronously in the teardown while
  * the recv is still kernel-owned would instead leave that recv CQE unable to reap. The observation mechanism is the [[RecordingIoUringBindings]]
  * reap latch, exactly as the sibling `IoUringDriverTest` -> "closeHandle defers PosixHandle.close until the in-flight read CQE is reaped" uses it,
  * but driven through the handshake-timeout teardown rather than a direct `closeHandle` call.
  *
  * io_uring-only (`assumeUring`): this is the validated platform exception. The poller path is already UAF-safe (it never hands the kernel the
  * read buffer), so the deferred-free ordering distinction only exists on io_uring. The cross-backend reap BEHAVIOR is proven by the shared soak
  * in `TransportHandshakeTimeoutTest`; this test is the deterministic ordering guard that complements it.
  */
class IoUringHandshakeTimeoutOrderingTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def assumeTls(): Unit =
        if !TlsProviderPlatform.hasAvailableEngine then cancel("no TLS engine provider is available on this host")

    /** Build a REAL io_uring ring at depth 256, wrap it in a [[RecordingIoUringBindings]] spy (every op runs for real; the spy only observes the
      * recv buffers and fires a latch on each CQE reap), build an [[IoUringDriver]] over it, start its reap loop, and build a [[PosixTransport]]
      * over the SAME driver, the real socket bindings and `clock`. Tears the ring down on exit.
      */
    private def withRecordingTransport[A](clock: Clock)(
        body: (PosixTransport, RecordingIoUringBindings) => A < (Abort[NetException | Closed] & Async & Scope)
    )(using Frame): A < (Abort[NetException | Closed] & Async) =
        // 256 fits the privileged-container cgroup `io_uring.max` cap, where the production default depth is rejected and falls back to epoll.
        // assumeUring probes at this same depth, so the gate matches the ring the transport builds.
        val depth     = 256
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        // io_uring_queue_init returns 0 / -errno and does NOT set the global errno; read the return value, not the stale
        // captured errno (a prior call's leftover errno would spuriously fail this).
        if rc != 0 then
            realRing.close()
            throw Closed("IoUringHandshakeTimeoutOrderingTest", summon[Frame], s"queue_init rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        // backendIsEpoll = false: the driver is io_uring, so the regular-file fallback never applies.
        val transport = TestTransports.forTesting(driver, sock, backendIsEpoll = false, clock = clock)
        // Scope.run discharges the body's Scope.ensure-registered listener/socket cleanup (see the "in" leaf below) before the driver itself
        // is torn down, so those finalizers still have a live ring to run their close() calls against.
        Sync.ensure(Sync.defer(driver.close()))(Scope.run(body(transport, recording)))
    end withRecordingTransport

    /** Open a raw client socket and connect it to `port` on 127.0.0.1, returning the client fd. The client then sends NOTHING, so the server-side
      * TLS handshake it triggers parks waiting for a ClientHello. The connect completes inline on loopback.
      */
    private def rawStallingClient(port: Int)(using Frame, kyo.test.AssertScope): Int < (Abort[Closed] & Async) =
        val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0))).map(_ => client)
    end rawStallingClient

    "IoUringDriver handshake-timeout teardown" - {

        "the stalled-handshake recv readBuffer is freed only AFTER its in-flight recv CQE reaps, never while the recv is kernel-owned" in {
            PosixTestSockets.assumeUring()
            assumeTls()
            given Frame = Frame.internal
            val timeout = 1.second
            TlsTestCertShared.writePems.map { case (certPath, keyPath) =>
                val serverTls =
                    NetTlsConfig(certChainPath = Present(certPath), privateKeyPath = Present(keyPath), handshakeTimeout = timeout)
                Clock.withTimeControl { tc =>
                    Clock.get.map { clock =>
                        withRecordingTransport(clock) { (transport, recording) =>
                            // The plaintext raw client never sends a ClientHello, so the server handshake parks in awaitReadCiphertext with exactly
                            // ONE in-flight io_uring op: the recv SQE into the server handle's readBuffer (neither peer sends, so no other CQE
                            // precedes the reap).
                            for
                                listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls) { _ => () }.safe.get
                                _        <- Scope.ensure(Sync.defer(listener.close()))
                                clientFd <- rawStallingClient(listener.port)
                                _        <- Scope.ensure(Sync.defer(discard(sock.close(clientFd))))
                                // During the stalled handshake the ONLY driver recv is the server handshake's awaitRead, so the first recorded
                                // recv buffer IS the server handle's readBuffer (the kernel-owned buffer at the heart of the use-after-free).
                                _ <- recording.firstRecv.safe.get
                                recvBuf = recording.recvBufs.peek()
                                // Time is held, so the deadline cannot fire before the recv is in hand; fire it at its instant.
                                _ <- tc.awaitPendingSleepers(1)
                                _ <- tc.advance(timeout)
                                // Barrier on the non-wake reap count, NOT a FIFO reap waiter. This leaf's non-wake reaps are exactly (1) the accept
                                // and (2) the teardown-forced recv, and a FIFO waiter could be consumed by the accept reap (unordered vs the client's
                                // connect returning). The count is bumped only AFTER the driver's complete() ran the deferred close, so reaching 2
                                // means the recv CQE reaped and the readBuffer free ran as part of that reap. A teardown that freed the readBuffer
                                // synchronously while the recv was kernel-owned, issuing no shutdown, leaves the recv unable to reap, and the leaf
                                // hangs to its cap.
                                _ <- recording.awaitSeenCount(2).safe.get
                            yield assert(
                                recvBuf.isClosed,
                                "deferred PosixHandle.close must free the recv readBuffer once its in-flight recv CQE reaps"
                            )
                        }
                    }
                }
            }
        }
    }

end IoUringHandshakeTimeoutOrderingTest
