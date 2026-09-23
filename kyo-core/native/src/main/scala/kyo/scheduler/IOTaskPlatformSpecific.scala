package kyo.scheduler

import kyo.Present
import kyo.scheduler.IOTask.Status
import scala.scalanative.libc.stdatomic.AtomicRef
import scala.scalanative.runtime.Intrinsics
import scala.scalanative.runtime.fromRawPtr

object IOTaskPlatformSpecific:

    val statusHandle =
        Present(
            new IOTask.StatusHandle:
                def compareAndSet(task: IOTask[?, ?, ?], curr: Status, next: Status): Boolean =
                    val ref = AtomicRef(fromRawPtr(Intrinsics.classFieldRawPtr(task, "status")))
                    ref.compareExchangeStrong(curr.asInstanceOf[AnyRef], next.asInstanceOf[AnyRef])
        )

end IOTaskPlatformSpecific
