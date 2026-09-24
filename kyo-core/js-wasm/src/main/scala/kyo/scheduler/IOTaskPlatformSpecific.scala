package kyo.scheduler

import kyo.Maybe

object IOTaskPlatformSpecific:

    // No handle: the runtime is single threaded, so the status word is never contended and the plain
    // read-modify-write is already atomic.
    inline def statusHandle = Maybe.empty[IOTask.StatusHandle]
end IOTaskPlatformSpecific
