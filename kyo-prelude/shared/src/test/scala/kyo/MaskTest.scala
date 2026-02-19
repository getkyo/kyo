package kyo

class MaskTest extends Test:

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
            Mask.use[S]: mask =>
                Var.run(2):
                    Var.get[Int].map: i =>
                        mask(intEffect).map: j =>
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

    "mask protects context effect" in {
        def genericFn[S](intEffect: Int < S)(using Tag[S]): Int < S =
            Mask.use[S]: mask =>
                Env.run(5):
                    Env.get[Int].map: i =>
                        mask(intEffect).map: j =>
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

    "mask protects complex effect" in {
        def genericFn[S](intEffect: Int < S)(using Tag[S]): Int < S =
            Mask.use[S]: mask =>
                val maskedEffect = mask(intEffect)
                Env.run(5):
                    Env.get[Int].map: i =>
                        maskedEffect.map: j =>
                            Var.run(2):
                                Var.get[Int].map: k =>
                                    maskedEffect.map: l =>
                                        i + j + k + l

        val eff = Env.get[Int].map(i => Var.get[Int].map(j => i + j))

        assert(Env.run(10)(Var.run(20)(genericFn(eff))).eval == 67)
    }

    "combined masked and unmasked effects" in {
        def genericFn[S](eff1: Int < S, eff2: Int < S)(using Tag[S]): Int < S =
            Mask.use[S]: mask =>
                val eff1Masked = mask(eff1)
                Env.run(5):
                    Env.get[Int].map: i =>   // 5
                        eff1Masked.map: j => // 10
                            Var.run(2):
                                Var.get[Int].map: k => // 2
                                    eff2.map: l =>     // 2
                                        i + j + k + l

        val eff1 = Env.get[Int]
        val eff2 = Var.get[Int]

        assert(Env.run(10)(Var.run(10)(genericFn(eff1, eff2))).eval == 19)
    }

    "cannot be used outside of scope" in {
        def genericFunction[S](using Tag[S]): Mask[S] < S =
            Mask.use[S](g => g)

        assertDoesNotCompile:
            """genericFunction[Any].map(mask => mask(42))"""
    }

end MaskTest
