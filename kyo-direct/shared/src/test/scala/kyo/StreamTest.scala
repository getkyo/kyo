package kyo

class StreamTest extends Test:

    "Stream" - {
        "map" in run {
            val stream = Stream.init(Seq(1, 2, 3))

            def f(i: Int): Int < Var[Int] =
                Var.update[Int](_ + i)

            val newStream1: Stream[Int, Any] < Var[Int] = direct:
                stream.map(x => f(x).now)

            val newStream2: Stream[Int, Var[Int]] < Any = direct:
                stream.map(x => f(x).later)

            def check(stream: Stream[Int, Var[Int]] < Var[Int], expected: Chunk[Int]) =
                Var.run(0):
                    Stream.unwrap(stream).run.map: chunk =>
                        assert(chunk == expected)

            check(newStream1, Chunk(1, 3, 6))
            check(newStream2, Chunk(1, 3, 6))

        }

        "filter" in run {
            val stream = Stream.init(Seq(1, 2, 3))

            def f(i: Int): Boolean < Var[Int] =
                Var.update[Int](_ + i).map(_ % 2 == 0)

            val newStream1: Stream[Int, Any] < Var[Int] = direct:
                stream.filter(x => f(x).now)

            val newStream2: Stream[Int, Var[Int]] < Any = direct:
                stream.filter(x => f(x).later)

            def check(stream: Stream[Int, Var[Int]] < Var[Int], expected: Chunk[Int]) =
                Var.run(0):
                    Stream.unwrap(stream).run.map: chunk =>
                        assert(chunk == expected)

            check(newStream1, Chunk(3))
            check(newStream2, Chunk(3))
        }
    }
end StreamTest
