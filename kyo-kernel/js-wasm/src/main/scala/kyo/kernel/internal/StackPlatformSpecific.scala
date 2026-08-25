package kyo.kernel.internal

private[kyo] class StackPlatformSpecific

private[kyo] object StackPlatformSpecific:

    // a plain module val where jvm-native uses `@static`: a static field initializer runs at script
    // evaluation on Scala.js, in file order, so it can capture undefined for a dependency defined
    // later; the module read is initializer-order safe
    private[internal] val local: ThreadLocal[Stack.Pool] =
        new ThreadLocal[Stack.Pool]:
            override def initialValue() = new Stack.Pool
end StackPlatformSpecific
