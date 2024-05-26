package kyo

import org.scalatest.freespec.AsyncFreeSpec
import zio.*

class KyoSchedulerZioRuntimeTest extends AsyncFreeSpec {

    "run" in {
        Unsafe.unsafe { implicit u =>
            val io     = ZIO.succeed(Thread.currentThread().getName()).fork.flatMap(_.join)
            val thread = KyoSchedulerZioRuntime.default.unsafe.run(io).getOrThrow()
            assert(thread.contains("kyo"))
        }
    }
}
