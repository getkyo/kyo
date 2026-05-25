package kyo.internal.reflect.query

import kyo.*

/** Per-classpath FQN resolver with concurrent deduplication.
  *
  * Uses `Cache.memo` from kyo-core to ensure that two concurrent `findClass(same fqn)` calls collapse into a single underlying resolution
  * via Promise dedup. The extra `Async` in the memoized function's result type comes from callers waiting on the Promise when they lose the
  * Cache.memo race.
  *
  * Missing FQNs return `Absent` (not an error) in soft-fail mode. The only error is `ClasspathClosed`.
  *
  * Wiring: `makeClassLookup`/`makePackageLookup` wrap `Classpath.rawLookupClass`/`rawLookupPackage` (the direct fqnIndex readers). The
  * resulting Cache.memo functions are stored on the Classpath as `classLookup`/`packageLookup` and are called from
  * `Classpath.lookupClass`/`lookupPackage`. The `readyLatch` inside `rawLookupClass`/`rawLookupPackage` gates lookups during Building
  * state; `Cache.memo` deduplicates concurrent calls on Ready state.
  *
  * Initialization is done in `Classpath.allocate` (supervisor-approved, documented in PROGRESS.md under "v2 Phase 1 (Async expansion)").
  */
object Resolver:

    /** Build a per-classpath memoized class-lookup function.
      *
      * Wraps `cp.rawLookupClass` with `Cache.memo` so that concurrent calls for the same FQN collapse into a single underlying resolution.
      * The returned function has type `String => Maybe[Reflect.Symbol] < (Async & Sync & Abort[ReflectError])`.
      *
      * Called once per Classpath instance during `Classpath.allocate`; the result is stored in `Classpath.classLookup` and delegated to by
      * `Classpath.lookupClass`.
      */
    def makeClassLookup(
        cp: Classpath,
        maxSize: Int
    )(using Frame): (String => Maybe[Reflect.Symbol] < (Async & Sync & Abort[ReflectError])) < Sync =
        Cache.memo[String](maxSize): fqn =>
            cp.rawLookupClass(fqn)

    /** Build a per-classpath memoized package-lookup function.
      *
      * Wraps `cp.rawLookupPackage` with `Cache.memo`. Stored in `Classpath.packageLookup` and delegated to by `Classpath.lookupPackage`.
      */
    def makePackageLookup(
        cp: Classpath,
        maxSize: Int
    )(using Frame): (String => Maybe[Reflect.Symbol] < (Async & Sync & Abort[ReflectError])) < Sync =
        Cache.memo[String](maxSize): fqn =>
            cp.rawLookupPackage(fqn)

end Resolver
