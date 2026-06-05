package kyo.internal.tasty.type_

/** Native implementation: single-threaded, so a plain object-level var is sufficient. */
private[type_] object PlatformHashingState:

    private val state: IdentitySet[kyo.Tasty.Type] = new IdentitySet[kyo.Tasty.Type]()

    def get(): IdentitySet[kyo.Tasty.Type] = state

end PlatformHashingState
