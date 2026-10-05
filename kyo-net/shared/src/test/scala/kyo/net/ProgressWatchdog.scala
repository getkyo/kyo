package kyo.net

import kyo.*

/** A watch over a computation that fails it when its progress stops, never because it is slow.
  *
  * The computation reports each unit of work it completes through [[ProgressWatchdog.Progress.tick]]. The watch samples that count once
  * per `window` and fails the computation with [[ProgressWatchdog.Stalled]] once the count has stayed the same for `windows` consecutive
  * windows. A window whose sampling ran late, past twice its length, says the process itself was paused or starved: it proves nothing
  * about the computation and restarts the count. A computation that keeps ticking runs to completion however long it takes, which is
  * what a wall-clock limit cannot tell apart from a wedge on a loaded or throttled host.
  *
  * The failure carries `Diagnostics.dumpAll()` taken at that moment, the poll and reap state of every live driver, so a stall is diagnosable
  * from its first occurrence.
  */
object ProgressWatchdog:

    /** The count of work units a watched computation has completed. */
    final class Progress private[ProgressWatchdog] (count: AtomicLong):
        def tick(using Frame): Unit < Sync = count.incrementAndGet.unit

    /** The computation completed no work for `windows` windows of `window`. */
    final case class Stalled(windows: Int, window: Duration, diagnostics: String) derives CanEqual:
        def message: String =
            s"no progress for $windows windows of ${window.show} with the sampler on time; live components:\n$diagnostics"

    def run[A](window: Duration, windows: Int)(body: Progress => A < (Async & Abort[NetException | Closed]))(using
        Frame
    ): A < (Async & Abort[NetException | Closed | Stalled]) =
        AtomicLong.init.map { count =>
            Async.raceFirst(body(Progress(count)), watch(count, window, windows))
        }

    private def watch(count: AtomicLong, window: Duration, windows: Int)(using Frame): Nothing < (Async & Abort[Stalled]) =
        Loop(-1L, 0) { (last, frozen) =>
            Clock.nowMonotonic.map { before =>
                Async.sleep(window).andThen(Clock.nowMonotonic).map { after =>
                    count.get.map { now =>
                        if after.minusOrZero(before) > window * 2 then Loop.continue(now, 0)
                        else if now != last then Loop.continue(now, 0)
                        else if frozen + 1 >= windows then
                            Abort.fail(Stalled(windows, window, kyo.internal.Diagnostics.dumpAll()))
                        else Loop.continue(now, frozen + 1)
                    }
                }
            }
        }
end ProgressWatchdog
