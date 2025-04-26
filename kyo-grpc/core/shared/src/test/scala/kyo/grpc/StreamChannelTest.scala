package kyo.grpc

import kyo.*

class StreamChannelTest extends Test:

    "put and take" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(42)
            value   <- channel.take
        yield assert(value == 42)
    }

    "put does not close producer" in run {
        for
            channel        <- StreamChannel.init[Int, String]
            _              <- channel.put(42)
            producerClosed <- channel.producerClosed
        yield assert(!producerClosed)
    }

    "error and take" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.error("error")
            value   <- Abort.run(channel.take)
        yield assert(value == Result.fail("error"))
    }

    "error completes producer" in run {
        for
            channel        <- StreamChannel.init[Int, String]
            _              <- channel.error("error")
            producerClosed <- channel.producerClosed
        yield assert(producerClosed)
    }

    "error closes consumer after take" in run {
        for
            channel   <- StreamChannel.init[Int, String]
            _         <- channel.error("error")
            wasClosed <- channel.consumerClosed
            _         <- Abort.run(channel.take)
            isClosed  <- channel.consumerClosed
        yield
            assert(!wasClosed)
            assert(isClosed)
    }

    "close producer" in run {
        for
            channel        <- StreamChannel.init[Int, String]
            _              <- channel.closeProducer
            producerClosed <- channel.producerClosed
        yield assert(producerClosed)
    }

    "closing producer closes consumer if channel is empty" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.closeProducer
            closed  <- channel.consumerClosed
        yield assert(closed)
    }

    "take last element closes consumer" in run {
        for
            channel  <- StreamChannel.init[Int, String]
            _        <- channel.put(1)
            _        <- channel.closeProducer
            _        <- channel.take
            isClosed <- channel.consumerClosed
        yield assert(isClosed)
    }

    // TODO: Take only returns error when empty.

    "take last elements closes consumer" in run {
        for
            channel   <- StreamChannel.init[Int, String]
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

    "take error after elements closes consumer" in run {
        for
            channel   <- StreamChannel.init[Int, String]
            _         <- channel.put(1)
            _         <- channel.error("error")
            _         <- channel.closeProducer
            _         <- channel.take
            wasClosed <- channel.consumerClosed
            result    <- Abort.run(channel.take)
            isClosed  <- channel.consumerClosed
        yield
            assert(!wasClosed)
            assert(result == Result.fail("error"))
            assert(isClosed)
    }

    "emit chunks from stream" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(1)
            _       <- channel.put(2)
            _       <- channel.put(3)
            stream = channel.stream
            chunks <- stream.take(3).run
        yield assert(chunks == Chunk(1, 2, 3))
    }

    "stream concurrently with ingestion" in run {
        for
            // Don't forget to only use a single producer and a single consumer.
            channel <- StreamChannel.init[Int, String]
            fiber   <- Async.run(Loop(1)(i => channel.put(i).andThen(Loop.continue(i + 1))))
            stream = channel.stream.take(10)
            result <- stream.run
            _      <- fiber.interrupt
        yield assert(result == Chunk.from(1 to 10))
    }

    "put fails when producer closed" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.closeProducer
            result  <- Abort.run(channel.put(42))
        yield assert(result.failure.get.isInstanceOf[Closed])
    }

    "put fails when producer closed and non-empty" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(31)
            _       <- channel.closeProducer
            result  <- Abort.run(channel.put(42))
        yield assert(result.failure.get.isInstanceOf[Closed])
    }

    "error fails when producer closed and non-empty" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(31)
            _       <- channel.closeProducer
            result  <- Abort.run(channel.error("error"))
        yield assert(result.failure.get.isInstanceOf[Closed])
    }

    "stream ends after last element when producer closed" in run {
        for
            channel <- StreamChannel.init[Int, String]
            fiber   <- Async.run(channel.stream.run)
            _       <- channel.put(1)
            _       <- channel.closeProducer
            result  <- fiber.get
        yield assert(result == Chunk(1))
    }

    "stream ends after last elements when producer closed" in run {
        for
            channel <- StreamChannel.init[Int, String]
            fiber   <- Async.run(channel.stream.run)
            _       <- channel.put(1)
            _       <- channel.put(2)
            _       <- channel.put(3)
            _       <- channel.closeProducer
            result  <- fiber.get
        yield assert(result == Chunk(1, 2, 3))
    }

end StreamChannelTest
