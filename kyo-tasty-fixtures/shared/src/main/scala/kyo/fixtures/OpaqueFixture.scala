package kyo.fixtures

/** Opaque type fixtures for cross-platform OpaqueType fidelity testing.
  *
  * Provides two opaque types (Micros and Millis) with companion objects so that OpaqueTypeFidelityTest
  * can run on JS/Native without requiring kyo.Maybe, kyo.Result, or kyo.Duration from kyo-data.
  *
  * Micros and Millis are defined at package level so their binary FQNs follow the $package$ pattern
  * used by all package-level definitions in Scala 3.
  */
opaque type Micros = Long
object Micros:
    def apply(n: Long): Micros             = n
    extension (m: Micros) def toLong: Long = m
end Micros

opaque type Millis = Long
object Millis:
    def apply(n: Long): Millis             = n
    extension (m: Millis) def toLong: Long = m
end Millis
