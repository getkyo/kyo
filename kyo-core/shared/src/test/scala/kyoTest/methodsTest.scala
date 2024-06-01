package kyoTest

import kyo.*

class methodsTest extends KyoTest:

    def widen[A](v: A < Any) = v

    "toString JVM" in runJVM {
        assert(IOs(1).toString() == "Kyo(Tag[kyo.IOs],Command(()),Trace(methodsTest.scala:10,method=IOs,snippet=IOs(1)))")
        assert(
            IOs(1).map(_ + 1).toString() == "Kyo(Tag[kyo.IOs],Command(()),Trace(methodsTest.scala:12,method=map,snippet=map(_ + 1)))"
        )
    }

    "toString JS" in runJS {
        assert(IOs(1).toString() == "Kyo(Tag[kyo.IOs],Command(undefined),Trace(methodsTest.scala:17,method=IOs,snippet=IOs(1)))")
        assert(
            IOs(1).map(_ + 1).toString() == "Kyo(Tag[kyo.IOs],Command(undefined),Trace(methodsTest.scala:19,method=map,snippet=map(_ + 1)))"
        )
    }

    "pure" in {
        assert(IOs.run(IOs(1)).pure == 1)
        assertDoesNotCompile("IOs(1).pure")
        assert(widen(TypeMap(1, true)).pure.get[Boolean])
        assertDoesNotCompile("widen(IOs(42)).pure == 42")
    }

    "pure widened" in {
        assertDoesNotCompile("val _: Int < IOs = widen(IOs(42)).pure")
    }

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
        def lift[T](v: T): T < IOs                          = IOs(v)
        def add(v: Int < IOs)                               = v.map(_ + 1)
        def transform[T, U](v: T < IOs, f: T => U): U < IOs = v.map(f)
        val io: Int < IOs < IOs =
            lift(lift(1))
        "map + flatten" in {
            val a: Int < IOs < IOs =
                transform[Int < IOs, Int < IOs](io, add(_))
            val b: Int < IOs = a.flatten
            assert(IOs.run(b) == 2)
        }
        "run doesn't compile" in {
            assertDoesNotCompile("IOs.run(io)")
        }
    }
end methodsTest
