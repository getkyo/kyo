package kyo.internal

import scala.scalajs.LinkingInfo

/** The Scala 3 members of [[Platform]] on Scala.js, for the Scala 3.3 LTS line.
  *
  * The Scala.js backend of Scala 3.3 does not resolve `LinkingInfo.linkTimeIf`: code it compiles emits a call to a method that does not exist,
  * and the link fails with "Referring to non-existent method LinkingInfo$.linkTimeIf". So here, as on Scala 2.13, a `linkTimeIf` whose
  * condition depends on `isWasm` or `canSplitModules` is a run-time branch: both branches reach the linked output, and the branch that is not
  * taken is dead code rather than absent. Nothing compiled for this line uses `linkTimeIf` to keep out code a link would reject, such as a
  * `js.dynamicImport` under WasmGC; the modules that do build only on the Next line (`scala-3-next`).
  */
trait PlatformStatic:

    /** Whether the application is linked with the WebAssembly (WasmGC) backend. Resolved by the linker, not by scalac. */
    transparent inline def isWasm: Boolean = LinkingInfo.isWebAssembly

    /** Whether the application's link can split into modules a host loads on demand: not under WasmGC, and not under `NoModule`. */
    transparent inline def canSplitModules: Boolean =
        !LinkingInfo.isWebAssembly && LinkingInfo.moduleKind != LinkingInfo.ModuleKind.NoModule

    /** `thenp` when `cond` holds and `elsep` otherwise. `cond` may combine only `isJVM`, `isJS`, `isNative`, `isWasm`, and `canSplitModules`.
      *
      * A condition that reduces to a constant (one without `isWasm` or `canSplitModules`) is resolved when compiling, exactly as `inline if`.
      * A condition that depends on either is evaluated at run time on this line.
      */
    transparent inline def linkTimeIf[T](inline cond: Boolean)(inline thenp: T)(inline elsep: T): T =
        inline cond match
            case true  => thenp
            case false => elsep
            case _     => if cond then thenp else elsep

    given platformOsCanEqual: CanEqual[Platform.Os, Platform.Os]       = CanEqual.derived
    given platformArchCanEqual: CanEqual[Platform.Arch, Platform.Arch] = CanEqual.derived
    given platformHostCanEqual: CanEqual[Platform.Host, Platform.Host] = CanEqual.derived
end PlatformStatic
