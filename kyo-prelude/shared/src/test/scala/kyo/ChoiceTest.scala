package kyo

import Tagged.*

class ChoiceTest extends Test:

    "eval with a single choice" in {
        assert(
            Choice.run(Choice.eval(Seq(1))(i => (i + 1))).eval == Seq(2)
        )
    }

    "eval with multiple choices" in {
        assert(
            Choice.run(Choice.eval(Seq(1, 2, 3))(i => (i + 1))).eval == Seq(2, 3, 4)
        )
    }

    "nested eval" in {
        assert(
            Choice.run(Choice.eval(Seq(1, 2, 3))(i =>
                Choice.get(Seq(i * 10, i * 100))
            )).eval == Seq(10, 100, 20, 200, 30, 300)
        )
    }

    "drop" in {
        assert(
            Choice.run(Choice.eval(Seq(1, 2, 3))(i =>
                if i < 2 then Choice.drop else Choice.get(Seq(i * 10, i * 100))
            )).eval == Seq(20, 200, 30, 300)
        )
    }

    "filter" in {
        assert(
            Choice.run(Choice.eval(Seq(1, 2, 3))(i =>
                Choice.dropIf(i < 2).map(_ => Choice.get(Seq(i * 10, i * 100)))
            )).eval == Seq(20, 200, 30, 300)
        )
    }

    "empty choices" in {
        assert(
            Choice.run(Choice.eval(Seq.empty[Int])(_ => 42)).eval == Seq.empty[Int]
        )
    }

    "nested drop" in {
        assert(
            Choice.run(
                Choice.eval(Seq(1, 2, 3))(i =>
                    Choice.eval(Seq(i * 10, i * 100))(j =>
                        if j > 100 then Choice.drop else j
                    )
                )
            ).eval == Seq(10, 100, 20, 30)
        )
    }

    "nested filter" in {
        assert(
            Choice.run(
                Choice.eval(Seq(1, 2, 3))(i =>
                    Choice.dropIf(i % 2 != 0).map(_ =>
                        Choice.eval(Seq(i * 10, i * 100))(j =>
                            Choice.dropIf(j >= 300).map(_ => j)
                        )
                    )
                )
            ).eval == Seq(20, 200)
        )
    }

    "large number of choices" in {
        val largeChoice = Seq.range(0, 100000)
        try
            assert(
                Choice.run(Choice.get(largeChoice)).eval == largeChoice
            )
        catch
            case ex: StackOverflowError => fail()
        end try
    }

    "large number of suspensions" taggedAs notNative in pendingUntilFixed {
        // https://github.com/getkyo/kyo/issues/208
        var v = Choice.get(Seq(1))
        for _ <- 0 until 100000 do
            v = v.map(_ => Choice.get(Seq(1)))
        try
            assert(
                Choice.run(v).eval == Seq(1)
            )
        catch
            case ex: StackOverflowError => fail()
        end try
        ()
    }

    "interaction with collection operations" - {
        "foreach" in {
            val result = Choice.run(
                Kyo.foreach(List("x", "y")) { str =>
                    Choice.get(List(true, false)).map(b =>
                        if b then str.toUpperCase else str
                    )
                }
            ).eval

            assert(result.contains(Chunk("X", "Y")))
            assert(result.contains(Chunk("X", "y")))
            assert(result.contains(Chunk("x", "Y")))
            assert(result.contains(Chunk("x", "y")))
            assert(result.size == 4)
        }

        "collect" in {
            val effects =
                List("x", "y").map { str =>
                    Choice.get(List(true, false)).map(b =>
                        if b then str.toUpperCase else str
                    )
                }
            val result = Choice.run(Kyo.collectAll(effects)).eval

            assert(result.contains(Chunk("X", "Y")))
            assert(result.contains(Chunk("X", "y")))
            assert(result.contains(Chunk("x", "Y")))
            assert(result.contains(Chunk("x", "y")))
            assert(result.size == 4)
        }

        "foldLeft" in {
            val result = Choice.run(
                Kyo.foldLeft(List(1, 1))(0) { (acc, _) =>
                    Choice.get(List(0, 1)).map(n => acc + n)
                }
            ).eval

            assert(result.contains(0))
            assert(result.contains(1))
            assert(result.contains(2))
            assert(result.size == 4)
        }
    }
end ChoiceTest
