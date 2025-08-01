package kyo

class GuardTest extends Test:

    "unprotected arrow effect" in {
        def genericFn[S](intEffect: Int < S)(using Tag[S]): Int < S =
            Var.run(2):
                Var.get[Int].map: i =>
                    intEffect.map: j =>
                        i + j

        val eff = Var.update[Int](_ + 1)

        assert(Var.run(10)(genericFn(eff)).eval == 5)
    }

    "protects arrow effect" in {
        def genericFn[S](intEffect: Int < S)(using Tag[S]): Int < S =
            Guard.use[S]: guard =>
                Var.run(2):
                    Var.get[Int].map: i =>
                        guard(intEffect).map: j =>
                            i + j

        val eff = Var.update[Int](_ + 1)

        assert(Var.run(10)(genericFn(eff)).eval == 13)
    }

    "unprotected context effect" in {
        def genericFn[S](intEffect: Int < S)(using Tag[S]): Int < S =
            Env.run(5):
                Env.get[Int].map: i =>
                    intEffect.map: j =>
                        i + j

        val eff = Env.get[Int]

        assert(Env.run(10)(genericFn(eff)).eval == 10)
    }

    "guard protects context effect" in {
        def genericFn[S](intEffect: Int < S)(using Tag[S]): Int < S =
            Guard.use[S]: guard =>
                Env.run(5):
                    Env.get[Int].map: i =>
                        guard(intEffect).map: j =>
                            i + j

        val eff = Env.get[Int]

        assert(Env.run(10)(genericFn(eff)).eval == 15)
    }

    "unprotected complex effect" in {
        def genericFn[S](intEffect: Int < S)(using Tag[S]): Int < S =
            Env.run(5):
                Env.get[Int].map: i =>  // 5
                    intEffect.map: j => // 25
                        Var.run(2):
                            Var.get[Int].map: k =>  // 2
                                intEffect.map: l => // 7
                                    i + j + k + l

        val eff = Env.get[Int].map(i => Var.get[Int].map(j => i + j))

        assert(Env.run(10)(Var.run(20)(genericFn(eff))).eval == 39)
    }

    "guard protects complex effect" in {
        def genericFn[S](intEffect: Int < S)(using Tag[S]): Int < S =
            Guard.use[S]: guard =>
                val guardedEffect = guard(intEffect)
                Env.run(5):
                    Env.get[Int].map: i =>
                        guardedEffect.map: j =>
                            Var.run(2):
                                Var.get[Int].map: k =>
                                    guardedEffect.map: l =>
                                        i + j + k + l

        val eff = Env.get[Int].map(i => Var.get[Int].map(j => i + j))

        assert(Env.run(10)(Var.run(20)(genericFn(eff))).eval == 67)
    }

    "cannot be used outside of scope" in {
        def genericFunction[S](using Tag[S]): Guard[S] < S =
            Guard.use[S](g => g)

        assertDoesNotCompile:
            """genericFunction[Any].map(guard => guard(42))"""
    }

end GuardTest
