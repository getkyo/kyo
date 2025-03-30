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

    "fail and take error" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.fail("error")
            value   <- channel.take
        yield assert(value == Result.fail("error"))
    }

    "complete and check completed state" in run {
        for
            channel    <- StreamChannel.init[Int, String]
            _          <- channel.complete
            isComplete <- channel.completed
        yield assert(isComplete)
    }

    "close channel after completion" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.complete
            closed  <- channel.closed
        yield assert(closed)
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
            channel <- StreamChannel.init[Int, String]
            bg      <- Async.run(Loop(0)(i => channel.put(i).andThen(Loop.continue(i + 1))))
            stream = channel.stream.take(10)
            result <- stream.run
            _      <- bg.interrupt
        yield assert(result == Chunk.from(0 until 10))
    }

    "fail when channel is closed" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.complete
            result  <- Abort.run(channel.put(42))
        yield assert(result.isFailure)
    }

    "stream stops when channel is completed after one put" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(1)
            _       <- channel.complete
            stream = channel.stream
            result <- stream.run
        yield assert(result == Chunk(1))
    }

    "stream stops when channel is completed after multiple puts" in run {
        for
            channel <- StreamChannel.init[Int, String]
            _       <- channel.put(1)
            _       <- channel.put(2)
            _       <- channel.complete
            stream = channel.stream
            result <- stream.run
        yield assert(result == Chunk(1, 2))
    }

end StreamChannelTest
