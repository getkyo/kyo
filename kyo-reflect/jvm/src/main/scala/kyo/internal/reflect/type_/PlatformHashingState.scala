package kyo.internal.reflect.type_

import scala.collection.mutable

/** JVM implementation: backed by a ThreadLocal so concurrent decode fibers each get their own set. */
private[type_] object PlatformHashingState:

    private val local: ThreadLocal[mutable.HashSet[kyo.Reflect.Type]] =
        ThreadLocal.withInitial(() => new mutable.HashSet[kyo.Reflect.Type]())

    def get(): mutable.HashSet[kyo.Reflect.Type] = local.get()

end PlatformHashingState
