package kyo.test

import kyo.*

private[test] trait TestClockPlatformSpecific:
    self: TestClock.Test =>

    def scheduler(implicit trace: Trace): Scheduler < Async =
        Kyo.runtime[Any].map { runtime =>
            new Scheduler:
                def schedule(runnable: Runnable, duration: Duration)(implicit unsafe: Unsafe): Scheduler.CancelToken =
                    val fiber =
                        runtime.unsafe.fork(Kyo.sleep(duration) *> Kyo.pure(runnable.run()))
                    () => runtime.unsafe.run(fiber.interrupt).getOrThrowFiberFailure().isInterrupted
                end schedule
        }
end TestClockPlatformSpecific
