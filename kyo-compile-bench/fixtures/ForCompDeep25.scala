package kyobench

import kyo.*
import kyo.kernel.*

object ForCompDeep25:

    def step(i: Int): Int < Any = i + 1

    def flow(a0: Int): Int < Any =
        for
            a1  <- step(a0)
            a2  <- step(a1)
            a3  <- step(a2)
            a4  <- step(a3)
            a5  <- step(a4)
            a6  <- step(a5)
            a7  <- step(a6)
            a8  <- step(a7)
            a9  <- step(a8)
            a10 <- step(a9)
            a11 <- step(a10)
            a12 <- step(a11)
            a13 <- step(a12)
            a14 <- step(a13)
            a15 <- step(a14)
            a16 <- step(a15)
            a17 <- step(a16)
            a18 <- step(a17)
            a19 <- step(a18)
            a20 <- step(a19)
            a21 <- step(a20)
            a22 <- step(a21)
            a23 <- step(a22)
            a24 <- step(a23)
            a25 <- step(a24)
        yield a25

end ForCompDeep25
