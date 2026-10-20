package kyo.net.internal.transport

import kyo.*
import kyo.net.NetConnectionClosedException
import kyo.net.Test

class OutboundLedgerTest extends Test:

    import AllowUnsafe.embrace.danger

    private def send() = NetConnectionClosedException(NetConnectionClosedException.Operation.Send, "first")

    "owed" - {

        "counts accepted plaintext until it is encoded, then the wire bytes until the kernel takes them" in {
            val ledger = OutboundLedger.init()
            ledger.accept(100)
            assert(ledger.owed == 100)
            ledger.encode(100, 129)
            assert(ledger.owed == 129, "after encoding, the record's wire bytes are owed, overhead included")
            ledger.send(29)
            assert(ledger.owed == 100)
            ledger.send(100)
            assert(ledger.owed == 0)
            assert(ledger.sentTotal == 129)
        }

        "a driver-generated record is owed like data" in {
            val ledger = OutboundLedger.init()
            ledger.generate(31)
            assert(ledger.owed == 31)
            ledger.send(31)
            assert(ledger.owed == 0)
        }

        "a direct write owes nothing and still counts as progress" in {
            val ledger = OutboundLedger.init()
            ledger.direct(4096)
            assert(ledger.owed == 0)
            assert(ledger.sentTotal == 4096)
        }

        "sentTotal is monotonic across a tail that fills and drains" in {
            val ledger = OutboundLedger.init()
            ledger.accept(10)
            ledger.encode(10, 10)
            ledger.send(10)
            val first = ledger.sentTotal
            ledger.accept(10)
            ledger.encode(10, 10)
            assert(ledger.owed == 10)
            ledger.send(10)
            assert(ledger.owed == 0 && ledger.sentTotal == first + 10)
        }
    }

    "onDrained" - {

        "runs at once when nothing is owed" in {
            val ledger = OutboundLedger.init()
            var runs   = 0
            ledger.onDrained(() => runs += 1)
            assert(runs == 1)
        }

        "runs once, inside the send that brings owed to zero" in {
            val ledger = OutboundLedger.init()
            ledger.accept(8)
            ledger.encode(8, 8)
            var runs = 0
            ledger.onDrained(() => runs += 1)
            assert(runs == 0)
            ledger.send(5)
            assert(runs == 0, "a send that leaves bytes owed does not fire it")
            ledger.send(3)
            assert(runs == 1)
            ledger.accept(1)
            ledger.encode(1, 1)
            ledger.send(1)
            assert(runs == 1, "the waiter is one-shot")
        }

        "runs inside the encode that brings owed to zero" in {
            val ledger = OutboundLedger.init()
            ledger.accept(4)
            var runs = 0
            ledger.onDrained(() => runs += 1)
            ledger.encode(4, 0)
            assert(runs == 1)
        }

        "a later registration replaces one that has not fired" in {
            val ledger = OutboundLedger.init()
            ledger.generate(2)
            var first  = 0
            var second = 0
            ledger.onDrained(() => first += 1)
            ledger.onDrained(() => second += 1)
            ledger.send(2)
            assert(first == 0 && second == 1)
        }
    }

    "fail" - {

        "the first failure wins and stays" in {
            val ledger = OutboundLedger.init()
            val first  = send()
            val later  = NetConnectionClosedException(NetConnectionClosedException.Operation.Send, "later")
            assert(ledger.failure.isEmpty)
            assert(ledger.fail(first))
            assert(!ledger.fail(later))
            assert(ledger.failure.exists(_ eq first))
        }

        "fires a waiting onDrained once, though bytes are still owed" in {
            val ledger = OutboundLedger.init()
            ledger.generate(8)
            var runs = 0
            ledger.onDrained(() => runs += 1)
            assert(ledger.fail(send()))
            assert(runs == 1, "what is owed never drains after a failure, so the waiter must learn of it")
            assert(!ledger.fail(send()))
            ledger.send(8)
            assert(runs == 1, "the waiter is one-shot")
        }

        "makes a later onDrained run at once" in {
            val ledger = OutboundLedger.init()
            ledger.generate(8)
            discard(ledger.fail(send()))
            var runs = 0
            ledger.onDrained(() => runs += 1)
            assert(runs == 1)
        }
    }

end OutboundLedgerTest
