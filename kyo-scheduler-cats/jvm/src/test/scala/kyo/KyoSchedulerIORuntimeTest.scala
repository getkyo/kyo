package kyo

import cats.effect.*
import org.scalatest.freespec.AsyncFreeSpec

class KyoSchedulerIORuntimeTest extends AsyncFreeSpec {

    import KyoSchedulerIORuntime.global

    "compute" in {
        val thread = IO.cede.map(_ => Thread.currentThread().getName()).unsafeRunSync()
        assert(thread.contains("kyo"))
    }

    "blocking" in {
        val thread = IO.cede.flatMap(_ => IO.blocking(Thread.currentThread().getName())).unsafeRunSync()
        assert(thread.contains("kyo"))
    }
}
