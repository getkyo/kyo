package kyo.benchTest

import kyo.bench.*

class ZIOBenchTest extends BenchTest:

    def target                                 = Target.ZIO
    def runFork[A](b: Bench.Fork[A]): A        = b.forkZIO(null)
    def runSync[A](b: Bench.SyncAndFork[A]): A = b.syncZIO(null)

end ZIOBenchTest
