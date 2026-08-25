package kyo.kernel.internal

import scala.annotation.static

private[kyo] class StackPlatformSpecific

private[kyo] object StackPlatformSpecific:

    // `@static` here, plain on js-wasm: a static field initializer runs at script evaluation on
    // Scala.js, in file order, so it can capture undefined for a dependency defined later
    @static private[internal] val local: ThreadLocal[Stack.Pool] =
        new ThreadLocal[Stack.Pool]:
            override def initialValue() = new Stack.Pool
end StackPlatformSpecific
