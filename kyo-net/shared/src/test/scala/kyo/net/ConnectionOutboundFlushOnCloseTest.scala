package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.HandleLife
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.ReleaseMode
import kyo.net.internal.transport.ScriptedPeerDriver
import kyo.net.internal.transport.WriteResult

/** The write-side half of the flush-before-close contract: the queued outbound is flushed to the driver BEFORE the fd is closed. The
  * release waits on the WRITE-side drain (the WritePump takes the closing outbound channel to empty and writes each span, then re-enters
  * closeFn), so every queued span is written before the driver's release runs.
  *
  * The SpyDriver records whether any `write` lands AFTER the release (an ordering violation) and counts the writes; the release promise is
  * the deterministic settle point (no sleep).
  */
class ConnectionOutboundFlushOnCloseTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    final private class SpyDriver extends IoDriver[Unit]:
        val writeCount      = AtomicInt.Unsafe.init(0)
        val releaseSeen     = AtomicBoolean.Unsafe.init(false)
        val writeAfterClose = AtomicBoolean.Unsafe.init(false)
        val releaseDone     = Promise.Unsafe.init[Unit, Any]()

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit             = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit  = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit    = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                                       =
            if releaseSeen.get() then writeAfterClose.set(true)
            discard(writeCount.incrementAndGet())
            WriteResult.Done
        end write
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit = ()
        private val handleLife                                   = HandleLife.init()
        def life(handle: Unit): HandleLife                       = handleLife
        def release(handle: Unit, mode: ReleaseMode, released: Promise.Unsafe[Unit, Any])(using AllowUnsafe, Frame): Unit =
            releaseSeen.set(true)
            discard(releaseDone.complete(Result.succeed(())))
            released.completeUnitDiscard()
        end release
        def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit     = closeFd()
        def runOwned(handle: Unit)(op: () => Unit)(using AllowUnsafe, Frame): Unit           = op()
        def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
            try cancel(handle)
            finally releaseFd(handle, closeFd)
        def close()(using AllowUnsafe, Frame): Unit = ()
        def label: String                           = "SpyDriver"
        def handleLabel(handle: Unit): String       = "spy"
    end SpyDriver

    "queued-outbound-flushed-before-fd-close" in {
        val spy   = new SpyDriver
        val conn  = Connection.init[Unit]((), spy, 8)
        val spans = List(Span(1.toByte), Span(2.toByte), Span(3.toByte))
        conn.start()
        for
            _ <- Sync.defer(spans.foreach(s => discard(conn.outbound.offer(s))))
            _ <- Sync.defer(conn.close())
            _ <- spy.releaseDone.safe.get
        yield
            assert(!spy.writeAfterClose.get(), "no outbound write may land after the fd close (the write-side drain gates the close)")
            assert(
                spy.writeCount.get() == spans.size,
                s"all ${spans.size} queued outbound spans must be flushed before the close, got ${spy.writeCount.get()}"
            )
        end for
    }

    "close flush grace" - {

        "the tail of a span parked mid-write reaches the peer before the fd closes" in {
            val driver = new ScriptedPeerDriver
            val conn   = Connection.init[Unit]((), driver, 8)
            conn.start()
            discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](3)(1))))
            conn.close()
            assert(
                driver.releaseCalls.get() == 0,
                s"close must wait for the parked span's tail; releases=${driver.releaseCalls.get()}"
            )
            driver.peerReads()
            driver.peerReads()
            assert(driver.written.get() == 3, s"every byte of the parked span must be written; writes=${driver.written.get()}")
            assert(
                driver.releaseCalls.get() == 1,
                s"the fd must close once the tail is written; releases=${driver.releaseCalls.get()}"
            )
        }

        "a peer that never reads is released after a window with no write progress" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new ScriptedPeerDriver
                    val conn   = Connection.init[Unit]((), driver, 8, closeFlushGrace = 1.second, clock = clock)
                    conn.start()
                    discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](8)(1))))
                    discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](8)(2))))
                    conn.close()
                    assert(
                        driver.releaseCalls.get() == 0,
                        s"the flush must start out waiting; releases=${driver.releaseCalls.get()}"
                    )
                    armedThenAdvance(tc, "close-flush grace", 1.second).map { _ =>
                        assert(
                            driver.releaseCalls.get() == 1,
                            s"a window with no write progress must release the fd; releases=${driver.releaseCalls.get()}"
                        )
                        assert(driver.written.get() == 1, s"nothing may be written after the release; writes=${driver.written.get()}")
                        assert(!conn.isOpen, "the released connection must read as closed")
                    }
                }
            }
        }

        "a peer that keeps reading is never cut and receives the whole tail" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new ScriptedPeerDriver
                    val conn   = Connection.init[Unit]((), driver, 8, closeFlushGrace = 1.second, clock = clock)
                    conn.start()
                    discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](4)(1))))
                    conn.close()
                    for
                        _      <- armedThenAdvance(tc, "close-flush grace", 1.second, by = 600.millis)
                        _      <- Sync.defer(driver.peerReads())
                        _      <- armedThenAdvance(tc, "close-flush grace", 1.second, by = 600.millis)
                        _      <- Sync.defer(driver.peerReads())
                        _      <- armedThenAdvance(tc, "re-armed close-flush grace", 1.second, by = 600.millis)
                        midway <- Sync.defer(driver.releaseCalls.get())
                        _      <- Sync.defer(driver.peerReads())
                    yield
                        assert(midway == 0, s"a window with progress must not release the fd; releases=$midway")
                        assert(driver.written.get() == 4, s"every byte must be written; writes=${driver.written.get()}")
                        assert(
                            driver.releaseCalls.get() == 1,
                            s"the fd must close once the flush completes; releases=${driver.releaseCalls.get()}"
                        )
                    end for
                }
            }
        }

        "an infinite grace keeps waiting for the peer" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new ScriptedPeerDriver
                    val conn   = Connection.init[Unit]((), driver, 8, closeFlushGrace = Duration.Infinity, clock = clock)
                    conn.start()
                    discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](8)(1))))
                    discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](8)(2))))
                    conn.close()
                    tc.advance(1.hour).map { _ =>
                        assert(
                            driver.releaseCalls.get() == 0,
                            s"an infinite grace must hold the fd for the peer; releases=${driver.releaseCalls.get()}"
                        )
                    }
                }
            }
        }
    }

end ConnectionOutboundFlushOnCloseTest
