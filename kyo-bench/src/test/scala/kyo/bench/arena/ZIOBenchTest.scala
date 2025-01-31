package kyo.bench.arena

import kyo.bench.*

class ZIOBenchTest extends BenchTest:

    def target                                      = Target.ZIO
    def runFork[A](b: ArenaBench.Fork[A]): A        = b.forkZIO(null)
    def runSync[A](b: ArenaBench.SyncAndFork[A]): A = b.syncZIO(null)

end ZIOBenchTest
