package kyo.scheduler

import kyo.Maybe

object IOTaskPlatformSpecific:

    // No handle: the runtime is single threaded, so the status word is never contended and the plain
    // read-modify-write selected by its absence is already atomic for every possible observer.
    inline def statusHandle = Maybe.empty[IOTask.StatusHandle]
end IOTaskPlatformSpecific
