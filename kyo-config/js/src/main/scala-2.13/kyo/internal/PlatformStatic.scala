package kyo.internal

import scala.scalajs.LinkingInfo

/** The Scala 2.13 declaration of [[Platform]]'s version-specific members on Scala.js.
  *
  * Scala 2.13 has no `inline`, so `isWasm` is a plain forwarder (an ordinary `if` on it still folds in optimized links) and `linkTimeIf` is a
  * run-time branch. Nothing compiled for 2.13 branches on link-time members; the declarations exist so the interface is the same in every
  * build.
  */
trait PlatformStatic {

    /** Whether the application is linked with the WebAssembly (WasmGC) backend. */
    def isWasm: Boolean = LinkingInfo.isWebAssembly

    /** Whether the application's link can split into modules a host loads on demand: not under WasmGC, and not under `NoModule`. */
    def canSplitModules: Boolean = !LinkingInfo.isWebAssembly && LinkingInfo.moduleKind != LinkingInfo.ModuleKind.NoModule

    /** `thenp` when `cond` holds and `elsep` otherwise, evaluated at run time on Scala 2.13. */
    def linkTimeIf[T](cond: Boolean)(thenp: => T)(elsep: => T): T = if (cond) thenp else elsep
}
