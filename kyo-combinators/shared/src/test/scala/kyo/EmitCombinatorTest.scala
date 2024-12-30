package kyo

import scala.concurrent.Future
import scala.util.Try

class EmitCombinatorTest extends Test:

    given ce[A, B]: CanEqual[A, B] = CanEqual.canEqualAny

    "emit" - {
        "handleEmit" in run {
            val emit = Loop(1)(i => if i == 4 then Loop.done(()) else Emit.andMap(i)(_ => Loop.continue(i + 1))).map(_ => "done")
            emit.handleEmit.map:
                case (chunk, res) => assert(chunk == Chunk(1, 2, 3) && res == "done")
        }

        "handleEmitDiscarding" in run {
            val emit = Loop(1)(i => if i == 4 then Loop.done(()) else Emit.andMap(i)(_ => Loop.continue(i + 1))).map(_ => "done")
            emit.handleEmitDiscarding.map:
                case chunk => assert(chunk == Chunk(1, 2, 3))
        }

        "foreachEmit" in run {
            val emit = Loop(1)(i => if i == 4 then Loop.done(()) else Emit.andMap(i)(_ => Loop.continue(i + 1)))
            val effect = emit.foreachEmit(i => Var.update[Int](v => v + i).unit)
                // Get the final state
                .map(_ => Var.get[Int])
            Var.run(0)(effect).map: result =>
                assert(result == 6)
        }

        "emitToChannel" in run {
            val emit = Loop(1)(i => if i == 4 then Loop.done(()) else Emit.andMap(i)(_ => Loop.continue(i + 1)))
            for
                channel <- Channel.init[Int](10)
                _       <- emit.emitToChannel(channel)
                values  <- channel.drain
            yield assert(values == Chunk(1, 2, 3))
            end for
        }

        "emitChunked with number of emitted values divisible by chunk size" in run {
            val emit        = Loop(1)(i => if i == 5 then Loop.done(()) else Emit.andMap(i)(_ => Loop.continue(i + 1)))
            val chunkedEmit = emit.emitChunked(2)
            chunkedEmit.handleEmitDiscarding.map: result =>
                assert(result == Chunk(Chunk(1, 2), Chunk(3, 4)))
        }

        "emitChunked with number of emitted values not divisible by chunk size" in run {
            val emit        = Loop(1)(i => if i == 6 then Loop.done(()) else Emit.andMap(i)(_ => Loop.continue(i + 1)))
            val chunkedEmit = emit.emitChunked(2)
            chunkedEmit.handleEmitDiscarding.map: result =>
                assert(result == Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5)))
        }

        "emitChunkedToStream" in run {
            val emit   = Loop(0)(i => if i == 9 then Loop.done(()) else Emit.andMap(i)(_ => Loop.continue(i + 1))).map(_ => Ack.Continue())
            val stream = emit.emitChunkedToStream(2)
            stream.run.map: chunk =>
                assert(chunk == Chunk.from(0 until 9))
        }

        "emitChunkedToStreamDiscarding" in run {
            val emit   = Loop(0)(i => if i == 9 then Loop.done(()) else Emit.andMap(i)(_ => Loop.continue(i + 1)))
            val stream = emit.emitChunkedToStreamDiscarding(2)
            stream.run.map: chunk =>
                assert(chunk == Chunk.from(0 until 9))
        }

        "emitChunkedToStreamAndResult" in run {
            val emit = Loop(0)(i => if i == 9 then Loop.done(()) else Emit.andMap(i)(_ => Loop.continue(i + 1))).map(_ => "done")
            for
                (stream, handled) <- emit.emitChunkedToStreamAndResult(2)
                streamRes         <- stream.run
                handledRes        <- handled
            yield assert(streamRes == Chunk.from(0 until 9) && handledRes == "done")
            end for
        }
    }

    "chunked emit" - {
        "emitToStream" in run {
            val emit   = Stream.init(0 until 9).emit
            val stream = emit.emitToStream
            stream.run.map: chunk =>
                assert(chunk == Chunk.from(0 until 9))
        }

        "emitToStreamDiscarding" in run {
            val emit   = Kyo.foreach(0 until 3)(i => Emit[Chunk[Int]](Chunk.from((i * 3) until (i * 3) + 3)).map(_ => i))
            val stream = emit.emitToStreamDiscarding
            stream.run.map: chunk =>
                assert(chunk == Chunk.from(0 until 9))
        }

        "emitToStreamAndResult" in run {
            val emit = Kyo.foreach(0 until 3)(i => Emit[Chunk[Int]](Chunk.from((i * 3) until (i * 3) + 3)).map(_ => i))
            for
                (stream, handled) <- emit.emitToStreamAndResult
                streamRes         <- stream.run
                handledRes        <- handled
            yield assert(streamRes == Chunk.from(0 until 9) && handledRes == Chunk.from(0 until 3))
            end for
        }
    }

end EmitCombinatorTest
