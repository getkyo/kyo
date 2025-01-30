package kyo.bench.arena

import kyo.bench.*

class CatsBenchTest extends BenchTest:

    def target                                      = Target.Cats
    def runFork[A](b: ArenaBench.Fork[A]): A        = b.forkCats(null)
    def runSync[A](b: ArenaBench.SyncAndFork[A]): A = b.syncCats(null)

end CatsBenchTest
