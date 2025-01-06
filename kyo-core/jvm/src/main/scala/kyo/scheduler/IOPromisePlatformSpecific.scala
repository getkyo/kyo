package kyo.scheduler

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import kyo.Present
import kyo.scheduler.IOPromise.State

object IOPromisePlatformSpecific:

    val stateHandle =
        val lookup    = MethodHandles.privateLookupIn(classOf[IOPromise[?, ?]], MethodHandles.lookup())
        val varHandle = lookup.findVarHandle(classOf[IOPromise[?, ?]], "state", classOf[Object])
        Present(
            new IOPromise.StateHandle:
                def compareAndSet[E, A](promise: IOPromise[E, A], curr: State[E, A], next: State[E, A]): Boolean =
                    varHandle.compareAndSet(promise, curr, next)
        )
    end stateHandle

end IOPromisePlatformSpecific
