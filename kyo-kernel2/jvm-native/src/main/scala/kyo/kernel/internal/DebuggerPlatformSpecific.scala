package kyo.kernel.internal

import scala.annotation.static

private[kyo] class DebuggerPlatformSpecific

private[kyo] object DebuggerPlatformSpecific:

    // `@static` here, plain on js-wasm: a static field initializer runs at script evaluation on
    // Scala.js, in file order, so it could capture undefined for `Noop`
    @static private[internal] var current: Debugger = Debugger.Noop
end DebuggerPlatformSpecific
