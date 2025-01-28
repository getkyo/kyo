package kyo.bench.arena

import kyo.bench.*

class KyoBenchTest extends BenchTest:

    def target                                      = Target.Kyo
    def runFork[A](b: ArenaBench.Fork[A]): A        = b.forkKyo(null)
    def runSync[A](b: ArenaBench.SyncAndFork[A]): A = b.syncKyo(null)

end KyoBenchTest
