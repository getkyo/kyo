package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

object ttt extends App:
    println((new VarBench).kyo2())

class VarBench extends BaseBench:

    @Benchmark
    def kyo1() =
        import kyo.*
        def loop(): Int < Var[Int] =
            Var.use[Int] { n =>
                if n <= 0 then n
                else Var.setAndThen(n - 1)(loop())
            }
        Var.runTuple(10000)(loop()).eval
    end kyo1

    @Benchmark
    def kyo2() =
        import kyo.kernel2.*
        def loop(): Int < Var[Int] =
            Var.get[Int].map { n =>
                if n <= 0 then n
                else Var.set(n - 1).andThen(loop())
            }
        Var.runTuple(10000)(loop()).eval
    end kyo2
end VarBench
