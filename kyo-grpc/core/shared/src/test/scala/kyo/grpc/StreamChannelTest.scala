package kyo.grpc

import kyo.*

class StreamChannelTest extends Test:

    // The time to wait before assuming that the consumer is blocked on the channel.
    private val delay = 100.millis

    "put" - {
        "does not close producer" in run {
            for
                channel        <- StreamChannel.init[Int, String]("Test")
                _              <- channel.put(42)
                producerClosed <- channel.producerClosed
            yield assert(!producerClosed)
        }

        "fails when producer closed and empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.put(1))
            yield assert(result.isFailure)
        }

        "fails when producer closed and not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.put(1)
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.put(2))
            yield assert(result.isFailure)
        }

        "fails after error" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.error("error")
                result  <- Abort.run[Closed](channel.put(1))
            yield assert(result.isFailure)
        }
    }

    "error" - {
        "completes producer" in run {
            for
                channel        <- StreamChannel.init[Int, String]("Test")
                _              <- channel.error("error")
                producerClosed <- channel.producerClosed
            yield assert(producerClosed)
        }

        "before take closes consumer" in run {
            for
                channel   <- StreamChannel.init[Int, String]("Test")
                _         <- channel.error("error")
                wasClosed <- channel.consumerClosed
                _         <- Abort.run(channel.take)
                isClosed  <- channel.consumerClosed
            yield
                assert(!wasClosed)
                assert(isClosed)
        }

        "fails when producer closed and empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.error("error"))
            yield assert(result.isFailure)
        }

        "fails when producer closed and not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.put(1)
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.error("error"))
            yield assert(result.isFailure)
        }

        "interrupts take" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                fiber   <- Async.run(channel.take)
                _       <- Async.delay(delay)(channel.error("error"))
                result  <- Abort.run(fiber.get)
            yield assert(result == Result.fail("error"))
        }

        "interrupts stream" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                fiber   <- Async.run(channel.stream.run)
                _       <- Async.delay(delay)(channel.error("error"))
                result  <- Abort.run(fiber.get)
            yield assert(result == Result.fail("error"))
        }
    }

    "close producer" - {
        "closes producer" in run {
            for
                channel        <- StreamChannel.init[Int, String]("Test")
                _              <- channel.closeProducer
                producerClosed <- channel.producerClosed
            yield assert(producerClosed)
        }

        "closes consumer if channel is empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.closeProducer
                closed  <- channel.consumerClosed
            yield assert(closed)
        }

        "does not close consumer if channel is not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.put(1)
                _       <- channel.closeProducer
                closed  <- channel.consumerClosed
            yield assert(!closed)
        }

        "interrupts take" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                fiber   <- Async.run(channel.take)
                _       <- Async.delay(delay)(channel.closeProducer)
                result  <- Abort.run[Closed](fiber.get)
            yield assert(result.isFailure)
        }

        "interrupts stream" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                fiber   <- Async.run(channel.stream.run)
                _       <- Async.delay(delay)(channel.closeProducer)
                result  <- fiber.get
            yield assert(result == Chunk.empty)
        }
    }

    "take" - {
        "not empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.put(1)
                value   <- channel.take
            yield assert(value == 1)
        }

        "last closes consumer" in run {
            for
                channel  <- StreamChannel.init[Int, String]("Test")
                _        <- channel.put(1)
                _        <- channel.closeProducer
                _        <- channel.take
                isClosed <- channel.consumerClosed
            yield assert(isClosed)
        }

        "last of multiple closes consumer" in run {
            for
                channel   <- StreamChannel.init[Int, String]("Test")
                _         <- channel.put(1)
                _         <- channel.put(2)
                _         <- channel.closeProducer
                _         <- channel.take
                wasClosed <- channel.consumerClosed
                _         <- channel.take
                isClosed  <- channel.consumerClosed
            yield
                assert(!wasClosed)
                assert(isClosed)
        }

        "last error closes consumer" in run {
            for
                channel   <- StreamChannel.init[Int, String]("Test")
                _         <- channel.put(1)
                _         <- channel.error("error")
                _         <- channel.take
                wasClosed <- channel.consumerClosed
                _         <- Abort.run(channel.take)
                isClosed  <- channel.consumerClosed
            yield
                assert(!wasClosed)
                assert(isClosed)
        }

        "fails when closed and empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.closeProducer
                result  <- Abort.run[Closed](channel.take)
            yield assert(result.isFailure)
        }

        "fails when closed after last take" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.put(1)
                _       <- channel.closeProducer
                _       <- channel.take
                result  <- Abort.run[Closed](channel.take)
            yield assert(result.isFailure)
        }

        "fails with error when empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.error("error")
                result  <- Abort.run(channel.take)
            yield assert(result == Result.fail("error"))
        }

        "fails with error after last take" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
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
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.put(1)
                _       <- channel.put(2)
                _       <- channel.put(3)
                chunks  <- channel.stream.take(3).run
            yield assert(chunks == Chunk(1, 2, 3))
        }

        "stops when producer closes" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                fiber   <- Async.run(channel.stream.run)
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

        "closes consumer when done" in run {
            for
                channel   <- StreamChannel.init[Int, String]("Test")
                fiber     <- Async.run(channel.stream.run)
                _         <- channel.put(1)
                _         <- channel.put(2)
                _         <- channel.put(3)
                wasClosed <- Async.delay(delay)(channel.consumerClosed)
                _         <- channel.closeProducer
                _         <- fiber.get
                isClosed  <- channel.consumerClosed
            yield
                assert(!wasClosed)
                assert(isClosed)
        }

        "concurrent with puts" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                // Don't forget to only use a single producer.
                _      <- Async.run(Loop(1)(i => channel.put(i).andThen(Loop.continue(i + 1))))
                result <- channel.stream.take(10).run
            yield assert(result == Chunk.from(1 to 10))
        }

        "last error closes consumer" in run {
            for
                channel   <- StreamChannel.init[Int, String]("Test")
                fiber     <- Async.run(channel.stream.splitAt(1))
                _         <- channel.put(1)
                _         <- channel.error("error")
                (_, tail) <- fiber.get
                wasClosed <- channel.consumerClosed
                _         <- Abort.run(tail.run)
                isClosed  <- channel.consumerClosed
            yield
                assert(!wasClosed)
                assert(isClosed)
        }

        "fails with error when empty" in run {
            for
                channel <- StreamChannel.init[Int, String]("Test")
                _       <- channel.error("error")
                result  <- Abort.run(channel.stream.run)
            yield assert(result == Result.fail("error"))
        }

        "fails with error after last take" in run {
            for
                channel      <- StreamChannel.init[Int, String]("Test")
                fiber        <- Async.run(channel.stream.splitAt(1))
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
