package kyo.internal

/** The Scala 2.13 declaration of [[Platform]]'s version-specific members on the JVM and Scala Native.
  *
  * Scala 2.13 has no `inline`, so `linkTimeIf` is a plain run-time branch here. Nothing compiled for 2.13 branches on link-time members; the
  * declaration exists so the interface is the same in every build.
  */
trait PlatformStatic {

    /** `thenp` when `cond` holds and `elsep` otherwise, evaluated at run time on Scala 2.13. */
    def linkTimeIf[T](cond: Boolean)(thenp: => T)(elsep: => T): T = if (cond) thenp else elsep
}
