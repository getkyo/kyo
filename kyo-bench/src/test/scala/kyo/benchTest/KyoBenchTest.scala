package kyo.benchTest

import kyo.bench.*

class KyoBenchTest extends BenchTest:

    def target                                 = Target.Kyo
    def runFork[A](b: Bench.Fork[A]): A        = b.forkKyo(null)
    def runSync[A](b: Bench.SyncAndFork[A]): A = b.syncKyo(null)

end KyoBenchTest
