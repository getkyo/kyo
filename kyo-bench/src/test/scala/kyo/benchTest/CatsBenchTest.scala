package kyo.benchTest

import kyo.bench.*

class CatsBenchTest extends BenchTest:

    def target                                 = Target.Cats
    def runFork[A](b: Bench.Fork[A]): A        = b.forkCats()
    def runSync[A](b: Bench.SyncAndFork[A]): A = b.syncCats()

end CatsBenchTest
