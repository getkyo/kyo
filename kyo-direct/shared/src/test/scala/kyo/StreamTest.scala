package kyo

class StreamTest extends Test:

    def check(stream: Stream[Int, Var[Int]] < Var[Int], expected: Chunk[Int]): Assertion < Any =
        Var.run(0):
            Stream.unwrap(stream).run.map: chunk =>
                assert(chunk == expected)

    "Stream" - {
        "map" in run {
            val stream = Stream.init(Seq(1, 2, 3))

            def f(i: Int): Int < Var[Int] =
                Var.update[Int](_ + i)

            typeCheckFailure(
                """val newStream1: Stream[Int, Any] < Var[Int] = direct(stream.map(x => f(x).now))""".stripMargin
            )("Effectful computations must explicitly use either .now or .later in a direct block.")

            val newStream2: Stream[Int, Var[Int]] < Any = direct:
                stream.map(x => f(x))

            val newStream2_1: Stream[Int, Var[Int]] < Any = direct:
                stream.map(f)

            val newStream3 = direct:
                stream.map(x =>
                    direct:
                        f(x).now + 1
                )

            val x = direct:
                direct(1 + 1).now

            check(newStream2, Chunk(1, 3, 6))

        }

        "filter" in run {
            val stream = Stream.init(Seq(1, 2, 3))

            def f(i: Int): Boolean < Var[Int] =
                Var.update[Int](_ + i).map(_ % 2 == 0)

            typeCheckFailure(
                """val newStream1: Stream[Int, Any] < Var[Int] = direct(stream.filter(x => f(x).now))"""
            )("Effectful computations must explicitly use either .now or .later in a direct block.")

            val newStream2: Stream[Int, Var[Int]] < Any = direct:
                stream.filter(x => f(x).later)

            check(newStream2, Chunk(3))
        }
    }
end StreamTest
