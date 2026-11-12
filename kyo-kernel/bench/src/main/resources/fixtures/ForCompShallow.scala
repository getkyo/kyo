package kyobench

import kyo.*
import kyo.kernel.*

object ForCompShallow:

    def step(i: Int): Int < Any = i + 1

    def flow0(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow1(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow2(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow3(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow4(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow5(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow6(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow7(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow8(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow9(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow10(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

    def flow11(a0: Int): Int < Any =
        for
            a <- step(a0)
            b <- step(a)
            c <- step(a + b)
        yield a + b + c

end ForCompShallow
