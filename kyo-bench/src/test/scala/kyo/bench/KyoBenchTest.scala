package kyo.bench

class KyoBenchTest extends BenchTest:

    def target                                 = Target.Kyo
    def runFork[T](b: Bench.Fork[T]): T        = b.forkKyo()
    def runSync[T](b: Bench.SyncAndFork[T]): T = b.syncKyo()

end KyoBenchTest
