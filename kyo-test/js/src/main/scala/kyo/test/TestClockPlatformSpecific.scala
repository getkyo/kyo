package kyo.test

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.{Duration, Scheduler, Trace, UIO, Unsafe, ZIO}

private[test] trait TestClockPlatformSpecific { self: TestClock.Test =>

  def scheduler(implicit trace: Trace): UIO[Scheduler] =
    ZIO.runtime[Any].map { runtime =>
      new Scheduler {
        def schedule(runnable: Runnable, duration: Duration)(implicit unsafe: Unsafe): Scheduler.CancelToken = {
          val fiber =
            runtime.unsafe.fork(sleep(duration) *> ZIO.succeed(runnable.run()))
          () => runtime.unsafe.run(fiber.interruptAs(zio.FiberId.None)).getOrThrowFiberFailure().isInterrupted
        }
      }
    }
}
