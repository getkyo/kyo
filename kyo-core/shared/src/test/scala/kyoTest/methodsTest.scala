package kyoTest

import kyo.*

class methodsTest extends KyoTest:

    "map" in {
        assert(IOs.run(IOs(1).map(_ + 1)) == 2)
        assert(IOs.run(IOs(1).map(v => IOs(v + 1))) == 2)
    }

    "flatMap" in {
        assert(IOs.run(IOs(1).flatMap(_ + 1)) == 2)
        assert(IOs.run(IOs(1).flatMap(v => IOs(v + 1))) == 2)
    }

    "unit" in {
        IOs.run(IOs(1).unit)
        succeed
    }

    "flatten" in {
        def test[T](v: T)      = IOs(v)
        val a: Int < IOs < IOs = test(IOs(1))
        val b: Int < IOs       = a.flatten
        assert(IOs.run(b) == 1)
    }

    "andThen" in {
        assert(IOs.run(IOs(()).andThen(2)) == 2)
    }

    "repeat" in {
        var c = 0
        IOs.run(IOs(c += 1).repeat(3))
        assert(c == 3)
    }

    "zip" in {
        assert(IOs.run(zip(IOs(1), IOs(2))) == (1, 2))
        assert(IOs.run(zip(IOs(1), IOs(2), IOs(3))) == (1, 2, 3))
        assert(IOs.run(zip(IOs(1), IOs(2), IOs(3), IOs(4))) == (1, 2, 3, 4))
    }

    "nested" - {
        val io: Int < IOs < IOs =
            IOs(IOs(1))
        "map + flatten" in {
            val n: Int < IOs = io.map(_.map(_ + 1)).flatten
            assert(IOs.run(n) == 2)
        }
        "run doesn't compile" in {
            assertDoesNotCompile("IOs.run(io)")
        }
    }
end methodsTest
