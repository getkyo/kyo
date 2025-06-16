package kyo

class StreamCombinatorsTest extends Test:

    "unwrap" - {
        "should fuse effect contexts from wrapped stream with Var" in run {

            val tick = Var.updateDiscard[Int](_ + 1)

            val stream = tick.andThen(Stream.range(-1, 2)).unwrapStream

            val n = stream.foreach(_ => tick).handle(_.andThen(Var.get[Int]), Var.run(0)).eval

            assert(n == 4)
        }
    }
end StreamCombinatorsTest
