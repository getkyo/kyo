package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException
import kyo.net.Test

/** The cancel contract, checked on every driver of the platform ([[CancelContractCells]]).
  *
  * `cancel` fails, with `Closed`, every promise parked on the handle through any entry path, and takes no release claim: a park after it is
  * live. The release claim is taken only by the connection's release: a park after it fails at once, the release fails every parked op, and
  * the descriptor is closed exactly once. Each leaf reads the parked promise synchronously right after the call under test, so a driver that
  * defers the failure, or leaves the promise to a later sweep, is red rather than slow.
  */
class IoDriverCancelContractTest extends Test:

    import AllowUnsafe.embrace.danger
    import CancelCell.Kind
    import CancelCell.Op

    /** One leaf per cell; a cell without every entry path in `ops` cancels with the missing ones named. */
    private def contract(ops: Op*)(kind: Kind)(
        scenario: CancelCell.Subject => (kyo.test.AssertScope ?=> Any < (Async & Abort[Closed] & Scope))
    )(using Frame): Unit =
        CancelContractCells.all.foreach { cell =>
            s"[${cell.name}]" in {
                cell.unavailable match
                    case Present(reason) => cancel(reason)
                    case Absent          =>
                        val missing = ops.filterNot(cell.supports.contains)
                        if missing.nonEmpty then cancel(s"${cell.name} has no ${missing.mkString(", ")} entry path")
                        else Scope.run(cell.open(kind).map(subject => scenario(subject).unit))
            }
        }

    private def readPromise() = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
    private def unitPromise() = Promise.Unsafe.init[Unit, Abort[Closed | NetException]]()

    private def failedClosed[A, E](what: String, p: Promise.Unsafe[A, Abort[E]])(using kyo.test.AssertScope): Unit =
        val outcome = p.poll()
        assert(
            outcome match
                case Present(Result.Failure(_: Closed)) => true
                case _                                  => false
            ,
            s"$what: expected a Closed failure at once, got $outcome"
        )
    end failedClosed

    private def waiterPromise() = Promise.Unsafe.init[Span[Byte], Abort[NetException]]()

    // The handoff waiter's channel is NetException: a handshake that loses its handle fails as a closed connection, not with Closed.
    private def waiterFailed(what: String, p: Promise.Unsafe[Span[Byte], Abort[NetException]])(using kyo.test.AssertScope): Unit =
        val outcome = p.poll()
        assert(
            outcome match
                case Present(Result.Failure(_: NetException)) => true
                case _                                        => false
            ,
            s"$what: expected a failure at once, got $outcome"
        )
    end waiterFailed

    "cancel fails" - {
        "a parked read with Closed" - contract(Op.Read)(Kind.Connected) { s =>
            val read = readPromise()
            s.awaitRead(read)
            assert(read.poll().isEmpty, s"the read must park on an idle handle, got ${read.poll()}")
            s.cancel()
            failedClosed("the parked read after cancel", read)
        }

        "a parked writable wait with Closed" - contract(Op.Write)(Kind.Stalled) { s =>
            val writable = unitPromise()
            s.awaitWritable(writable)
            assert(writable.poll().isEmpty, s"the writable wait must park on a stalled handle, got ${writable.poll()}")
            s.cancel()
            failedClosed("the parked writable wait after cancel", writable)
        }

        "both a parked read and a parked writable with Closed" - contract(Op.Read, Op.Write)(Kind.Stalled) { s =>
            val read     = readPromise()
            val writable = unitPromise()
            s.awaitRead(read)
            s.awaitWritable(writable)
            assert(writable.poll().isEmpty, s"the writable wait must park on a stalled handle, got ${writable.poll()}")
            s.cancel()
            failedClosed("the parked read after cancel", read)
            failedClosed("the parked writable wait after cancel", writable)
        }

        "a parked connect with Closed" - contract(Op.Connect)(Kind.Connecting) { s =>
            val connect = unitPromise()
            s.awaitConnect(connect)
            assert(connect.poll().isEmpty, s"the connect must park while it is in flight, got ${connect.poll()}")
            s.cancel()
            failedClosed("the parked connect after cancel", connect)
        }

        "a parked accept with Closed" - contract(Op.Accept)(Kind.Listening) { s =>
            val accept = Promise.Unsafe.init[Int, Abort[Closed | NetException]]()
            s.awaitAccept(accept)
            assert(accept.poll().isEmpty, s"the accept must park on a listener nobody connects to, got ${accept.poll()}")
            s.cancel()
            failedClosed("the parked accept after cancel", accept)
        }

        "a parked upgrade handoff waiter" - contract(Op.UpgradeWaiter)(Kind.Connected) { s =>
            val waiter = waiterPromise()
            s.parkUpgradeWaiter(waiter)
            assert(waiter.poll().isEmpty, s"the waiter must park until the peer's flight arrives, got ${waiter.poll()}")
            s.cancel()
            waiterFailed("the parked upgrade handoff waiter after cancel", waiter)
        }

        "an armed upgrade producer read with Closed" - contract(Op.UpgradeProducer)(Kind.Connected) { s =>
            val producer = readPromise()
            s.armUpgradeProducer(producer)
            assert(producer.poll().isEmpty, s"the producer must stay armed until the peer's flight arrives, got ${producer.poll()}")
            s.cancel()
            failedClosed("the armed upgrade producer read after cancel", producer)
        }
    }

    "cancel takes no release claim" - {
        "a read parked after cancel is live and takes the peer's bytes" - contract(Op.Read)(Kind.Connected) { s =>
            s.cancel()
            val read = readPromise()
            s.awaitRead(read)
            assert(read.poll().isEmpty, s"a read parked after cancel must park, got ${read.poll()}")
            val payload = Array[Byte](1, 2, 3)
            s.peerSend(payload).andThen(read.safe.get).map {
                case ReadOutcome.Bytes(span) => assert(span.toArray.sameElements(payload), s"got ${span.toArray.toList}")
                case other                   => fail(s"a read parked after cancel must take the peer's bytes, got $other")
            }
        }

        "a read parked after detachForUpgrade is live and takes the peer's bytes" - contract(Op.Read, Op.Upgrade)(Kind.Connected) { s =>
            s.detachForUpgrade()
            val read = readPromise()
            s.awaitRead(read)
            assert(read.poll().isEmpty, s"a read parked after detachForUpgrade must park, got ${read.poll()}")
            val payload = Array[Byte](4, 5, 6)
            s.peerSend(payload).andThen(read.safe.get).map {
                case ReadOutcome.Bytes(span) => assert(span.toArray.sameElements(payload), s"got ${span.toArray.toList}")
                case other                   => fail(s"a read parked after detachForUpgrade must take the peer's bytes, got $other")
            }
        }

        "an upgrade handoff waiter parked after detachForUpgrade stays parked" - contract(Op.Upgrade, Op.UpgradeWaiter)(Kind.Connected) {
            s =>
                s.detachForUpgrade()
                val waiter = waiterPromise()
                s.parkUpgradeWaiter(waiter)
                assert(waiter.poll().isEmpty, s"a waiter parked after detachForUpgrade must park, got ${waiter.poll()}")
        }
    }

    "the release claim" - {
        "a read parked after it fails Closed at once" - contract(Op.Read)(Kind.Connected) { s =>
            s.release()
            val read = readPromise()
            s.awaitRead(read)
            failedClosed("a read parked after the release claim", read)
        }

        "a writable wait parked after it fails Closed at once" - contract(Op.Write)(Kind.Connected) { s =>
            s.fillSendBuffer().map { _ =>
                s.release()
                val writable = unitPromise()
                s.awaitWritable(writable)
                failedClosed("a writable wait parked after the release claim", writable)
            }
        }

        "a connect parked after it fails Closed at once" - contract(Op.Connect)(Kind.Connecting) { s =>
            s.release()
            val connect = unitPromise()
            s.awaitConnect(connect)
            failedClosed("a connect parked after the release claim", connect)
        }

        "the release fails every parked op with Closed" - contract(Op.Read, Op.Write)(Kind.Stalled) { s =>
            val read     = readPromise()
            val writable = unitPromise()
            s.awaitRead(read)
            s.awaitWritable(writable)
            assert(writable.poll().isEmpty, s"the writable wait must park on a stalled handle, got ${writable.poll()}")
            s.release()
            failedClosed("the parked read after the release", read)
            failedClosed("the parked writable wait after the release", writable)
        }

        "an upgrade handoff waiter parked after it fails at once" - contract(Op.UpgradeWaiter)(Kind.Connected) { s =>
            s.release()
            val waiter = waiterPromise()
            s.parkUpgradeWaiter(waiter)
            waiterFailed("an upgrade handoff waiter parked after the release claim", waiter)
        }

        "an upgrade producer read armed after it fails Closed at once" - contract(Op.UpgradeProducer)(Kind.Connected) { s =>
            s.release()
            val producer = readPromise()
            s.armUpgradeProducer(producer)
            failedClosed("an upgrade producer read armed after the release claim", producer)
        }

        "the release fails a parked upgrade handoff waiter at once" - contract(Op.UpgradeWaiter)(Kind.Connected) { s =>
            val waiter = waiterPromise()
            s.parkUpgradeWaiter(waiter)
            s.release()
            waiterFailed("the parked upgrade handoff waiter after the release", waiter)
        }

        "the release closes the descriptor exactly once, however often it is called" - contract(Op.CountsCloses)(Kind.Connected) { s =>
            s.release()
            s.release()
            s.descriptorClosed.andThen(s.stopDriver()).map { _ =>
                assert(s.descriptorCloses == 1, s"the descriptor was closed ${s.descriptorCloses} times")
            }
        }

        "an accept arm applied after closeListener began is rejected" - contract(Op.Accept, Op.ListenerClaim)(Kind.Listening) { s =>
            s.closeListener()
            val accept = Promise.Unsafe.init[Int, Abort[Closed | NetException]]()
            s.awaitAccept(accept)
            accept.safe.getResult.map { outcome =>
                assert(
                    outcome match
                        case Result.Failure(_: Closed) => true
                        case _                         => false
                    ,
                    s"an accept armed after closeListener must fail Closed, got $outcome"
                )
            }
        }
    }

end IoDriverCancelContractTest
