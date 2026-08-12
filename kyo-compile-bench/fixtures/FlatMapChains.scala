package kyobench

import kyo.*
import kyo.kernel.*

object FlatMapChains:

    def step(i: Int): Int < Any = i + 1

    def flow0(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow1(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow2(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow3(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow4(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow5(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow6(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow7(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow8(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow9(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow10(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

    def flow11(a0: Int): Int < Any =
        step(a0).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step).flatMap(step)

end FlatMapChains
