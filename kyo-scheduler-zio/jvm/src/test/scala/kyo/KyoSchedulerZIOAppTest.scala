package kyo

import zio.ZIO

object KyoSchedulerZIOAppDefaultTest extends KyoSchedulerZIOAppDefault {
    def run: ZIO[Any, String, String] = ZIO.succeed(Thread.currentThread().getName()).tap { thread =>
        ZIO.fail("Not using Kyo Scheduler").unless(thread.contains("kyo")) *> ZIO.logInfo(thread)
    }
}
