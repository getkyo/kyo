package kyo.bench

import kyo.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import scala.compiletime.uninitialized
import scala.reflect.ClassTag
import scala.util.Random

class ChannelBench extends BaseBench:

    import AllowUnsafe.embrace.danger

    var size: Int = 1024

    @Param(Array("1", "2", "4", "8"))
    var maxChunkSize: Int = uninitialized

    def intFillFn: Int = Random.nextInt()

    @Benchmark def streamChunks() =
        val x =
            for
                channel <- Channel.init[Int](size)
                _       <- channel.putBatch(Seq.fill(size)(intFillFn))
                _       <- Async.run(channel.closeAwaitEmpty)
                count   <- channel.streamUntilClosed(maxChunkSize).into(Sink.count)
            yield count
        val count = IO.Unsafe.evalOrThrow(Async.run(x).flatMap(_.block(Duration.Infinity))).getOrThrow
        assert(count == size, s"Expected $size, got $count")
    end streamChunks

end ChannelBench
