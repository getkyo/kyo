package kyo.internal

import kyo.*
import org.scalatest.compatible.Assertion

class ZippableTest extends Test:
    "compile" - {
        def check[A, B](using zip: Zippable[A, B])[Expected](using (zip.Out =:= Expected)): Assertion = succeed

        def on[A, B, C, D]: Assertion =
            check[(A, B), (C, D)][(A, B, C, D)]
            check[Unit, A][A]
            check[Unit, B][B]
            check[(A, B), Unit][(A, B)]
            check[(A, B), C][(A, B, C)]
        end on

    }

    "zip" in {
        assert(Zippable.zip(1, 2) == (1, 2))
        assert(Zippable.zip(1, ()) == 1)
        assert(Zippable.zip((), 2) == 2)
        assert(Zippable.zip((1, 2), 3) == (1, 2, 3))
        assert(Zippable.zip((1, 2), ()) == (1, 2))
        assert(Zippable.zip((), (3, 4)) == (3, 4))
        assert(Zippable.zip((1, 2), (3, 4)) == (1, 2, 3, 4))
    }

    "prepend" in {
        assert(Zippable.zip(2, (3, 4)) == (2, (3, 4)))
    }
end ZippableTest
