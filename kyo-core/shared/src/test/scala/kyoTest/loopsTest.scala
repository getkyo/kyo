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

        "accumulate results in a List" in {
            val result = Loops.transform((0, List.empty[Int])) { case (i, acc) =>
                if i < 5 then
                    Loops.continue((i + 1, i :: acc))
                else
                    Loops.done(acc.reverse)
            }
            assert(result.pure == List(0, 1, 2, 3, 4))
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
                Loops.transform(1, 1)((i, j) =>
                    if i + j < largeNumber then Loops.continue(i + 1, j + 1)
                    else Loops.done(i + j)
                ).pure == largeNumber
            )
        }

        "stack safety with nested loops" in {
            val outerLoopIterations = 1000
            val innerLoopIterations = 1000
            assert(
                Loops.transform(0, 0)((i, j) =>
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
                Loops.transform(0, 0)((i, j) =>
                    if i + j < iterations then
                        if i % 2 == 0 then Loops.continue(i + 1, j)
                        else Loops.continue(i, j + 1)
                    else Loops.done(i + j)
                ).pure == iterations
            )
        }

        "suspend with IOs at the beginning" in {
            val result = Loops.transform(1, 1)((i, j) =>
                IOs {
                    if i + j < 5 then Loops.continue(i + 1, j + 1) else Loops.done(i + j)
                }
            )
            assert(IOs.run(result).pure == 6)
        }

        "suspend with IOs in the middle" in {
            val result = Loops.transform(1, 1)((i, j) =>
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
            val result = Loops.transform(1, 1)((i, j) =>
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
                Loops.transform(1, 1, 1)((i, j, k) =>
                    if i + j + k < 5 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                ).pure == 6
            )
        }

        "with multiple iterations" in {
            assert(
                Loops.transform(1, 1, 1)((i, j, k) =>
                    if i + j + k < 10 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                ).pure == 12
            )
        }

        "with no iterations" in {
            assert(
                Loops.transform(5, 5, 5)((i, j, k) =>
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
                Loops.transform(1, 1, 1)((i, j, k) =>
                    if i + j + k < largeNumber then Loops.continue(i + 1, j + 1, k + 1)
                    else Loops.done(i + j + k)
                ).pure == largeNumber + 2
            )
        }

        "stack safety with interleaved iterations" in {
            val iterations = 100000
            assert(
                Loops.transform(0, 0, 0)((i, j, k) =>
                    if i + j + k < iterations then
                        if i % 3 == 0 then Loops.continue(i + 1, j, k)
                        else if i % 3 == 1 then Loops.continue(i, j + 1, k)
                        else Loops.continue(i, j, k + 1)
                    else Loops.done(i + j + k)
                ).pure == iterations
            )
        }

        "suspend with IOs at the beginning" in {
            val result = Loops.transform(1, 1, 1)((i, j, k) =>
                IOs {
                    if i + j + k < 5 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                }
            )
            assert(IOs.run(result).pure == 6)
        }

        "suspend with IOs in the middle" in {
            val result = Loops.transform(1, 1, 1)((i, j, k) =>
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
            val result = Loops.transform(1, 1, 1)((i, j, k) =>
                if i + j + k < 5 then
                    Loops.continue(i + 1, j + 1, k + 1)
                else
                    IOs(Loops.done(i + j + k))
            )
            assert(IOs.run(result).pure == 6)
        }
    }

    "indexed without input" - {
        "with a single iteration" in {
            assert(
                Loops.indexed(idx => if idx < 1 then Loops.continue(()) else Loops.done(idx)).pure == 1
            )
        }

        "with multiple iterations" in {
            assert(
                Loops.indexed(idx => if idx < 5 then Loops.continue(()) else Loops.done(idx)).pure == 5
            )
        }

        "with no iterations" in {
            assert(
                Loops.indexed(idx => if idx < 0 then Loops.continue(()) else Loops.done(idx)).pure == 0
            )
        }

        "output must be flat" in {
            assertDoesNotCompile("""
            def test[T](v: T) = Loops.indexed(idx => Loops.done(v))
            """)
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loops.indexed(idx => if idx < largeNumber then Loops.continue(()) else Loops.done(idx)).pure == largeNumber
            )
        }

        "suspend with IOs" in {
            val result = Loops.indexed(idx =>
                if idx < 5 then
                    IOs(Loops.continue(()))
                else
                    IOs(Loops.done(idx))
            )
            assert(IOs.run(result).pure == 5)
        }
    }

    "indexed" - {
        "with a single iteration" in {
            assert(
                Loops.indexed(1)((idx, i) => if idx < 5 then Loops.continue(i + 1) else Loops.done(i)).pure == 6
            )
        }

        "with multiple iterations" in {
            assert(
                Loops.indexed(1)((idx, i) => if idx < 10 then Loops.continue(i + 1) else Loops.done(i)).pure == 11
            )
        }

        "with no iterations" in {
            assert(
                Loops.indexed(1)((idx, i) => if idx < 0 then Loops.continue(i + 1) else Loops.done(i)).pure == 1
            )
        }

        "output must be flat" in {
            assertDoesNotCompile("""
        def test[T](v: T) = Loops.indexed(0)((idx, i) => Loops.done(v))
        """)
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loops.indexed(1)((idx, i) => if idx < largeNumber then Loops.continue(i + 1) else Loops.done(i)).pure == largeNumber + 1
            )
        }

        "suspend with IOs" in {
            val result = Loops.indexed(1)((idx, i) =>
                if idx < 5 then
                    IOs(Loops.continue(i + 1))
                else
                    IOs(Loops.done(i))
            )
            assert(IOs.run(result).pure == 6)
        }
    }

    "indexed2" - {
        "with a single iteration" in {
            assert(
                Loops.indexed(1, 1)((idx, i, j) => if idx < 5 then Loops.continue(i + 1, j + 1) else Loops.done(i + j)).pure == 12
            )
        }

        "with multiple iterations" in {
            assert(
                Loops.indexed(1, 1)((idx, i, j) => if idx < 10 then Loops.continue(i + 1, j + 1) else Loops.done(i + j)).pure == 22
            )
        }

        "with no iterations" in {
            assert(
                Loops.indexed(1, 1)((idx, i, j) => if idx < 0 then Loops.continue(i + 1, j + 1) else Loops.done(i + j)).pure == 2
            )
        }

        "output must be flat" in {
            assertDoesNotCompile("""
        def test[T](v: T) = Loops.indexed2(0, 0)((idx, i, j) => Loops.done(v))
        """)
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loops.indexed(1, 1)((idx, i, j) =>
                    if idx < largeNumber then Loops.continue(i + 1, j + 1) else Loops.done(i + j)
                ).pure == 2 * largeNumber + 2
            )
        }

        "suspend with IOs" in {
            val result = Loops.indexed(1, 1)((idx, i, j) =>
                if idx < 5 then
                    IOs(Loops.continue(i + 1, j + 1))
                else
                    IOs(Loops.done(i + j))
            )
            assert(IOs.run(result).pure == 12)
        }
    }

    "indexed3" - {
        "with a single iteration" in {
            assert(
                Loops.indexed(1, 1, 1)((idx, i, j, k) =>
                    if idx < 5 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                ).pure == 18
            )
        }

        "with multiple iterations" in {
            assert(
                Loops.indexed(1, 1, 1)((idx, i, j, k) =>
                    if idx < 10 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                ).pure == 33
            )
        }

        "with no iterations" in {
            assert(
                Loops.indexed(1, 1, 1)((idx, i, j, k) =>
                    if idx < 0 then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                ).pure == 3
            )
        }

        "output must be flat" in {
            assertDoesNotCompile("""
        def test[T](v: T) = Loops.indexed3(0, 0, 0)((idx, i, j, k) => Loops.done(v))
        """)
        }

        "stack safety" in {
            val largeNumber = 100000
            assert(
                Loops.indexed(1, 1, 1)((idx, i, j, k) =>
                    if idx < largeNumber then Loops.continue(i + 1, j + 1, k + 1) else Loops.done(i + j + k)
                ).pure == 3 * largeNumber + 3
            )
        }

        "suspend with IOs" in {
            val result = Loops.indexed(1, 1, 1)((idx, i, j, k) =>
                if idx < 5 then
                    IOs(Loops.continue(i + 1, j + 1, k + 1))
                else
                    IOs(Loops.done(i + j + k))
            )
            assert(IOs.run(result).pure == 18)
        }
    }

    "foreach" - {
        "with a single iteration" in {
            var counter = 0
            Loops.foreach {
                counter += 1
                if counter < 1 then Loops.continue(()) else Loops.done(())
            }.pure
            assert(counter == 1)
        }

        "with multiple iterations" in {
            var sum = 0
            Loops.foreach {
                sum += 1
                if sum < 10 then Loops.continue(()) else Loops.done(())
            }.pure
            assert(sum == 10)
        }

        "with no iterations" in {
            var entered = false
            Loops.foreach {
                entered = true
                Loops.done(())
            }.pure
            assert(entered)
        }

        "stack safety" in {
            var counter     = 0
            val largeNumber = 100000
            Loops.foreach {
                counter += 1
                if counter < largeNumber then Loops.continue(()) else Loops.done(())
            }.pure
            assert(counter == largeNumber)
        }

        "suspend with IOs" in {
            var effect = ""
            val result = Loops.foreach {
                effect += "A"
                if effect.length < 3 then
                    IOs(Loops.continue(()))
                else
                    IOs(Loops.done(()))
                end if
            }
            IOs.run(result).pure
            assert(effect == "AAA")
        }
    }

    "repeat" in {
        var count = 0
        val io    = IOs(count += 1)

        IOs.run(Loops.repeat(0)(io))
        assert(count == 0)

        count = 0
        IOs.run(Loops.repeat(1)(io))
        assert(count == 1)

        count = 0
        IOs.run(Loops.repeat(100)(io))
        assert(count == 100)

        count = 0
        IOs.run(Loops.repeat(10000)(io))
        assert(count == 10000)
    }

    "forever" in runJVM {
        for
            p <- Fibers.initPromise[Unit]
            f <- Fibers.init(Loops.forever(p.complete(()).unit))
            _ <- p.get
            _ <- f.interrupt
        yield succeed
    }
end loopsTest
