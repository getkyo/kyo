package kyo.benchTest

import kyo.bench.*

class ZIOBenchTest extends BenchTest:

    def target                                 = Target.ZIO
    def runFork[T](b: Bench.Fork[T]): T        = b.forkZIO()
    def runSync[T](b: Bench.SyncAndFork[T]): T = b.syncZIO()

end ZIOBenchTest
