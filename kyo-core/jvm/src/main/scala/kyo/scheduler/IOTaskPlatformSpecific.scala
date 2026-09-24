package kyo.scheduler

import java.lang.invoke.MethodHandles
import kyo.Present
import kyo.scheduler.IOTask.Status

object IOTaskPlatformSpecific:

    val statusHandle =
        val lookup    = MethodHandles.privateLookupIn(classOf[IOTask[?, ?, ?]], MethodHandles.lookup())
        val varHandle = lookup.findVarHandle(classOf[IOTask[?, ?, ?]], "status", classOf[Object])
        Present(
            new IOTask.StatusHandle:
                def compareAndSet(task: IOTask[?, ?, ?], curr: Status, next: Status): Boolean =
                    varHandle.compareAndSet(task, curr, next)
        )
    end statusHandle

end IOTaskPlatformSpecific
