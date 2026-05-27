package kyo.internal.tasty.type_

import scala.collection.mutable

/** JVM implementation: backed by a ThreadLocal so concurrent decode fibers each get their own set. */
private[type_] object PlatformHashingState:

    private val local: ThreadLocal[mutable.HashSet[kyo.Tasty.Type]] =
        ThreadLocal.withInitial(() => new mutable.HashSet[kyo.Tasty.Type]())

    def get(): mutable.HashSet[kyo.Tasty.Type] = local.get()

end PlatformHashingState
