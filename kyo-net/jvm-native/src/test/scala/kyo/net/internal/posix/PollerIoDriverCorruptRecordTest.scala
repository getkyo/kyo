package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetConnectionIoException
import kyo.net.Test
import kyo.net.internal.TlsEngineLoopback
import kyo.net.internal.TlsRealEngines
import kyo.net.internal.transport.ReadOutcome

/** Poller-path fatal-record parity guard, the epoll/kqueue sibling of [[IoUringDriverCorruptRecordTest]].
  *
  * A fatal record-layer error (RFC 5246 §7.2.2) must terminate the connection surfaced as the typed decrypt failure
  * `ReadOutcome.Failed(NetConnectionIoException(Decrypt))`, identical to the io_uring driver, never a bare `Closed` and never the good
  * prefix delivered behind the corruption. On BoringSSL a fatal record is signalled by `feedAndDecrypt`'s `onFatal` (a `readPlain == -2`
  * return, not a thrown exception), so `dispatchReadTls` must complete the typed decrypt failure on that path directly: `onFatal`'s
  * `requestClose` marks the handle closing, and the read completion is what distinguishes a corrupt record (a typed decrypt failure) from an
  * ordinary local close (`Closed`).
  *
  * The leaf feeds `[good record][corrupted record]` (the corruption flips the last body byte of the second record so its AEAD tag fails)
  * coalesced in one wire write, so both records arrive in a single recv, and reads once via the driver. It asserts the read fails with the
  * typed decrypt failure, the handle is closing, and neither the good prefix nor a bare `Closed` is delivered.
  *
  * Gate: [[PosixTestSockets.assumePoller]] (Linux epoll or macOS kqueue) and [[TlsRealEngines.assumeTlsReady]] (a staged BoringSSL/OpenSSL
  * provider); cancels cleanly on JS, off a poller platform, or where no provider is staged, so this is CI-validated on native Linux with
  * BoringSSL staged.
  *
  * Anti-flakiness: the read synchronizes on the read promise (completed only when the recv edge dispatches and the decrypt engine op runs),
  * never a timer; `Async.timeout` is only the deadlock ceiling. The settle-wait on `tls` going Absent lets the driver's own queued TLS
  * teardown run its engine free before `withEngines` frees the same engine out of band, so that becomes a harmless CAS-guarded second free.
  * No sleep, no busy-spin.
  */
class PollerIoDriverCorruptRecordTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Poll a real condition until it holds or the bound elapses, re-checking each turn after a short Async.sleep. */
    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    "PollerIoDriver corrupt-record (real epoll/kqueue, real engine)" - {

        "a fatal TLS record on a read fails the read with a typed decrypt error and tears the connection down, never delivering the good prefix" in {
            if kyo.internal.Platform.isJS then Sync.defer(succeed)
            else
                TlsRealEngines.assumeTlsReady()
                PosixTestSockets.assumePoller()
                TlsRealEngines.withEngines { (clientEngine, serverEngine) =>
                    val driver = PollerIoDriver.init()
                    discard(driver.start())
                    Sync.ensure(Sync.defer(driver.close())) {
                        PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                            val handshakeDone = TlsEngineLoopback.handshake(clientEngine, serverEngine)
                            assert(handshakeDone, "TLS handshake must complete before the read")
                            val acceptedH = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent, Frame.internal)
                            acceptedH.tls = Present(serverEngine)

                            val good = "GOOD-application-record".getBytes("UTF-8")
                            val bad  = "TAMPERED-application-record".getBytes("UTF-8")
                            // One writePlain per record yields one TLS record each.
                            val goodRecord = TlsEngineLoopback.encrypt(clientEngine, good)
                            val badRecord  = TlsEngineLoopback.encrypt(clientEngine, bad)
                            assert(
                                goodRecord.length > 5 && badRecord.length > 5,
                                "expected real TLS records with a 5-byte header plus body"
                            )
                            // Corrupt the body of the SECOND record (skip the 5-byte header) so its AEAD tag fails; the first stays intact.
                            val corrupted = badRecord.clone()
                            corrupted(corrupted.length - 1) = (corrupted(corrupted.length - 1) ^ 0xff).toByte
                            // Coalesce [good record][corrupted record] in one wire write, exactly the on-wire batching the driver sees under load.
                            val coalesced = new Array[Byte](goodRecord.length + corrupted.length)
                            java.lang.System.arraycopy(goodRecord, 0, coalesced, 0, goodRecord.length)
                            java.lang.System.arraycopy(corrupted, 0, coalesced, goodRecord.length, corrupted.length)
                            // Send BEFORE arming the read so both records are in the accepted side's kernel buffer, delivered in one recv.
                            val cipherBuf = Buffer.fromArray[Byte](coalesced)
                            val sendR =
                                try sock.sendNow(client, cipherBuf, coalesced.length.toLong, PosixConstants.MSG_NOSIGNAL)
                                finally cipherBuf.close()
                            assert(sendR.value.toInt == coalesced.length, s"send failed: errno=${sendR.errorCode}")

                            val promise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            driver.awaitRead(acceptedH, promise)
                            Abort.run[Timeout | Closed](Async.timeout(5.seconds)(promise.safe.get)).map { outcome =>
                                // requestClose fired inside the fatal completion (poll carrier), which happens-before this outcome, so isClosing is
                                // already settled here. Capture it before our own closeHandle below.
                                val closing = acceptedH.isClosing()
                                driver.closeHandle(acceptedH)
                                discard(sock.close(client))
                                // Let the driver's queued TLS teardown run its own engine free before withEngines frees the same engine out of band.
                                awaitCondition(5.seconds)(!acceptedH.tls.isDefined).map { settled =>
                                    assert(
                                        settled,
                                        "the driver's own TLS teardown never settled (a hang, not the fatal-record path this guard targets)"
                                    )
                                    outcome match
                                        case Result.Success(ReadOutcome.Failed(e: NetConnectionIoException)) =>
                                            // RFC 5246 §7.2.2: the fatal record tears the connection down, surfaced as the typed decrypt failure
                                            // (never a misleading Closed, and never the good prefix). The handle must be closing, not re-armed.
                                            assert(
                                                e.operation == NetConnectionIoException.Operation.Decrypt,
                                                s"the fatal record must surface as a TLS decrypt failure; got operation ${e.operation}"
                                            )
                                            assert(
                                                closing,
                                                "the fatal record must mark the handle closing (requestClose), tearing it down rather than re-arming a freed handle"
                                            )
                                        case Result.Success(ReadOutcome.Bytes(got)) =>
                                            fail(
                                                s"a fatal TLS record was swallowed: the read delivered ${got.size} bytes (${got.toArray.toList}) " +
                                                    "instead of the typed decrypt failure; RFC 5246 §7.2.2 requires the connection to be torn down"
                                            )
                                        case Result.Failure(_: Closed) =>
                                            fail(
                                                "a fatal TLS record surfaced as a bare Closed instead of the typed decrypt failure: the fatal " +
                                                    "path fell through to the endDispatch closing check. io_uring and the poller must agree"
                                            )
                                        case Result.Failure(_: Timeout) =>
                                            fail(
                                                "the read hung on a fatal record: the fatal path did not complete the read (a torn-down handle was re-armed)"
                                            )
                                        case other =>
                                            fail(s"a fatal TLS record must surface as the typed decrypt failure; got: $other")
                                    end match
                                }
                            }
                        }
                    }
                }
        }
    }

end PollerIoDriverCorruptRecordTest
