package kyo.bench

class CatsBenchTest extends BenchTest:

    def target                                 = Target.Cats
    def runFork[T](b: Bench.Fork[T]): T        = b.forkCats()
    def runSync[T](b: Bench.SyncAndFork[T]): T = b.syncCats()

end CatsBenchTest
