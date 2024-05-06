package kyo

import java.util.concurrent.CompletionStage
import kyo.scheduler.IOPromise
import kyo.scheduler.IOTask

trait fibersPlatformSpecific:
    def fromCompletionStage[T: Flat](cs: CompletionStage[T]): T < Fibers =
        Fibers.get(fromCompletionStageFiber(cs))

    def fromCompletionStageFiber[T: Flat](cs: CompletionStage[T]): Fiber[T] < IOs =
        Locals.save { st =>
            IOs {
                val p = new IOPromise[T]()
                cs.whenComplete { (success, error) =>
                    val io = IOs {
                        if error == null then p.complete(success)
                        else p.complete(IOs.fail(error))
                    }
                    IOTask(io, st)
                    ()
                }
                Promise(p)
            }
        }
end fibersPlatformSpecific
