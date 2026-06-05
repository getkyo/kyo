package kyo.internal.tasty.type_

/** JVM implementation: backed by a ThreadLocal so concurrent decode fibers each get their own set. */
private[type_] object PlatformHashingState:

    private val local: ThreadLocal[IdentitySet[kyo.Tasty.Type]] =
        ThreadLocal.withInitial(() => new IdentitySet[kyo.Tasty.Type]())

    def get(): IdentitySet[kyo.Tasty.Type] = local.get()

end PlatformHashingState
