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
end ChoiceTest
