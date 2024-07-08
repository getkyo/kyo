package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

class VarBench:

    @Benchmark
    def noTailMap() =
        import kyo.*

        def program: Int < Vars[Int] =
            Vars.get[Int].flatMap { n =>
                if n <= 0
                then n
                else Vars.set(n - 1).andThen(program)
            }

        Vars.run(100000)(program).pure
    end noTailMap

    @Benchmark
    def withTailMap() =
        import kyo.*

        def program: Int < Vars[Int] =
            Vars.get[Int].flatMap { n =>
                if n <= 0
                then n
                else Vars.set(n - 1).andThen(program).map(x => x)
            }

        Vars.run(100000)(program).pure
    end withTailMap

    
end VarBench
