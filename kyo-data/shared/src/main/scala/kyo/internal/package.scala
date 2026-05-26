package kyo

/** Package object for `kyo.internal`.
  *
  * `kyo.internal` is a split package contributed to by kyo-data, kyo-core, kyo-schema, and kyo-sql. Without an explicit package object, the
  * Scala 3 compiler can be driven (via an `inline def` that references a `private[kyo]` member through a `kyo.internal.X` path) to "mock
  * up" a synthetic module class for `kyo.internal` and emit `getstatic kyo/internal.MODULE$` at call sites, a class that never exists on
  * the runtime classpath, causing `NoClassDefFoundError: kyo/internal`.
  *
  * Declaring the package object here, in the lowest module of the dependency graph, gives every downstream compile a real `kyo.internal`
  * module so the compiler resolves it instead of mocking one up.
  */
package object internal
