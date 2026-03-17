package kyo

import org.scalatest.freespec.AsyncFreeSpec
import zio.*

class KyoSchedulerZIORuntimeTest extends AsyncFreeSpec {

    "run" in {
        Unsafe.unsafe { implicit u =>
            val io     = ZIO.succeed(Thread.currentThread().getName()).fork.flatMap(_.join)
            val thread = KyoSchedulerZIORuntime.default.unsafe.run(io).getOrThrow()
            assert(thread.contains("kyo"))
        }
    }
}
