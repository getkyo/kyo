package kyo.kernel.internal

private[kyo] class DebuggerPlatformSpecific

private[kyo] object DebuggerPlatformSpecific:

    // a plain module var where jvm-native uses `@static`: a static field initializer runs at script
    // evaluation on Scala.js, in file order, so it could capture undefined for `Noop`; the module
    // read is initializer-order safe
    private[internal] var current: Debugger = Debugger.Noop
end DebuggerPlatformSpecific
