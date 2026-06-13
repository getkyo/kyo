package kyo.internal.tasty.type_

/** Native implementation: backed by a ThreadLocal so concurrent decode fibers each get their own set.
  *
  * The Native runtime runs the kyo scheduler across multiple worker threads, so the cold-load decode
  * pipeline interns types from several fibers in parallel. `TypeKey.computeHash` borrows this set as its
  * cycle-detection scratch, adding and removing entries as it walks a type. A single shared set would be
  * mutated by multiple threads at once, corrupting the in-progress markers and producing wrong structural
  * hashes, which collapses or drops distinct types during interning. Per-thread isolation keeps each
  * decode fiber's hashing independent.
  */
private[type_] object PlatformHashingState:

    private val local: ThreadLocal[IdentitySet[kyo.Tasty.Type]] =
        ThreadLocal.withInitial(() => new IdentitySet[kyo.Tasty.Type]())

    def get(): IdentitySet[kyo.Tasty.Type] = local.get()

end PlatformHashingState
