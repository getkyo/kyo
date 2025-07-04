package kyo.grpc

import kyo.*

class StreamChannelTest extends Test:

    // The time to wait before assuming that the consumer is blocked on the channel.
    private val delay = 100.millis

    "put" - {
        "does not close" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(42)
                closed  <- channel.closed
            yield assert(!closed)
        }

        "fails when producer closed and empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.put(1))
            yield assert(result.isFailure)
        }

        "fails when producer closed and not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.put(2))
            yield assert(result.isFailure)
        }

        "fails after error" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.error("error")
                result  <- Abort.run[Closed](channel.put(1))
            yield assert(result.isFailure)
        }
    }

    "error" - {
        "does not close" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.error("error")
                closed  <- channel.closed
            yield assert(!closed)
        }

        "fails when called more than once when empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.error("error 1")
                result  <- Abort.run[Closed](channel.error("error 2"))
            yield assert(result.isFailure)
        }

        "fails when called more than once when not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                _       <- channel.error("error 1")
                result  <- Abort.run[Closed](channel.error("error 2"))
            yield assert(result.isFailure)
        }

        "fails when producer closed and empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.error("error"))
            yield assert(result.isFailure)
        }

        "fails when producer closed and not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.error("error"))
            yield assert(result.isFailure)
        }

        "interrupts take" in run {
            for
                channel <- StreamChannel.init[Int, String]
                fiber   <- Fiber.run(channel.take)
                _       <- Async.delay(delay)(channel.error("error"))
                result  <- Abort.run(fiber.get)
            yield assert(result == Result.fail("error"))
        }

        "interrupts stream" in run {
            for
                channel <- StreamChannel.init[Int, String]
                fiber   <- Fiber.run(channel.stream.run)
                _       <- Async.delay(delay)(channel.error("error"))
                result  <- Abort.run(fiber.get)
            yield assert(result == Result.fail("error"))
        }
    }

    "close producer" - {
        "closes if channel is empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.closeProducer
                closed  <- channel.closed
            yield assert(closed)
        }

        "does not close if channel is not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                _       <- channel.closeProducer
                closed  <- channel.closed
            yield assert(!closed)
        }

        "interrupts take" in run {
            for
                channel <- StreamChannel.init[Int, String]
                fiber   <- Fiber.run(channel.take)
                _       <- Async.delay(delay)(channel.closeProducer)
                result  <- Abort.run[Closed](fiber.get)
            yield assert(result.isFailure)
        }

        "interrupts stream" in run {
            for
                channel <- StreamChannel.init[Int, String]
                fiber   <- Fiber.run(channel.stream.run)
                _       <- Async.delay(delay)(channel.closeProducer)
                result  <- fiber.get
            yield assert(result == Chunk.empty)
        }
    }

    "close" - {
        "closes immediately when empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.close
                closed  <- channel.closed
            yield assert(closed)
        }

        "closes immediately when not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                _       <- channel.put(2)
                _       <- channel.close
                closed  <- channel.closed
            yield assert(closed)
        }

        "discards elements" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                _       <- channel.put(2)
                _       <- channel.close
                result  <- Abort.run[Closed](channel.take)
            yield assert(result.isFailure)
        }

        "fails put after close" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.close
                result  <- Abort.run[Closed](channel.put(1))
            yield assert(result.isFailure)
        }

        "fails error after close" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.close
                result  <- Abort.run[Closed](channel.error("error"))
            yield assert(result.isFailure)
        }

        "interrupts take" in run {
            for
                channel <- StreamChannel.init[Int, String]
                fiber   <- Fiber.run(channel.take)
                _       <- Async.delay(delay)(channel.close)
                result  <- Abort.run[Closed](fiber.get)
            yield assert(result.isFailure)
        }

        "interrupts stream" in run {
            for
                channel <- StreamChannel.init[Int, String]
                fiber   <- Fiber.run(channel.stream.run)
                _       <- Async.delay(delay)(channel.close)
                result  <- fiber.get
            yield assert(result == Chunk.empty)
        }
    }

    "take" - {
        "not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                value   <- channel.take
            yield assert(value == 1)
        }

        "last closes" in run {
            for
                channel  <- StreamChannel.init[Int, String]
                _        <- channel.put(1)
                _        <- channel.closeProducer
                _        <- channel.take
                isClosed <- channel.closed
            yield assert(isClosed)
        }

        "last of multiple closes" in run {
            for
                channel   <- StreamChannel.init[Int, String]
                _         <- channel.put(1)
                _         <- channel.put(2)
                _         <- channel.closeProducer
                _         <- channel.take
                wasClosed <- channel.closed
                _         <- channel.take
                isClosed  <- channel.closed
            yield
                assert(!wasClosed)
                assert(isClosed)
        }

        "last error closes" in run {
            for
                channel   <- StreamChannel.init[Int, String]
                _         <- channel.put(1)
                _         <- channel.error("error")
                _         <- channel.take
                wasClosed <- channel.closed
                _         <- Abort.run(channel.take)
                isClosed  <- channel.closed
            yield
                assert(!wasClosed)
                assert(isClosed)
        }

        "fails when closed and empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.take)
            yield assert(result.isFailure)
        }

        "fails when closed after last take" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                _       <- channel.closeProducer
                _       <- channel.take
                result  <- Abort.run[Closed](channel.take)
            yield assert(result.isFailure)
        }

        "fails with error when empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.error("error")
                result  <- Abort.run(channel.take)
            yield assert(result == Result.fail("error"))
        }

        "fails with error after last take" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                _       <- channel.error("error")
                _       <- channel.take
                result  <- Abort.run(channel.take)
            yield assert(result == Result.fail("error"))
        }
    }

    "stream" - {
        "emit multiple" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.put(1)
                _       <- channel.put(2)
                _       <- channel.put(3)
                chunks  <- channel.stream.take(3).run
            yield assert(chunks == Chunk(1, 2, 3))
        }

        "stops when producer closes" in run {
            for
                channel <- StreamChannel.init[Int, String]
                fiber   <- Fiber.run(channel.stream.run)
                _       <- channel.put(1)
                _       <- channel.put(2)
                _       <- channel.put(3)
                wasDone <- Async.delay(delay)(fiber.done)
                _       <- channel.closeProducer
                chunks  <- fiber.get
            yield
                assert(!wasDone)
                assert(chunks == Chunk(1, 2, 3))
        }

        "closes when done" in run {
            for
                channel   <- StreamChannel.init[Int, String]
                fiber     <- Fiber.run(channel.stream.run)
                _         <- channel.put(1)
                _         <- channel.put(2)
                _         <- channel.put(3)
                wasClosed <- Async.delay(delay)(channel.closed)
                _         <- channel.closeProducer
                _         <- fiber.get
                isClosed  <- channel.closed
            yield
                assert(!wasClosed)
                assert(isClosed)
        }

        "concurrent with puts" in run {
            for
                channel <- StreamChannel.init[Int, String]
                // Don't forget to only use a single producer.
                _      <- Fiber.run(Loop(1)(i => channel.put(i).andThen(Loop.continue(i + 1))))
                result <- channel.stream.take(10).run
            yield assert(result == Chunk.from(1 to 10))
        }

        "last error closes" in run {
            for
                channel   <- StreamChannel.init[Int, String]
                fiber     <- Fiber.run(channel.stream.splitAt(1))
                _         <- channel.put(1)
                _         <- channel.error("error")
                (_, tail) <- fiber.get
                wasClosed <- channel.closed
                _         <- Abort.run(tail.run)
                isClosed  <- channel.closed
            yield
                assert(!wasClosed)
                assert(isClosed)
        }

        "fails with error when empty" in run {
            for
                channel <- StreamChannel.init[Int, String]
                _       <- channel.error("error")
                result  <- Abort.run(channel.stream.run)
            yield assert(result == Result.fail("error"))
        }

        "fails with error after last take" in run {
            for
                channel      <- StreamChannel.init[Int, String]
                fiber        <- Fiber.run(channel.stream.splitAt(1))
                _            <- channel.put(1)
                (head, tail) <- fiber.get
                _            <- channel.error("error")
                result       <- Abort.run(tail.run)
            yield
                assert(head == Chunk(1))
                assert(result == Result.fail("error"))
        }
    }

end StreamChannelTest
