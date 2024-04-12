package kyoTest

import kyo.*

class loopsTest extends KyoTest:

    "transform" - {
        "with a single iteration" in {
            assert(
                Loops.transform(1)(i => if i < 5 then Loops.continue(i + 1) else Loops.done(i)).pure == 5
            )
        }

        "with multiple iterations" in {
            assert(
                Loops.transform(1)(i => if i < 5 then Loops.continue(i + 1) else Loops.done(i)).pure == 5
            )
        }

        "with no iterations" in {
            assert(
                Loops.transform(5)(i => if i < 5 then Loops.continue(i + 1) else Loops.done(i)).pure == 5
            )
        }

        "output must be flat" in {
            assertDoesNotCompile("""
            def test[T](v: T) = Loops.transform(0)(i => Loops.done(v))
          """)
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loops.transform(1)(i => if i < largeNumber then Loops.continue(i + 1) else Loops.done(i)).pure == largeNumber
            )
        }

        "stack safety with nested loops" in {
            val outerLoopIterations = 1000
            val innerLoopIterations = 1000

            assert(
                Loops.transform(0)(i =>
                    if i < outerLoopIterations then
                        Loops.continue(Loops.transform(0)(j =>
                            if j < innerLoopIterations then Loops.continue(j + 1) else Loops.done(j)
                        ).pure)
                    else
                        Loops.done(i)
                ).pure == outerLoopIterations
            )
        }

        "stack safety with multiple levels of nested loops" in {
            val level1Iterations = 100
            val level2Iterations = 100
            val level3Iterations = 100

            assert(
                Loops.transform(0)(i =>
                    if i < level1Iterations then
                        Loops.continue(
                            Loops.transform(0)(j =>
                                if j < level2Iterations then
                                    Loops.continue(
                                        Loops.transform(0)(k => if k < level3Iterations then Loops.continue(k + 1) else Loops.done(k)).pure
                                    )
                                else
                                    Loops.done(j)
                            ).pure
                        )
                    else
                        Loops.done(i)
                ).pure == level1Iterations
            )
        }

        "suspend with IOs at the beginning" in {
            val result = Loops.transform(1)(i =>
                IOs {
                    if i < 5 then Loops.continue(i + 1) else Loops.done(i)
                }
            )
            assert(IOs.run(result).pure == 5)
        }

        "suspend with IOs in the middle" in {
            val result = Loops.transform(1)(i =>
                if i < 3 then
                    IOs(Loops.continue(i + 1))
                else if i < 5 then
                    Loops.continue(i + 1)
                else
                    Loops.done(i)
            )
            assert(IOs.run(result).pure == 5)
        }

        "suspend with IOs at the end" in {
            val result = Loops.transform(1)(i =>
                if i < 5 then
                    Loops.continue(i + 1)
                else
                    IOs(Loops.done(i))
            )
            assert(IOs.run(result).pure == 5)
        }
    }

    "transform2" - {
        "with a single iteration" in {
            assert(
                Loops.transform(1, 1)((i, j) => if i + j < 5 then Loops.continue(i + 1, j + 1) else Loops.done(i + j)).pure == 6
            )
        }

        "with multiple iterations" in {
            assert(
                Loops.transform(1, 1)((i, j) => if i + j < 10 then Loops.continue(i + 1, j + 1) else Loops.done(i + j)).pure == 10
            )
        }

        "with no iterations" in {
            assert(
                Loops.transform(5, 5)((i, j) => if i + j < 10 then Loops.continue(i + 1, j + 1) else Loops.done(i + j)).pure == 10
            )
        }

        "output must be flat" in {
            assertDoesNotCompile("""
            def test[T](v: T) = Loops.transform2(0, 0)((i, j) => Loops.done(v))
          """)
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loops.transform2(1, 1)((i, j) =>
                    if i + j < largeNumber then Loops.continue(i + 1, j + 1)
                    else Loops.done(i + j)
                ).pure == largeNumber
            )
        }

        "stack safety with nested loops" in {
            val outerLoopIterations = 1000
            val innerLoopIterations = 1000
            assert(
                Loops.transform2(0, 0)((i, j) =>
                    if i < outerLoopIterations then
                        Loops.continue(
                            i + 1,
                            Loops.transform(0)(k =>
                                if k < innerLoopIterations then Loops.continue(k + 1)
                                else Loops.done(k)
                            ).pure
                        )
                    else Loops.done(j)
                ).pure == innerLoopIterations
            )
        }

        "stack safety with interleaved iterations" in {
            val iterations = 100000
            assert(
                Loops.transform2(0, 0)((i, j) =>
                    if i + j < iterations then
                        if i % 2 == 0 then Loops.continue(i + 1, j)
                        else Loops.continue(i, j + 1)
                    else Loops.done(i + j)
                ).pure == iterations
            )
        }

        "suspend with IOs at the beginning" in {
            val result = Loops.transform2(1, 1)((i, j) =>
                IOs {
                    if i + j < 5 then Loops.continue(i + 1, j + 1) else Loops.done(i + j)
                }
            )
            assert(IOs.run(result).pure == 6)
        }

        "suspend with IOs in the middle" in {
            val result = Loops.transform2(1, 1)((i, j) =>
                if i + j < 3 then
                    IOs(Loops.continue(i + 1, j + 1))
                else if i + j < 5 then
                    Loops.continue(i + 1, j + 1)
                else
                    Loops.done(i + j)
            )
            assert(IOs.run(result).pure == 6)
        }

        "suspend with IOs at the end" in {
            val result = Loops.transform2(1, 1)((i, j) =>
                if i + j < 5 then
                    Loops.continue(i + 1, j + 1)
                else
                    IOs(Loops.done(i + j))
            )
            assert(IOs.run(result).pure == 6)
        }
    }

    "transform3" - {
        "with a single iteration" in {
            assert(
                Loops.transform3(1, 1, 1)((i, j, k) =>
                    if i + j + k < 5 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                ).pure == 6
            )
        }

        "with multiple iterations" in {
            assert(
                Loops.transform3(1, 1, 1)((i, j, k) =>
                    if i + j + k < 10 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                ).pure == 12
            )
        }

        "with no iterations" in {
            assert(
                Loops.transform3(5, 5, 5)((i, j, k) =>
                    if i + j + k < 10 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                ).pure == 15
            )
        }

        "output must be flat" in {
            assertDoesNotCompile("""
            def test[T](v: T) = Loops.transform3(0, 0, 0)((i, j, j) => Loops.done(v))
          """)
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loops.transform3(1, 1, 1)((i, j, k) =>
                    if i + j + k < largeNumber then Loops.continue(i + 1, j + 1, k + 1)
                    else Loops.done(i + j + k)
                ).pure == largeNumber + 2
            )
        }

        "stack safety with interleaved iterations" in {
            val iterations = 100000
            assert(
                Loops.transform3(0, 0, 0)((i, j, k) =>
                    if i + j + k < iterations then
                        if i % 3 == 0 then Loops.continue(i + 1, j, k)
                        else if i % 3 == 1 then Loops.continue(i, j + 1, k)
                        else Loops.continue(i, j, k + 1)
                    else Loops.done(i + j + k)
                ).pure == iterations
            )
        }

        "suspend with IOs at the beginning" in {
            val result = Loops.transform3(1, 1, 1)((i, j, k) =>
                IOs {
                    if i + j + k < 5 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                }
            )
            assert(IOs.run(result).pure == 6)
        }

        "suspend with IOs in the middle" in {
            val result = Loops.transform3(1, 1, 1)((i, j, k) =>
                if i + j + k < 3 then
                    IOs(Loops.continue(i + 1, j + 1, k + 1))
                else if i + j + k < 5 then
                    Loops.continue(i + 1, j + 1, k + 1)
                else
                    Loops.done(i + j + k)
            )
            assert(IOs.run(result).pure == 6)
        }

        "suspend with IOs at the end" in {
            val result = Loops.transform3(1, 1, 1)((i, j, k) =>
                if i + j + k < 5 then
                    Loops.continue(i + 1, j + 1, k + 1)
                else
                    IOs(Loops.done(i + j + k))
            )
            assert(IOs.run(result).pure == 6)
        }
    }
end loopsTest
