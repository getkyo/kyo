package kyo.benchTest

import kyo.bench.*

class ZIOBenchTest extends BenchTest:

    def target                                 = Target.ZIO
    def runFork[A](b: Bench.Fork[A]): A        = b.forkZIO()
    def runSync[A](b: Bench.SyncAndFork[A]): A = b.syncZIO()

end ZIOBenchTest
