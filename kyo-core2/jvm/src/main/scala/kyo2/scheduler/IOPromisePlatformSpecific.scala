package kyo2.scheduler

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

abstract class IOPromisePlatformSpecific:

    private[scheduler] val stateHandle: VarHandle =
        val lookup = MethodHandles.privateLookupIn(classOf[IOPromise[?, ?]], MethodHandles.lookup())
        lookup.findVarHandle(classOf[IOPromise[?, ?]], "state", classOf[Object])
    end stateHandle

end IOPromisePlatformSpecific
