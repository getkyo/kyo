package kyo.experimental

import kyo.*

class UseTest extends kyo.Test:

    // A minimal service demonstrating Use and UseAsync
    trait TestInt[-S]:
        def int: Int < S

    private val getInt: Int < Use[TestInt]             = Use.use[TestInt](_.int)
    private val getIntAsync_1: Int < UseAsync[TestInt] = getInt
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

    "UseAsync: race with a pure value" in run {
        val svc = new TestInt[Async & Sync]:
            def int: Int < (Async & Sync) = 1

        val race: Int < UseAsync[TestInt] = Async.race(getIntAsync_1, 2)

        UseAsync.run(svc):
            race.map: winner =>
                assert(winner == 1 || winner == 2)
    }

    "UseAsync: use via UseAsync.use" in run {
        val svc = new TestInt[Async & Sync]:
            def int: Int < (Async & Sync) = 40
        UseAsync.run[TestInt, Async & Sync](svc) {
            getIntAsync_2.map(_ + 2).map(v => assert(v == 42))
        }
    }
end UseTest
