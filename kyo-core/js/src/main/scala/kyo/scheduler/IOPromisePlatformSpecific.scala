package kyo.scheduler

import kyo.Maybe

object IOPromisePlatformSpecific:

    inline def stateHandle = Maybe.empty[IOPromise.StateHandle]
