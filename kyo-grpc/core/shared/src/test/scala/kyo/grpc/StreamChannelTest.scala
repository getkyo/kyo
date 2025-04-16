package kyo.grpc

import kyo.*

class StreamChannelTest extends Test:

    "put and take" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(42)
            value   <- channel.take
        yield assert(value == Result.succeed(42))
    }

    "put does not complete" in run {
        for
            channel    <- StreamChannel.init[Int, String]
            _          <- channel.put(42)
            isComplete <- channel.completed
        yield assert(!isComplete)
    }

    "fail and take" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.fail("fail")
            value   <- channel.take
        yield assert(value == Result.fail("fail"))
    }

    "fail completes" in run {
        for
            channel    <- StreamChannel.init[Int, String]
            _          <- channel.fail("fail")
            isComplete <- channel.completed
        yield assert(isComplete)
    }

    "fail closes after take" in run {
        for
            channel   <- StreamChannel.init[Int, String]
            _         <- channel.fail("fail")
            wasClosed <- channel.closed
            _         <- channel.take
            isClosed  <- channel.closed
        yield
            assert(!wasClosed)
            assert(isClosed)
    }

    "error and take" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.error("error")
            value   <- channel.take
        yield assert(value == Result.fail("error"))
    }

    "error does not complete" in run {
        for
            channel    <- StreamChannel.init[Int, String]
            _          <- channel.error("error")
            isComplete <- channel.completed
        yield assert(!isComplete)
    }

    "error does not close after take" in run {
        for
            channel   <- StreamChannel.init[Int, String]
            _         <- channel.error("error")
            wasClosed <- channel.closed
            _         <- channel.take
            isClosed  <- channel.closed
        yield
            assert(!wasClosed)
            assert(!isClosed)
    }

    "complete" in run {
        for
            channel    <- StreamChannel.init[Int, String]
            _          <- channel.complete
            isComplete <- channel.completed
        yield assert(isComplete)
    }

    "complete closes empty channel" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.complete
            closed  <- channel.closed
        yield assert(closed)
    }

    "take last element closes channel" in run {
        for
            channel  <- StreamChannel.init[Int, String]
            _        <- channel.put(1)
            _        <- channel.complete
            _        <- channel.take
            isClosed <- channel.closed
        yield assert(isClosed)
    }

    "take last elements closes channel" in run {
        for
            channel   <- StreamChannel.init[Int, String]
            _         <- channel.put(1)
            _         <- channel.put(2)
            _         <- channel.complete
            _         <- channel.take
            wasClosed <- channel.closed
            _         <- channel.take
            isClosed  <- channel.closed
        yield
            assert(!wasClosed)
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

    "put fails when complete" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.complete
            result  <- Abort.run(channel.put(42))
        yield assert(result.failure.get.isInstanceOf[Closed])
    }

    "put fails when complete and non-empty" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(31)
            _       <- channel.complete
            result  <- Abort.run(channel.put(42))
        yield assert(result.failure.get.isInstanceOf[Closed])
    }

    "fail fails when complete and non-empty" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(31)
            _       <- channel.complete
            result  <- Abort.run(channel.fail("fail"))
        yield assert(result.failure.get.isInstanceOf[Closed])
    }

    "error fails when complete and non-empty" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(31)
            _       <- channel.complete
            result  <- Abort.run(channel.error("error"))
        yield assert(result.failure.get.isInstanceOf[Closed])
    }

    "stream ends after last element when channel completed" in run {
        for
            channel <- StreamChannel.init[Int, String]
            fiber   <- Async.run(channel.stream.run)
            _       <- channel.put(1)
            _      <- channel.complete
            result <- fiber.get
        yield assert(result == Chunk(1))
    }

    "stream ends after last elements when channel completed" in run {
        for
            channel <- StreamChannel.init[Int, String]
            fiber   <- Async.run(channel.stream.run)
            _       <- channel.put(1)
            _       <- channel.put(2)
            _       <- channel.put(3)
            _      <- channel.complete
            result <- fiber.get
        yield assert(result == Chunk(1, 2, 3))
    }

end StreamChannelTest
