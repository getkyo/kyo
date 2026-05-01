package kyo

import scala.compiletime.constValue as scConstValue

/** An opaque type that materializes a compile-time constant value as a runtime value via an implicit given.
  *
  * `ConstValue[A]` is a subtype of `A`, backed by `scala.compiletime.constValue`. When `A` is a singleton literal type (e.g., `"name"`,
  * `42`, `true`), summoning a `ConstValue[A]` produces the corresponding runtime value. This is useful for passing singleton type
  * information to runtime code without requiring an explicit `inline` context at every call site.
  *
  * @tparam A
  *   A singleton literal type whose compile-time value is materialized at runtime
  */
opaque type ConstValue[A] <: A = A

object ConstValue:
    /** Materializes the compile-time constant for singleton type `A` as a runtime value. */
    inline given [A]: ConstValue[A] = scConstValue[A]
