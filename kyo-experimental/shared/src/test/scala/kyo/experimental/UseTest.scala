package kyo.experimental

import kyo.*

class UseTest extends kyo.Test:

    trait TestInt[-S]:
        def int: Int < S

    private val getInt: Int < Use[TestInt]             = Use.use[TestInt](_.int)
    private val getIntAsync_1                          = getInt.toUseAsync
    private val getIntAsync_2: Int < UseAsync[TestInt] = UseAsync.use[TestInt](_.int)

    val svc: TestInt[Sync] = new TestInt[Sync]:
        def int: Int < Sync = 42

    "basic use with Use.run" in run {
        Use.run(svc):
            getInt.map(_ + 1).map(v => assert(v == 43))
    }

    "basic get with Use.get" in run {
        Use.run(svc):
            Use.get[TestInt].map(_.int).map(v => assert(v == 42))
    }

    "same instance returned by consecutive Use.get" in run {
        Use.run(svc):
            for
                a <- Use.get[TestInt]
                b <- Use.get[TestInt]
            yield assert((a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]))
    }

    "Use: passing subclass to a super service" in run {
        trait Super[-S]:
            def i: Int < S

        class Sub extends Super[Sync]:
            def i: Int < Sync = 99

        val sub = new Sub
        Use.run(sub):
            Use.use[Super](_.i).map(v => assert(v == 99))
    }

    // Two-service example inspired by EnvTest's pure services
    trait Svc1[-S]:
        def f(i: Int): Int < S

    trait Svc2[-S]:
        def g(i: Int): Int < S

    "Use: combine two services with &&" in run {
        val combined: Svc1[Sync] & Svc2[Sync] = new Svc1[Sync] with Svc2[Sync]:
            def f(i: Int): Int < Sync = i * 2
            def g(i: Int): Int < Sync = i * 3

        val program: Int < (Use[Svc1 && Svc2]) =
            Use.use: r =>
                r.f(1).map: a =>
                    r.g(2).map: b =>
                        a + b

        Use.run[Svc1 && Svc2, Sync](combined):
            program.map(v => assert(v == 1 * 2 + 2 * 3))
    }

    "UseAsync: race with a pure value" in run {
        val svc = new TestInt[Async & Sync]:
            def int: Int < (Async & Sync) = 1

        val race: Int < UseAsync[TestInt] = Async.race(getIntAsync_1, 2)

        UseAsync.run(svc):
            race.map: winner =>
                assert(winner == 1 || winner == 2)
    }

    "UseAsync: use via UseAsync.use" in run {
        val svc = new TestInt[Async]:
            def int: Int < Async = 40

        UseAsync.run(svc) {
            getIntAsync_2.map(_ + 2).map(v => assert(v == 42))
        }
    }

    "UseAsync: service method can suspend with Async.sleep" in run {
        val svc = new TestInt[Async]:
            def int: Int < Async = Async.sleep(5.millis).andThen(42)

        UseAsync.run(svc):
            Async.zip(getIntAsync_1, getIntAsync_2).map: (a, b) =>
                assert(a + b == 42 * 2)
    }

    "Should not compile" in {
        val svc = new TestInt[Var[Int]]:
            def int: Int < Var[Int] = Var.update(_ + 1)

        typeCheckFailure("UseAsync.run(svc)(getIntAsync_2)")(
            "UseAsync.run requires the service effect S to be limited to Async, Abort[Error & ...], Env[Resource & ...]"
        )
    }
end UseTest
