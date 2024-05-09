package kyo.benchTest

import kyo.bench.*

class ZioBenchTest extends BenchTest:

    def target                                 = Target.Zio
    def runFork[T](b: Bench.Fork[T]): T        = b.forkZio()
    def runSync[T](b: Bench.SyncAndFork[T]): T = b.syncZio()

end ZioBenchTest
