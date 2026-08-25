package kyo.kernel.internal

import kyo.<

private[kyo] class Nested[+A](val value: A)

// public for the same reason as Safepoint's depth guard: the implicit lift is inline and names it, and an
// accessor here costs the hottest expansion in the kernel. The class stays private[kyo], so the wrapper
// itself is still unnameable outside kyo.
//
// Plain module methods rather than `@static`: an `@static` symbol named in inline-expanded code fails
// `bringForward` in a downstream module's suspended-unit retry run (a StaleSymbolException on that
// module's clean build whenever it defines its own macros), while a module method survives it. The cost
// is the module load at expansion sites.
object Nested:

    /** The settled value, one nesting level stripped. Only valid where the pending case is already excluded.
      *
      * This is `unsafeGet`'s test, reachable without going through the extension. An inline body that selects a
      * member through the opaque type's owner makes the expansion carry a proxy chain for that owner with its
      * refinement written out longhand, which costs more than the two lines below; a call to a plain object does
      * not.
      *
      * The parameter is `Any` on purpose. Typed `A`, the implicit lift would be in scope at every call site, and
      * lifting a value that is already union-represented corrupts it.
      */
    def unnest[A](v: Any): A =
        v match
            case v: Nested[A] @unchecked => v.value
            case v                       => v.asInstanceOf[A]

    def nest[A, S](v: A): A < S =
        v match
            case v: (Kyo[?, ?] | Nested[?]) => Nested(v).asInstanceOf[A < S]
            case _                          => v.asInstanceOf[A < S]

end Nested
