package kyobench

import kyo.*
import kyo.kernel.*

object NestedMaps:

    def step(i: Int): Int < Any = i + 1

    def flow0(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow1(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow2(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow3(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow4(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow5(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow6(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow7(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow8(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow9(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow10(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

    def flow11(a0: Int): Int < Any =
        step(a0).map(a =>
            step(a).map(b =>
                step(a + b).map(c =>
                    step(b + c).map(d =>
                        step(c + d).map(e =>
                            step(d + e).map(f => a + b + c + d + e + f))))))

end NestedMaps
