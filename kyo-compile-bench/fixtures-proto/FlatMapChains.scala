package kyobench

import kyo.kernel.proto.*

object FlatMapChains:

    def step(i: Int): Int < Any = i + 1

    def flow0(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow1(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow2(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow3(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow4(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow5(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow6(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow7(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow8(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow9(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow10(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

    def flow11(a0: Int): Int < Any =
        step(a0).map(step).map(step).map(step).map(step).map(step).map(step)

end FlatMapChains
