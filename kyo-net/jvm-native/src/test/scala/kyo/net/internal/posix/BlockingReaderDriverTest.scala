package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.ReleaseMode
import kyo.net.internal.transport.WriteResult

/** Tests the [[BlockingReaderDriver]] regular-file fallback in isolation, against a real temp-file fd and a real PollerIoDriver.
  *
  * `awaitRead` must yield the real file bytes in order and an empty `Span` on the 0-return EOF, honoring the one-read-per-handle contract;
  * `write` must delegate to the wrapped driver on `writeFd` (only `awaitRead` is swapped).
  *
  * The test uses a real temp file fd obtained via `PosixTestSockets.tempFileFd`, which opens the file `O_RDONLY` through the native
  * `kyo_posix_open` shim and returns the raw POSIX fd; no reflection is involved.
  *
  * Anti-flakiness: `readVia` latches on `promise.safe.get` (a real `Promise.Unsafe` completed by the `@Ffi.blocking` `read` fiber). File data
  * is pre-written before the test; `read` returns from page cache promptly. No sleep.
  *
  * The wrapped driver is a real `PollerIoDriver`: unstarted for the `awaitRead` test (whose read path uses the blocking `read(2)` syscall
  * directly, never delegating to the wrapped driver's `awaitRead`), and wrapped in a delegating `RecordingIoDriver` for the write-delegation
  * test so the delegated `write` reaches a real peer over a loopback pair.
  */
class BlockingReaderDriverTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.NetConfig.default

    private def sock = Ffi.load[SocketBindings]

    /** Drive a single `awaitRead` on `driver` for `handle` and await the deposited promise. */
    private def readVia(driver: BlockingReaderDriver, handle: PosixHandle)(using Frame): ReadOutcome < (Abort[Closed] & Async) =
        val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
        driver.awaitRead(handle, promise)
        promise.safe.get
    end readVia

    "BlockingReaderDriver" - {
        // Anti-flakiness: real file bytes read from page cache promptly; Promise latch on @Ffi.blocking read(2) completion. No sleep.
        "awaitRead returns file bytes in order, then an empty Span on EOF" in {
            PosixTestSockets.assumePoller()
            // Real temp file with known content [1..6]. The @Ffi.blocking read(2) returns data from the page cache (already in OS page
            // cache after write), then 0 (EOF) when the file position reaches the end. The driver's promise latches on each read
            // completion (a real Promise.Unsafe) with no sleep; bytes arrive in order and EOF produces an empty Span.
            //
            // BlockingReaderDriver.awaitRead uses the blocking read(2) syscall on the file fd directly; the wrapped driver's awaitRead is
            // never invoked, so the wrapped real PollerIoDriver need not be started.
            val content    = Array[Byte](1, 2, 3, 4, 5, 6)
            val (fd, _)    = PosixTestSockets.tempFileFd(content)
            val realDriver = PollerIoDriver.init() // unstarted; awaitRead path never delegates here
            // init loads the real socket bindings; the blocking read(2) path reads the real temp file fd.
            val driver = BlockingReaderDriver.init(realDriver)
            // PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal) creates a handle with readFd == writeFd == fd; BlockingReaderDriver reads from readFd.
            val handle = PosixHandle.socket(fd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)

            // Drain all data reads until EOF, accumulating bytes. A real file returns however many bytes the kernel provides per read
            // (may be all 6 at once, or split); the concatenation is asserted to equal the file content. EOF produces an empty Span.
            def readAll(acc: List[Byte])(using Frame): List[Byte] < (Abort[Closed] & Async) =
                readVia(driver, handle).map {
                    case ReadOutcome.Bytes(span) => readAll(acc ++ span.toArray.toList)
                    case _                       => acc
                }
            readAll(Nil).map { bytes =>
                PosixTestSockets.closeTempFd(fd)
                realDriver.close()
                assert(bytes == content.toList, s"all file bytes must arrive in order, got $bytes")
            }
        }

        // Anti-flakiness: write() is synchronous; RecordingIoDriver.writeCalls incremented before delegating to real.
        // The real PollerIoDriver.write on a real loopback socket pair delivers bytes for real.
        "write delegates to the wrapped real driver on writeFd, carrying the bytes through" in {
            PosixTestSockets.assumePoller()
            // The write() call is recorded by RecordingIoDriver (writeCalls.getAndIncrement()) then delegated to the real PollerIoDriver,
            // which writes to a real loopback socket so the peer receives the bytes. The test asserts writeCalls == 1 (the recorded
            // delegation) and that the real write returned Done.
            val real = PollerIoDriver.init()
            val spy  = new RecordingIoDriver(real)
            discard(spy.start())
            PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
                // init wraps the recording driver (a real IoDriver that records the delegated write) over the real socket bindings.
                val wrappedDriver = BlockingReaderDriver.init(spy)
                val handle        = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                val payload       = Span.fromUnsafe(Array.tabulate[Byte](8)(i => (i + 1).toByte))
                val result        = wrappedDriver.write(handle, payload, 0)
                assert(result == WriteResult.Done, s"write result must be Done, got $result")
                assert(spy.writeCalls.get() == 1, s"write must have been delegated to real driver once, got ${spy.writeCalls.get()}")
                spy.close()
                val sockets = Ffi.load[SocketBindings]
                discard(sockets.close(peerFd))
                discard(sockets.close(clientFd))
                succeed
            }
        }

        "cancel fails a read the driver is blocked in with Closed" in {
            PosixTestSockets.assumePoller()
            val real   = PollerIoDriver.init()
            val driver = BlockingReaderDriver.init(real)
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.blockingLoopbackPair().map { case (peerFd, readFd) =>
                    val h    = PosixHandle.socket(readFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                    val read = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(h, read)
                    driver.cancel(h)
                    val outcome = read.poll()
                    // The next read is the latch for the blocked read(2): it completes only once that read has returned on the peer's FIN.
                    val next = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                    driver.awaitRead(h, next)
                    discard(sock.close(peerFd))
                    Abort.run[Closed](next.safe.get).map { _ =>
                        discard(sock.close(readFd))
                        outcome match
                            case Present(Result.Failure(_: Closed)) => succeed
                            case other => fail(s"a cancel left the blocked read pending instead of failing it Closed: $other")
                        end match
                    }
                }
            }
        }

        "a close while a read is in flight keeps the read buffer until the read returns" in {
            PosixTestSockets.assumePoller()
            val real   = PollerIoDriver.init()
            val driver = BlockingReaderDriver.init(real)
            Sync.ensure(Sync.defer(driver.close()))(PosixTestSockets.blockingLoopbackPair().map { case (peerFd, readFd) =>
                val h = PosixHandle.socket(readFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                driver.awaitRead(h, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                // The test holds the fd-close claim so release does not shut the fd down, which would end the read before the free and
                // hide the window. The test installs the fd-close credit in its place: it runs once the resources are freed.
                assert(h.claimFdClose())
                val released = Promise.Unsafe.init[Boolean, Any]()
                h.fdCloseSink = Present { () =>
                    discard(sock.close(readFd))
                    released.completeDiscard(Result.succeed(h.readBuffer.isClosed))
                }
                driver.release(h, ReleaseMode.Graceful, Promise.Unsafe.init())
                val freedInFlight = h.readBuffer.isClosed
                discard(sock.close(peerFd))
                driver.close()
                released.safe.get.map { freedOnRelease =>
                    assert(!freedInFlight, "release freed the read buffer while read(2) was still in flight on it")
                    assert(freedOnRelease, "the read buffer was not freed once the in-flight read(2) returned")
                }
            })
        }

        "a read armed after a cancel takes the bytes the cancelled read(2) returns" in {
            PosixTestSockets.assumePoller()
            val payload                                                         = Array[Byte](1, 2, 3)
            def once(using Frame): Maybe[ReadOutcome] < (Abort[Closed] & Async) =
                val real   = PollerIoDriver.init()
                val driver = BlockingReaderDriver.init(real)
                Sync.ensure(Sync.defer(driver.close())) {
                    PosixTestSockets.blockingLoopbackPair().map { case (peerFd, readFd) =>
                        val h = PosixHandle.socket(readFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                        driver.awaitRead(h, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                        driver.cancel(h)
                        val next = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(h, next)
                        val out = Buffer.alloc[Byte](payload.length)
                        payload.indices.foreach(i => out.set(i, payload(i)))
                        val sent = Sync.ensure(Sync.defer(out.close()))(sock.send(peerFd, out, payload.length.toLong, 0).safe.get)
                        sent.andThen {
                            discard(sock.close(peerFd))
                            next.safe.get.map { outcome =>
                                discard(sock.close(readFd))
                                outcome match
                                    case ReadOutcome.Bytes(span) if span.toArray.sameElements(payload) => Absent
                                    case other                                                         => Present(other)
                                end match
                            }
                        }
                    }
                }
            end once
            Kyo.fill(20)(once).map { results =>
                val lost = results.flatMap(_.toList)
                assert(lost.isEmpty, s"the bytes the cancelled read(2) consumed never reached the next read: ${lost.size}/20 got $lost")
            }
        }

        "bytes a cancelled read(2) returns with no read armed reach the next read" in {
            PosixTestSockets.assumePoller()
            val payload                                                         = Array[Byte](4, 5, 6)
            def once(using Frame): Maybe[ReadOutcome] < (Abort[Closed] & Async) =
                val real   = PollerIoDriver.init()
                val spy    = new RecordingSocketBindings(sock)
                val driver = TestDrivers.blockingReader(real, spy)
                Sync.ensure(Sync.defer(driver.close())) {
                    PosixTestSockets.blockingLoopbackPair().map { case (peerFd, readFd) =>
                        val h        = PosixHandle.socket(readFd, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                        val returned = spy.readReturned(readFd)
                        driver.awaitRead(h, Promise.Unsafe.init[ReadOutcome, Abort[Closed]]())
                        driver.cancel(h)
                        val out = Buffer.alloc[Byte](payload.length)
                        payload.indices.foreach(i => out.set(i, payload(i)))
                        val sent = Sync.ensure(Sync.defer(out.close()))(sock.send(peerFd, out, payload.length.toLong, 0).safe.get)
                        sent.andThen(returned.safe.get).andThen {
                            // The peer's FIN bounds a second read(2), so a driver that dropped the bytes completes the next read PeerFin.
                            discard(sock.close(peerFd))
                            val next = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            driver.awaitRead(h, next)
                            next.safe.get.map { outcome =>
                                discard(sock.close(readFd))
                                outcome match
                                    case ReadOutcome.Bytes(span) if span.toArray.sameElements(payload) => Absent
                                    case other                                                         => Present(other)
                                end match
                            }
                        }
                    }
                }
            end once
            Kyo.fill(20)(once).map { results =>
                val lost = results.flatMap(_.toList)
                assert(lost.isEmpty, s"the bytes a cancelled read(2) returned with no read armed were dropped: ${lost.size}/20 got $lost")
            }
        }
    }

end BlockingReaderDriverTest
