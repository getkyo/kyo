package kyo.internal

/** The Scala 3 members of [[Platform]] on the JVM and Scala Native.
  *
  * `linkTimeIf` is the cross-platform form of a branch that is resolved before the program runs. Here every condition it accepts is a
  * compile-time constant (platform identity, and `isWasm` and `canSplitModules`, which are `false`), so it is an `inline if` and the untaken
  * branch is never emitted. On Scala.js the same call resolves `isWasm` and `canSplitModules` at link time.
  */
trait PlatformStatic:

    /** `thenp` when `cond` holds and `elsep` otherwise, with the untaken branch left out of the output. `cond` may combine only `isJVM`,
      * `isJS`, `isNative`, `isWasm`, and `canSplitModules`: those are the members known before run time on every platform.
      */
    transparent inline def linkTimeIf[T](inline cond: Boolean)(inline thenp: T)(inline elsep: T): T =
        inline if cond then thenp else elsep

    given platformOsCanEqual: CanEqual[Platform.Os, Platform.Os]       = CanEqual.derived
    given platformArchCanEqual: CanEqual[Platform.Arch, Platform.Arch] = CanEqual.derived
    given platformHostCanEqual: CanEqual[Platform.Host, Platform.Host] = CanEqual.derived
end PlatformStatic
