package kyo.scheduler

import kyo.Maybe

object IOTaskPlatformSpecific:

    // No handle: the runtime is single threaded, so the status word is never contended and the plain
    // read-modify-write the absence selects is already atomic with respect to everything that can observe it
    inline def statusHandle = Maybe.empty[IOTask.StatusHandle]
