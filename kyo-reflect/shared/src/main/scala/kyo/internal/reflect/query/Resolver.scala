package kyo.internal.reflect.query

import kyo.*

/** Per-classpath FQN resolver with concurrent deduplication.
  *
  * Uses `Cache.memo` from kyo-core to ensure that two concurrent `findClass(same fqn)` calls return the same `Symbol` instance
  * (reference-equal). The extra `Async` in the memoized function's result type comes from callers waiting on the Promise when they lose the
  * Cache.memo race.
  *
  * Missing FQNs return `Absent` (not an error) in soft-fail mode. The only error is `ClasspathClosed`.
  */
object Resolver:

    /** Build a per-classpath memoized class-lookup function.
      *
      * The returned function is `String => Maybe[Reflect.Symbol] < (Async & Sync & Abort[ReflectError])`. It must be initialized once per
      * `Classpath` instance (inside `ClasspathOrchestrator.open`), then stored on the classpath. However, to keep Classpath lean, we create
      * the memoized fn inline in the orchestrator and wire lookups there.
      *
      * This factory method exists so tests can construct a resolver independently.
      */
    def makeClassLookup(
        cp: Classpath,
        maxSize: Int
    )(using Frame): (String => Maybe[Reflect.Symbol] < (Async & Sync & Abort[ReflectError])) < Sync =
        Cache.memo[String](maxSize): fqn =>
            cp.lookupClass(fqn)

    /** Build a per-classpath memoized package-lookup function. */
    def makePackageLookup(
        cp: Classpath,
        maxSize: Int
    )(using Frame): (String => Maybe[Reflect.Symbol] < (Async & Sync & Abort[ReflectError])) < Sync =
        Cache.memo[String](maxSize): fqn =>
            cp.lookupPackage(fqn)

end Resolver
