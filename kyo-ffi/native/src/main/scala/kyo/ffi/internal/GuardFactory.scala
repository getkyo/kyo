package kyo.ffi.internal

import kyo.Frame
import kyo.ffi.Ffi

/** Native factory for [[Ffi.Guard]]. Registers with [[GuardRegistry]]; leak detection via [[NativeLeakDetector]]. */
private[ffi] object GuardFactory:

    /** Open a new guard. The [[Frame]] is retained on the guard for future diagnostic use. */
    def open(frame: Frame): Ffi.Guard =
        GuardFactoryShared.register(new NativeGuard(frame))
end GuardFactory
