package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

object ttt extends App:
    println((new VarBench).kyo3())

class VarBench extends BaseBench:

    // @Benchmark
    // def kyo1() =
    //     import kyo.*
    //     def loop(): Int < Var[Int] =
    //         Var.use[Int] { n =>
    //             if n <= 0 then n
    //             else Var.setAndThen(n - 1)(loop())
    //         }
    //     Var.runTuple(10000)(loop()).eval
    // end kyo1

    @Benchmark
    def kyo3() =
        import kyo.kernel3.*
        def loop(): Int < Var[Int] =
            Var.use[Int] { n =>
                if n <= 0 then n
                else Var.setWith(n - 1)(_ => loop())
            }
        Var.runTuple(10000)(loop()).eval
    end kyo3
end VarBench
