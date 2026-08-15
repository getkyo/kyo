package kyobench

import kyo.kernel.proto.*

object ForCompShallow:

    def step(i: Int): Int < Any = i + 1

    def flow0(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow1(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow2(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow3(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow4(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow5(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow6(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow7(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow8(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow9(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow10(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

    def flow11(a0: Int): Int < Any =
        step(a0).map(a => step(a).map(b => step(a + b).map(c => a + b + c)))

end ForCompShallow
