package kyoTest
import kyo.*

class choicesTest extends KyoPureTest:
    "eval with a single choice" in {
        assert(
            Choices.run(Choices.eval(Seq(1))(i => (i + 1))).pure == Seq(2)
        )
    }

    "eval with multiple choices" in {
        assert(
            Choices.run(Choices.eval(Seq(1, 2, 3))(i => (i + 1))).pure == Seq(2, 3, 4)
        )
    }

    "nested eval" in {
        assert(
            Choices.run(Choices.eval(Seq(1, 2, 3))(i =>
                Choices.get(Seq(i * 10, i * 100))
            )).pure == Seq(10, 100, 20, 200, 30, 300)
        )
    }

    "drop" in {
        assert(
            Choices.run(Choices.eval(Seq(1, 2, 3))(i =>
                if i < 2 then Choices.drop else Choices.get(Seq(i * 10, i * 100))
            )).pure == Seq(20, 200, 30, 300)
        )
    }

    "filter" in {
        assert(
            Choices.run(Choices.eval(Seq(1, 2, 3))(i =>
                Choices.filter(i >= 2).map(_ => Choices.get(Seq(i * 10, i * 100)))
            )).pure == Seq(20, 200, 30, 300)
        )
    }

    "empty choices" in {
        assert(
            Choices.run(Choices.eval(Seq.empty[Int])(_ => 42)).pure == Seq.empty[Int]
        )
    }

    "nested drop" in {
        assert(
            Choices.run(
                Choices.eval(Seq(1, 2, 3))(i =>
                    Choices.eval(Seq(i * 10, i * 100))(j =>
                        if j > 100 then Choices.drop else j
                    )
                )
            ).pure == Seq(10, 100, 20, 30)
        )
    }

    "nested filter" in {
        assert(
            Choices.run(
                Choices.eval(Seq(1, 2, 3))(i =>
                    Choices.filter(i % 2 == 0).map(_ =>
                        Choices.eval(Seq(i * 10, i * 100))(j =>
                            Choices.filter(j < 300).map(_ => j)
                        )
                    )
                )
            ).pure == Seq(20, 200)
        )
    }

    "large number of choices" in {
        val largeChoices = Seq.range(0, 100000)
        try
            assert(
                Choices.run(Choices.get(largeChoices)).pure == largeChoices
            )
        catch
            case ex: StackOverflowError => fail()
        end try
    }

    "large number of suspensions" in pendingUntilFixed {
        // https://github.com/getkyo/kyo/issues/208
        var v = Choices.get(Seq(1))
        for _ <- 0 until 100000 do
            v = v.map(_ => Choices.get(Seq(1)))
        try
            assert(
                Choices.run(v).pure == Seq(1)
            )
        catch
            case ex: StackOverflowError => fail()
        end try
        ()
    }
end choicesTest
