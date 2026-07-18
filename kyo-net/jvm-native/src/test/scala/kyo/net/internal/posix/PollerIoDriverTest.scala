package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.TlsEngine
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Tests the readiness-to-completion [[PollerIoDriver]] over a real epoll (Linux) / kqueue (macOS/BSD) poller on the host that has the
  * syscalls.
  *
  * The driver's poll loop runs on its own `Fiber.initUnscoped` (started by `start()`); each scenario drives `write` / `awaitRead` /
  * `awaitWritable` over a connected loopback socket pair and awaits the deposited `Promise` via `.safe.get`. The epoll arm runs only on Linux
  * and the kqueue arm only on macOS/BSD; the other is skipped because its syscalls are absent.
  *
  * Covers: the IoDriver completion contract (echo bytes equal, peer-close EOF, peer-reset Closed); the per-direction keying (reads key on
  * `readFd`, writes on `writeFd`); the close ordering (closeHandle cancels before close; stale recycled-fd events are dropped); and the
  * indefinite poll wait (the wake mechanism returns it early when work arrives).
  */
class PollerIoDriverTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumePoller(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then
            cancel("PollerIoDriver needs epoll (Linux) or kqueue (macOS/BSD)")

    private def sock = Ffi.load[SocketBindings]

    /** Build a connected loopback socket pair and return (clientFd, acceptedFd). `connect`/`accept`/`close` are `@Ffi.blocking`. Both ends are
      * set non-blocking once the connection is established (the connect/accept above are blocking on purpose): the [[PollerIoDriver]] requires
      * non-blocking fds (its readiness model means a `recv`/`send` issued after a readiness event must never block), and in particular the
      * driver's synchronous `sendNow` path on JS must run on a non-blocking fd so a full send buffer returns EAGAIN rather than freezing the JS
      * event loop. Setting `O_NONBLOCK` here guarantees that invariant instead of relying on the test payloads staying small enough to never
      * fill the kernel buffer.
      */
    private def loopbackPair()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sock.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sock.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    // The connection is established; switch both ends non-blocking for the driver phase. Done after connect/accept because a
                    // non-blocking connect would return EINPROGRESS rather than the 0 asserted above. This honors the driver's non-blocking
                    // contract and makes the JS sendNow path provably unable to block the event loop.
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set_nonblocking(client) failed")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set_nonblocking(accepted) failed")
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

    /** Start a fresh driver, run `body` with it, then close it. The poll loop runs on the driver's own fiber for the duration. */
    private def withDriver[A](body: PollerIoDriver => A < (Abort[Closed] & Async))(using Frame): A < (Abort[Closed] & Async) =
        val driver = PollerIoDriver.init(kyo.net.TransportConfig.default)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withDriver

    /** Await a read on `handle` driven by the poller. */
    private def readVia(driver: PollerIoDriver, handle: PosixHandle)(using Frame): ReadOutcome < (Abort[Closed] & Async) =
        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
        driver.awaitRead(handle, promise)
        promise.safe.get
    end readVia

    "PollerIoDriver" - {
        "echo round-trip: 16 bytes written one side are read on the other" in {
            assumePoller()
            withDriver { driver =>
                loopbackPair().map { case (client, accepted) =>
                    val clientH   = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val payload   = Span.fromUnsafe(Array.tabulate[Byte](16)(i => (i + 1).toByte))
                    val w         = driver.write(clientH, payload, 0)
                    assert(w == WriteResult.Done, s"write result=$w")
                    readVia(driver, acceptedH).map {
                        case ReadOutcome.Bytes(got) =>
                            driver.closeHandle(clientH)
                            driver.closeHandle(acceptedH)
                            assert(got.toArray.toList == payload.toArray.toList)
                        case other =>
                            driver.closeHandle(clientH)
                            driver.closeHandle(acceptedH)
                            fail(s"expected bytes, got $other")
                    }
                }
            }.map(_ => succeed)
        }

        "zero-length read on peer-close is EOF (empty Span, not a failure)" in {
            assumePoller()
            withDriver { driver =>
                loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    // Peer (client) closes orderly; the accepted side should observe EOF.
                    sock.close(client).safe.get.map { _ =>
                        readVia(driver, acceptedH).map { outcome =>
                            driver.closeHandle(acceptedH)
                            outcome match
                                case ReadOutcome.Bytes(s) if s.isEmpty => succeed // empty Bytes: EOF as a zero-length read
                                case ReadOutcome.PeerFin | ReadOutcome.CleanClose | ReadOutcome.LocalShutdown => succeed
                                case ReadOutcome.Bytes(s) => fail(s"expected EOF, got ${s.size} bytes")
                                case other                => fail(s"expected EOF, got $other")
                            end match
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "peer-reset surfaces Closed (or terminal EOF)" in {
            assumePoller()
            withDriver { driver =>
                loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    // Force an RST: set SO_LINGER {on=1, linger=0} on the client so its close sends a reset rather than a FIN.
                    val linger = Buffer.alloc[Byte](8)
                    linger.set(0, 1.toByte) // l_onoff = 1
                    var i = 1
                    while i < 8 do
                        linger.set(i, 0.toByte) // l_linger = 0
                        i += 1
                    val soLinger = if PosixConstants.isMacOrBsd then 0x0080 else 13
                    discard(sock.setsockopt(client, PosixConstants.SOL_SOCKET, soLinger, linger, 8))
                    linger.close()
                    sock.close(client).safe.get.map { _ =>
                        Abort.run[Closed](readVia(driver, acceptedH)).map { result =>
                            driver.closeHandle(acceptedH)
                            // The reset surfaces as Closed; some kernels deliver the RST only after an EOF read, so accept either a Closed
                            // failure or a terminal EOF (both are non-data terminal outcomes, never live bytes).
                            result match
                                case Result.Failure(_: Closed) => succeed
                                case Result.Success(ReadOutcome.PeerFin) | Result.Success(ReadOutcome.CleanClose) | Result.Success(
                                        ReadOutcome.LocalShutdown
                                    ) => succeed
                                case Result.Success(ReadOutcome.Bytes(s)) if s.isEmpty => succeed // empty Bytes: EOF as a zero-length read
                                case other                                             => fail(s"expected Closed or EOF, got $other")
                            end match
                        }
                    }
                }
            }
        }

        "awaitRead registers on readFd, awaitWritable on writeFd (split-fd handle)" in {
            assumePoller()
            // Verify the per-direction keying with a RecordingPollerBackend over the real epoll/kqueue and a split-fd stdio handle
            // (readFd=0, writeFd=1): the spy records which fd each registration targeted before delegating to the real backend (the real
            // epoll_ctl/kevent for fd 0/1 may succeed or be rejected, but the recorded fds are observed regardless). awaitRead must register
            // the readFd and awaitWritable the writeFd; only a split handle (readFd != writeFd) can distinguish them.
            // The registrations run asynchronously on the driver's change-FIFO worker, so synchronize on the actual events: the spy completes
            // registeredRead(fd) when registerRead(fd) runs and registeredWrite-equivalent via the write per-fd path. The test awaits both
            // per-fd latches, then asserts, proceeding the moment the registrations happen rather than after a guessed delay.
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(spy, pollerFd)
            // The change FIFO drains only on the poll-loop carrier, so start the poll loop; it bounded-waits on the idle poller fd and drains the
            // registrations each cycle. The registrations and the fifoBarrier below settle on the real change/engine drain, no sleep.
            discard(driver.start())
            val stdioH = PosixHandle.stdio(PosixHandle.DefaultReadBufferSize)
            assert(stdioH.readFd != stdioH.writeFd, "split handle must have distinct fds")
            // A per-fd promise for the write registration: completes when registerWrite(writeFd) has recorded the fd. Watch the spy's
            // registeredWriteFds via a fifoBarrier (the write registration is the second change; its execution latch is the read latch's twin).
            val rp = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
            driver.awaitRead(stdioH, rp)
            val wp = Promise.Unsafe.init[Unit, Abort[Closed]]()
            driver.awaitWritable(stdioH, wp)
            // Latch on the read registration executing; then settle the change FIFO so the write registration has also executed.
            spy.registeredRead(stdioH.readFd).safe.get.andThen(fifoBarrier(driver).safe.get).andThen {
                driver.close()
                assert(
                    spy.registeredReadFds.contains(stdioH.readFd),
                    s"read registered on ${spy.registeredReadFds}, expected ${stdioH.readFd}"
                )
                assert(
                    spy.registeredWriteFds.contains(stdioH.writeFd),
                    s"write registered on ${spy.registeredWriteFds}, expected ${stdioH.writeFd}"
                )
            }
        }

        "partial write on a non-blocking socket returns Partial(remaining) when the send buffer fills" in {
            // Real non-blocking partial-write test. Uses kyo_posix_set_nonblocking (the C shim that fixes the arm64 variadic ABI bug) to
            // put the write-end socket into non-blocking mode, then floods the kernel send buffer until `send` returns EAGAIN. This
            // validates that: (1) kyo_posix_set_nonblocking actually engages O_NONBLOCK (the regression guard), and (2) the driver's write
            // state machine correctly surfaces Partial(remaining) when the kernel rejects a send with EAGAIN/EWOULDBLOCK.
            assumePoller()
            withDriver { driver =>
                loopbackPair().map { case (client, accepted) =>
                    // Set the client write-end non-blocking via the C shim. The shim wraps fcntl variadically so O_NONBLOCK is not silently
                    // dropped on arm64. Without the shim (a non-variadic binding), this call would appear to succeed but the socket would
                    // remain blocking, and the flood loop below would hang instead of returning EAGAIN.
                    val shim = Ffi.load[PosixShimBindings]
                    val rc   = shim.kyo_posix_set_nonblocking(client)
                    assert(rc == 0, s"kyo_posix_set_nonblocking failed rc=$rc")
                    val flags = shim.kyo_posix_get_flags(client)
                    assert(
                        (flags & PosixConstants.O_NONBLOCK) != 0,
                        s"O_NONBLOCK not set: flags=0x${flags.toHexString} O_NONBLOCK=0x${PosixConstants.O_NONBLOCK.toHexString}"
                    )
                    val clientH = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                    // Flood the kernel send buffer with 64 KiB chunks until we observe a Partial or Done-after-EAGAIN. The kernel send
                    // buffer is typically 128 KiB - 4 MiB; cap at 512 iterations (32 MiB) to bound the loop even on large-buffer hosts.
                    val chunk      = Span.fromUnsafe(Array.fill[Byte](65536)(0x42))
                    var gotPartial = false
                    var itr        = 0
                    while !gotPartial && itr < 512 do
                        driver.write(clientH, chunk, 0) match
                            case WriteResult.Partial(_, _)     => gotPartial = true
                            case WriteResult.TailPartial(_, _) => gotPartial = true
                            case WriteResult.Done              => itr += 1
                            case WriteResult.Error             => itr = 512 // break on real error
                    end while
                    // Close the raw fds; the driver handle is not registered so no cancel needed.
                    sock.close(accepted).safe.get.andThen(sock.close(client).safe.get).map { _ =>
                        assert(gotPartial, s"expected Partial when send buffer fills (EAGAIN), but all ${itr} writes returned Done")
                    }
                }
            }
        }

        "closeHandle cancels a pending read with Closed, then closes the fd" in {
            assumePoller()
            withDriver { driver =>
                loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    // Register a read with no data available, so the promise stays pending until closeHandle cancels it.
                    val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(acceptedH, promise)
                    driver.closeHandle(acceptedH)
                    Abort.run[Closed](promise.safe.get).map { result =>
                        discard(sock.close(client))
                        // The pending read was failed with Closed by closeHandle -> cancel; and the fd is now closed (a fresh recv fails).
                        result match
                            case Result.Failure(_: Closed) => succeed
                            case other                     => fail(s"expected Closed, got $other")
                    }
                }
            }
        }

        "stale event on a recycled fd is dropped via the activeFds id guard" in {
            assumePoller()
            withDriver { driver =>
                loopbackPair().map { case (client, accepted) =>
                    // Two handles over the SAME fd with different ids: the old read registration, then a new owner of the fd id slot.
                    val oldHandle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val newHandle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    assert(oldHandle.id.packed != newHandle.id.packed, "handles must have distinct ids")
                    // Register a read for the OLD handle: pendingReads[fd] = oldPromise, activeFds[fd] = oldId.
                    val oldPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(oldHandle, oldPromise)
                    // The fd is recycled into newHandle: awaitWritable rewrites activeFds[fd] to newId WITHOUT touching pendingReads[fd].
                    val newWritable = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    driver.awaitWritable(newHandle, newWritable)
                    // Make the fd readable; the poller fires the old read registration, whose id no longer matches -> stale -> Closed, dropped.
                    val wb = Buffer.fromArray[Byte](Array[Byte](9))
                    Sync.ensure(Sync.defer(wb.close())) {
                        sock.send(client, wb, 1L, PosixConstants.MSG_NOSIGNAL).safe.get
                    }.andThen {
                        Abort.run[Closed](oldPromise.safe.get).map { result =>
                            driver.closeHandle(newHandle)
                            discard(sock.close(client))
                            // The stale registration was failed with Closed rather than delivered the byte to the new owner.
                            result match
                                case Result.Failure(_: Closed) => succeed
                                case other                     => fail(s"expected stale read dropped as Closed, got $other")
                        }
                    }
                }
            }
        }

        // fd-recycling lost-wakeup regression (listener-close deregister in PosixListener.close): a listen fd's accept registration must be deregistered
        // (cleared from pendingAccepts/activeFds) BEFORE the fd is closed. Without the deregister, the stale pendingAccepts entry keyed by the
        // listen fd survives after close; when the OS recycles that fd number for a new client connection, drainReady routes the recycled
        // fd's read-ready event to dispatchAccept (stale) instead of dispatchRead (correct), so the connection's read promise never
        // completes (lost-wakeup hang).
        //
        // Deterministic driver-level proof: register an accept interest on fd N, then cancel it (simulating the deregister PosixListener.close
        // wires in), then register a read interest on the SAME fd N (simulating fd recycling), make fd N readable, and
        // assert the READ promise completes. Without the cancel (or if cancel did not clear pendingAccepts), drainReady would route the
        // event to the stale accept dispatch and the read promise would hang until the 15s timeout.
        "a recycled listen fd routes to read dispatch after the listener closed (fd-recycling regression)" in {
            assumePoller()
            withDriver { driver =>
                loopbackPair().map { case (client, accepted) =>
                    // Step 1: register an accept interest on `accepted` (simulating a listen fd being watched by awaitAccept).
                    val listenHandle  = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val acceptPromise = Promise.Unsafe.init[Int, Abort[Closed]]()
                    driver.awaitAccept(listenHandle, acceptPromise)
                    // Step 2: cancel the listen handle (PosixListener.close calls driver.cancel before closing the fd).
                    // This clears pendingAccepts[accepted] and activeFds[accepted].
                    driver.cancel(listenHandle)
                    // The accept promise is now Closed (cancel failed it). That is expected.
                    // Step 3: register a read interest on the SAME fd number (simulating OS fd recycling: the OS handed fd N to a new
                    // client connection after the listener released it). The new handle has a fresh id, so drainReady's id guard alone is
                    // not what we are testing here (activeFds was wiped by cancel); we are testing that pendingAccepts is also cleared,
                    // so drainReady picks pendingReads over the no-longer-present pendingAccepts entry.
                    val readHandle  = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(readHandle, readPromise)
                    // Step 4: make the fd readable by sending a byte from the client side.
                    val wb = Buffer.fromArray[Byte](Array[Byte](42))
                    Sync.ensure(Sync.defer(wb.close())) {
                        sock.send(client, wb, 1L, PosixConstants.MSG_NOSIGNAL).safe.get
                    }.andThen {
                        // Step 5: the read promise must complete with the byte. Without the cancel (stale pendingAccepts entry present),
                        // drainReady would route to dispatchAccept and the read promise would never complete.
                        readPromise.safe.get.map { outcome =>
                            driver.closeHandle(readHandle)
                            discard(sock.close(client))
                            outcome match
                                case ReadOutcome.Bytes(got) =>
                                    assert(!got.isEmpty, "expected read promise to complete with the sent byte, not empty")
                                    assert(got.toArray.toList == List[Byte](42), s"expected byte 42, got ${got.toArray.toList}")
                                case other =>
                                    fail(s"expected bytes, got $other")
                            end match
                        }
                    }
                }
            }.map(_ => succeed)
        }

        "pollOnce uses an indefinite -1 timeout when the wake mechanism is armed (probe)" in {
            assumePoller()
            // Drive a real driver against a RecordingPollerBackend over the real epoll/kqueue that captures the timeout passed to poll and
            // otherwise delegates. The real backend's create() allocates the real poller fd.
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingPollerBackend(real)
            // Synchronize on the actual event: the spy fires prePollLatch the instant the poll loop's first poll cycle runs (lastPollTimeoutMs
            // is set just before that), so the test inspects the recorded value the moment that cycle happens rather than after a guessed delay.
            val polled = Promise.Unsafe.init[Unit, Any]()
            spy.setPrePollLatch(polled)
            val driver = TestDrivers.forBackend(spy, pollerFd)
            discard(driver.start())
            polled.safe.get.andThen {
                driver.close()
                val t = spy.lastPollTimeoutMs
                assert(
                    t == -1,
                    s"poll timeout was $t ms, expected -1 (indefinite); the wake mechanism must make bounded floors unnecessary"
                )
            }
        }

        "registerWake failure fails start() with a clear error, not a permanent stall" in {
            assumePoller()
            // The driver parks with timeoutMs = -1; without a wakeup mechanism there is no way to return the park. start() must
            // fail loud rather than start a loop that wedges on the first park with no way to recover.
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingPollerBackend(real)
            spy.forceRegisterWakeFail.set(true)
            val driver = TestDrivers.forBackend(spy, pollerFd)
            val ex     = intercept[IllegalStateException](discard(driver.start()))
            assert(
                ex.getMessage.contains("registerWake"),
                s"error message should name the failed operation, got: ${ex.getMessage}"
            )
            // The driver never started (start() threw before spawning the loop), so close() takes the never-started path.
            driver.close()
            succeed
        }
    }

    "PollerIoDriver allocation seams" - {

        // Anti-flakiness: each awaitRead latches on a real read completion (Promise.Unsafe completed by the real recv of one byte the peer
        // sent). N sequential reads drive N register/poll cycles; the per-driver scratch buffers and arm buffer are observed after the Nth
        // read completes. No sleep.
        "one poll scratch and one arm buffer are reused across N read cycles; the change FIFO is typed Long" in {
            assumePoller()
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val spy      = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(spy, pollerFd)
            discard(driver.start())
            loopbackPair().map { case (client, accepted) =>
                val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                val N         = 6
                // Drive N sequential reads, each completing on a real one-byte send. Each read registers (arm buffer) and the poll loop runs
                // at least one cycle (events buffer + fds array).
                def drive(k: Int): Unit < (Abort[Closed] & Async) =
                    if k >= N then Sync.defer(())
                    else
                        val wb = Buffer.fromArray[Byte](Array[Byte]((k + 1).toByte))
                        Sync.ensure(Sync.defer(wb.close()))(sock.send(client, wb, 1L, PosixConstants.MSG_NOSIGNAL).safe.get).andThen {
                            readVia(driver, acceptedH).map {
                                case ReadOutcome.Bytes(got) =>
                                    assert(got.toArray.toList == List[Byte]((k + 1).toByte), s"read $k got ${got.toArray.toList}")
                                case other =>
                                    fail(s"read $k: expected bytes, got $other")
                            }.andThen(drive(k + 1))
                        }
                drive(0).map { _ =>
                    driver.closeHandle(acceptedH)
                    discard(sock.close(client))
                    driver.close()

                    import scala.jdk.CollectionConverters.*
                    // The change FIFO is statically typed as the unboxed MpscLongQueue: this assignment compiles only if the queue stores
                    // primitive long change commands (no Function0 closure, no boxed java.lang.Long per offer).
                    val typedQueue: kyo.net.internal.util.MpscLongQueue = driver.changeQueue
                    assert(typedQueue != null, "changeQueue must be non-null")

                    val armBufs = spy.registerReadArmBufs.iterator().asScala.toList
                    assert(armBufs.size >= N, s"expected at least $N registerRead arm captures, got ${armBufs.size}")
                    val driverArmBuf = driver.pollScratch.armBuf
                    armBufs.zipWithIndex.foreach { case (buf, i) =>
                        assert(buf eq driverArmBuf, s"registerRead $i got a different armBuf: expected $driverArmBuf, got $buf")
                    }

                    val eventsBufs = spy.pollEventsBufs.iterator().asScala.toList
                    assert(eventsBufs.size >= 2, s"expected at least 2 poll cycles, got ${eventsBufs.size}")
                    val driverEventsBuf = driver.pollScratch.eventsBuffer
                    eventsBufs.zipWithIndex.foreach { case (buf, i) =>
                        assert(buf eq driverEventsBuf, s"poll cycle $i got a different eventsBuffer: expected $driverEventsBuf, got $buf")
                    }

                    val fdsArrays = spy.pollFdsArrays.iterator().asScala.toList
                    val driverFds = driver.pollScratch.fds
                    fdsArrays.zipWithIndex.foreach { case (arr, i) =>
                        assert(arr eq driverFds, s"poll cycle $i got a different fds array: expected $driverFds, got $arr")
                    }
                    succeed
                }
            }
        }

        // Anti-flakiness: a real full-success send completes synchronously; N plaintext writes each return Done inline. The send mirror is
        // observed after the writes via RecordingSocketBindings.sendNowBufs / send-path buffers. No sleep. JVM/Native only (JS uses sendNow).
        "writeRaw reuses one send mirror buffer across N plaintext writes, delivering each payload" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                assumePoller()
                val spy      = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real     = PollerBackend.default()
                val pollerFd = real.create()
                val backend  = RecordingPollerBackend(real)
                val driver   = TestDrivers.forBackend(backend, pollerFd, spy)
                discard(driver.start())
                loopbackPair().map { case (client, peer) =>
                    val handle = PosixHandle.socket(
                        client,
                        PosixHandle.DefaultReadBufferSize,
                        Absent
                    ) // plaintext handle (no TLS): writeRaw is called directly
                    val N        = 8
                    val payloads = Array.tabulate(N)(k => Array.tabulate[Byte](32)(i => ((k * 32 + i) % 127 + 1).toByte))
                    var k        = 0
                    while k < N do
                        val r = driver.write(handle, Span.fromUnsafe(payloads(k)), 0)
                        assert(r == WriteResult.Done, s"plaintext write $k should be Done, got $r")
                        k += 1
                    end while
                    // Drain the peer to confirm bytes were delivered (and to free the kernel buffer), latching on real reads.
                    val peerHandle = PosixHandle.socket(peer, PosixHandle.DefaultReadBufferSize, Absent)
                    val total      = N * 32
                    PosixTestSockets.drainPeer(driver, peerHandle, peer, total).map { received =>
                        import scala.jdk.CollectionConverters.*
                        // Capture the per-handle send mirror and the recorded send buffers BEFORE closeHandle, which frees the per-handle
                        // buffers. The JVM/Native flush path uses the @Ffi.blocking send (sendBufs); a host using sendNow records there.
                        val mirrorBefore = handle.sendMirror
                        val sentBufs     = spy.sendBufs.iterator().asScala.toList ++ spy.sendNowBufs.iterator().asScala.toList
                        driver.closeHandle(handle)
                        discard(sock.close(peer))
                        driver.close()

                        assert(received >= total, s"peer must receive all $total bytes, got $received")
                        // The send mirror is the per-handle reused buffer; assert all recorded send buffers are the same instance as it.
                        mirrorBefore match
                            case Present(mirror) =>
                                assert(sentBufs.nonEmpty, "at least one send buffer must be recorded for the plaintext writes")
                                sentBufs.zipWithIndex.foreach { case (buf, i) =>
                                    assert(
                                        buf eq mirror,
                                        s"send $i used a different buffer than handle.sendMirror: got $buf, mirror=$mirror"
                                    )
                                }
                            case Absent =>
                                fail("handle.sendMirror must be Present after N plaintext writes")
                        end match
                        succeed
                    }
                }
        }

        // Anti-flakiness: real BoringSSL engines handshaked on the driver FIFO; the peer pre-sends real ciphertext for two reads; each read
        // latches on its real recv completion. The recv staging buffer is observed via RecordingTlsEngine.feedBufs. No sleep.
        "the TLS recv path reuses one staging buffer across two reads" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                assumePoller()
                val clientEngine = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine = TlsRealEngines.singleEngine(isServer = true)
                val spy          = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real         = PollerBackend.default()
                val pollerFd     = real.create()
                val backend      = RecordingPollerBackend(real)
                val driver       = TestDrivers.forBackend(backend, pollerFd, spy)
                // The accepted side decrypts inbound ciphertext with the server engine wrapped in a recording spy.
                val recordingServer = RecordingTlsEngine(serverEngine)
                discard(driver.start())
                loopbackPair().map { case (client, accepted) =>
                    val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    acceptedH.tls = Present(recordingServer)
                    handshakeOnDriver(driver, clientEngine, serverEngine).safe.get.flatMap { handshakeDone =>
                        assert(handshakeDone, "handshake must complete before the reads")
                        // Two application messages: encrypt each through the client engine and send the ciphertext so the accepted side reads
                        // twice, feeding the per-handle recvStaging both times.
                        val msg0 = "tls-read-zero".getBytes("UTF-8")
                        val msg1 = "tls-read-one!".getBytes("UTF-8")
                        encryptOnDriver(driver, clientEngine, msg0).safe.get.flatMap { c0 =>
                            sendAll(client, c0)
                            readVia(driver, acceptedH).map {
                                case ReadOutcome.Bytes(got0) =>
                                    assert(got0.toArray.toList == msg0.toList, s"read 0 got ${got0.toArray.toList}")
                                case other => fail(s"tls read 0: expected bytes, got $other")
                            }
                                .flatMap { _ =>
                                    encryptOnDriver(driver, clientEngine, msg1).safe.get.flatMap { c1 =>
                                        sendAll(client, c1)
                                        readVia(driver, acceptedH).map { got1 =>
                                            got1 match
                                                case ReadOutcome.Bytes(b) =>
                                                    assert(b.toArray.toList == msg1.toList, s"read 1 got ${b.toArray.toList}")
                                                case other => fail(s"tls read 1: expected bytes, got $other")
                                            end match
                                            import scala.jdk.CollectionConverters.*
                                            // Capture recvStaging and the recorded feed buffers BEFORE closeHandle frees the per-handle buffers.
                                            val feedBufs = recordingServer.feedBufs.iterator().asScala.toList
                                            val staging =
                                                acceptedH.recvStaging.getOrElse(fail("recvStaging must be Present after TLS reads"))
                                            driver.submitEngineOp(() => clientEngine.free())
                                            driver.closeHandle(acceptedH)
                                            discard(sock.close(client))
                                            driver.close()

                                            assert(
                                                feedBufs.size >= 2,
                                                s"expected at least 2 feedCiphertext calls (one per read), got ${feedBufs.size}"
                                            )
                                            feedBufs.zipWithIndex.foreach { case (buf, i) =>
                                                assert(
                                                    buf eq staging,
                                                    s"feedCiphertext $i received a different buffer than recvStaging: got $buf, staging=$staging"
                                                )
                                            }
                                            succeed
                                        }
                                    }
                                }
                        }
                    }
                }
        }

        // Anti-flakiness: a real BoringSSL write through the driver over a shrunk-buffer socket forces real EAGAIN and a backpressured flush;
        // draining the peer (real reads) re-flushes. The reused plaintextStaging / encryptDrain / flushMirror buffers are observed via the
        // recording engine and socket spies. No sleep. JVM/Native only.
        "the TLS write path reuses one plaintextStaging, encryptDrain, and flushMirror buffer across a multi-record backpressured write" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                assumePoller()
                val clientEngine    = TlsRealEngines.singleEngine(isServer = false)
                val serverEngine    = TlsRealEngines.singleEngine(isServer = true)
                val recordingClient = RecordingTlsEngine(clientEngine)
                val spy             = RecordingSocketBindings(Ffi.load[SocketBindings])
                val real            = PollerBackend.default()
                val pollerFd        = real.create()
                val backend         = RecordingPollerBackend(real)
                val driver          = TestDrivers.forBackend(backend, pollerFd, spy)
                PosixTestSockets.smallBufferedPair(sndBuf = 128, rcvBuf = 128).map { case (writeFd, peerFd) =>
                    val handle     = PosixHandle.socket(writeFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val peerHandle = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.tls = Present(recordingClient)
                    discard(driver.start())
                    handshakeOnDriver(driver, clientEngine, serverEngine).safe.get.flatMap { handshakeDone =>
                        assert(handshakeDone, "handshake must complete before the write")
                        // 64 KB exceeds one TLS record (max ~16 KB), so encryptPlaintext loops writePlain once per record (multiple calls), and
                        // the 128-byte SO_SNDBUF forces a real backpressured flush across several sends.
                        val payload = Array.fill[Byte](64 * 1024)(0x42.toByte)
                        val r       = driver.write(handle, Span.fromUnsafe(payload), 0)
                        assert(r == WriteResult.Done, s"TLS write should be Done, got $r")
                        // Drain the peer fully so every record reaches the wire (this re-flushes on real write-readiness).
                        drainCiphertextBytes(driver, peerHandle, payload.length).map { _ =>
                            import scala.jdk.CollectionConverters.*
                            // Capture the recorded buffers and the per-handle fields BEFORE closeHandle frees the per-handle buffers.
                            val writePlainBufs = recordingClient.writePlainBufs.iterator().asScala.toList
                            val drainBufs      = recordingClient.drainCipherBufs.iterator().asScala.toList
                            val sendBufs       = spy.sendBufs.iterator().asScala.toList ++ spy.sendNowBufs.iterator().asScala.toList
                            val stagingBefore  = handle.plaintextStaging
                            val drainBefore    = handle.encryptDrain
                            val mirrorBefore   = handle.flushMirror
                            driver.submitEngineOp(() => serverEngine.free())
                            driver.closeHandle(handle)
                            driver.close()
                            PosixTestSockets.closePeerForEof(spy, peerFd)

                            assert(writePlainBufs.nonEmpty, "expected at least one writePlain call for the TLS write")
                            // A 64 KB payload exceeds one TLS record, so drainCiphertext is called multiple times (one per drained record); all
                            // those calls must reuse the one encryptDrain buffer. This is the reuse-across-calls property on the encrypt path.
                            assert(
                                drainBufs.size >= 2,
                                s"expected at least 2 drainCiphertext calls for a multi-record write, got ${drainBufs.size}"
                            )
                            val staging = stagingBefore.getOrElse(fail("plaintextStaging must be Present after a TLS write"))
                            writePlainBufs.zipWithIndex.foreach { case (buf, i) =>
                                assert(
                                    buf eq staging,
                                    s"writePlain $i used a different buffer than plaintextStaging: got $buf, staging=$staging"
                                )
                            }
                            val drain = drainBefore.getOrElse(fail("encryptDrain must be Present after a TLS write"))
                            drainBufs.zipWithIndex.foreach { case (buf, i) =>
                                assert(
                                    buf eq drain,
                                    s"drainCiphertext $i used a different buffer than encryptDrain: got $buf, drain=$drain"
                                )
                            }
                            // The flush mirror is the per-handle buffer the send path copies the unsent tail into.
                            mirrorBefore match
                                case Present(mirror) =>
                                    sendBufs.zipWithIndex.foreach { case (buf, i) =>
                                        assert(buf eq mirror, s"send $i used a different buffer than flushMirror: got $buf, mirror=$mirror")
                                    }
                                case Absent =>
                                    // A host whose send accepted everything inline never copied into the mirror; the writePlain/encryptDrain
                                    // reuse above still pins the TLS encrypt-loop buffers.
                                    assert(sendBufs.isEmpty, "flushMirror Absent implies no recorded send buffers")
                            end match
                            succeed
                        }
                    }
                }
        }
    }

    /** Complete the in-memory handshake for `client`/`server` on the driver's engine FIFO worker so both sessions are created and used on the
      * same carrier (the engine single-owner contract). Returns a promise that completes once the handshake has run.
      */
    private def handshakeOnDriver(driver: PollerIoDriver, client: TlsEngine, server: TlsEngine): Promise.Unsafe[Boolean, Any] =
        val done = Promise.Unsafe.init[Boolean, Any]()
        driver.submitEngineOp(() => done.completeDiscard(Result.succeed(TlsEngineLoopback.handshake(client, server))))
        done
    end handshakeOnDriver

    /** Encrypt `plaintext` with `client` on the driver's engine FIFO worker and return the produced ciphertext via a promise. */
    private def encryptOnDriver(driver: PollerIoDriver, client: TlsEngine, plaintext: Array[Byte]): Promise.Unsafe[Array[Byte], Any] =
        val done = Promise.Unsafe.init[Array[Byte], Any]()
        driver.submitEngineOp(() => done.completeDiscard(Result.succeed(TlsEngineLoopback.encrypt(client, plaintext))))
        done
    end encryptOnDriver

    /** Send the whole byte array to `fd` in one or more sendNow calls until every byte is accepted. */
    private def sendAll(fd: Int, bytes: Array[Byte]): Unit =
        var off = 0
        while off < bytes.length do
            val chunk = bytes.slice(off, bytes.length)
            val buf   = Buffer.fromArray[Byte](chunk)
            try
                val n = sock.sendNow(fd, buf, chunk.length.toLong, 0).value
                if n > 0 then off += n.toInt
                else off = bytes.length // a non-positive return ends the loop; the test asserts on the decrypted reads
            finally buf.close()
            end try
        end while
    end sendAll

    /** Drain the peer fd until `total` ciphertext bytes have been received, latching on each real read-readiness. Returns the count drained. */
    private def drainCiphertextBytes(driver: PollerIoDriver, peerHandle: PosixHandle, total: Int): Int < (Abort[Closed] & Async) =
        def loop(received: Int, steps: Int): Int < (Abort[Closed] & Async) =
            if received >= total then received
            else if steps > total + 256 then received // safety bound; the surrounding assertions catch a shortfall
            else
                val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                driver.awaitRead(peerHandle, p)
                p.safe.get.map {
                    case ReadOutcome.Bytes(span) => loop(received + span.size, steps + 1)
                    case _                       => received
                }
        loop(0, 0)
    end drainCiphertextBytes

    /** Submit a marker engine op and return a promise that completes when the FIFO worker runs it. Awaiting it proves every change submitted
      * before it has executed: a deterministic, sleep-free settle point.
      */
    private def fifoBarrier(driver: PollerIoDriver): Promise.Unsafe[Unit, Any] =
        val p = Promise.Unsafe.init[Unit, Any]()
        driver.submitEngineOp(() => p.completeDiscard(Result.succeed(())))
        p
    end fifoBarrier

end PollerIoDriverTest
