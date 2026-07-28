package kyo.net.internal.transport

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.Test

/** Tests the driverless in-memory Connection: a Connection backed only by two cross-wired channels, no driver and no syscalls.
  *
  * It must round-trip bytes through the channels, close both idempotently with no driver call, and, not being a `Connection[Handle]`, take
  * the doUpgradeToTls path's failure fiber.
  */
class InMemoryConnectionTest extends Test:

    import AllowUnsafe.embrace.danger

    "Connection.inMemory" - {
        "round-trips a span: a write on side A is readable on side B's inbound" in {
            val (a, b)  = Connection.inMemoryPair()
            val payload = Span.fromUnsafe(Array.tabulate[Byte](5)(i => (i + 10).toByte))
            a.outbound.safe.put(payload).andThen {
                b.inbound.safe.take.map { got =>
                    assert(
                        got.toArray.toList == payload.toArray.toList,
                        s"B received ${got.toArray.toList}, expected ${payload.toArray.toList}"
                    )
                }
            }
        }

        "is bidirectional: B writes, A reads" in {
            val (a, b)  = Connection.inMemoryPair()
            val payload = Span.fromUnsafe(Array[Byte](7, 8, 9))
            b.outbound.safe.put(payload).andThen {
                a.inbound.safe.take.map { got =>
                    assert(got.toArray.toList == payload.toArray.toList, s"A received ${got.toArray.toList}")
                }
            }
        }

        "close closes both channels idempotently, with no driver" in {
            val (a, _) = Connection.inMemoryPair()
            assert(a.isOpen, "connection should start open")
            Sync.defer(a.close()).andThen {
                Sync.defer(a.close()).andThen { // second close is a safe no-op (idempotent CAS)
                    val stillOpen = a.isOpen
                    assert(!stillOpen, "connection should be closed after close")
                    // Both channels are closed: a put on the outbound now fails with Closed (no driver involved).
                    Abort.run[Closed](a.outbound.safe.put(Span.fromUnsafe(Array[Byte](1)))).map { result =>
                        assert(result.isFailure, s"write after close must fail, got $result")
                    }
                }
            }
        }

        "onClosing completes on close (and not before)" in {
            val (a, _) = Connection.inMemoryPair()
            assert(!a.onClosing.done(), "onClosing must not be complete on a live in-memory connection")
            Sync.defer(a.close()).andThen {
                assert(a.onClosing.done(), "onClosing must complete when the in-memory connection is closed")
                Sync.defer(a.close()).andThen { // idempotent: a repeat close neither re-completes nor raises
                    assert(a.onClosing.done(), "onClosing stays complete after a repeat close")
                    succeed
                }
            }
        }

        "is not upgradable: doUpgradeToTls returns a Closed failure fiber" in {
            val (a, _) = Connection.inMemoryPair()
            val tls    = NetTlsConfig.default
            // InMemory connection is not a Connection[Handle]; doUpgradeToTls returns a failure fiber.
            val upgradeFiber = a match
                case c: kyo.net.internal.transport.Connection[?] => c.doUpgradeToTls(tls, Frame.internal)
                case _ => Fiber.Unsafe.fromResult(Result.fail(Closed("InMemory", Frame.internal, "not upgradable")))
            Abort.run[Closed](upgradeFiber.safe.get).map { result =>
                result match
                    case Result.Failure(_: Closed) => succeed
                    case other                     => fail(s"expected Closed (non-upgradable), got $other")
            }
        }

        "detachForUpgrade returns Absent (not upgradable)" in {
            val (a, _)   = Connection.inMemoryPair()
            val detached = a.detachForUpgrade()
            assert(detached.isEmpty, s"detachForUpgrade must return Absent, got $detached")
        }
    }
end InMemoryConnectionTest
