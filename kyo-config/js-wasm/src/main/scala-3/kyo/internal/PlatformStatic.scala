package kyo.internal

import scala.scalajs.LinkingInfo

/** The Scala 3 members of [[Platform]] on Scala.js.
  *
  * One published Scala.js artifact is linked as JS or as WasmGC by each application, so `isWasm` is not known when kyo is compiled: it is a
  * link-time property. Both members are `transparent inline` so the call site sees `LinkingInfo` itself, which is the only form
  * `LinkingInfo.linkTimeIf` accepts in its condition; a plain `def` or `inline def` forwarder is rejected there.
  */
trait PlatformStatic:

    /** Whether the application is linked with the WebAssembly (WasmGC) backend. Resolved by the linker, not by scalac. */
    transparent inline def isWasm: Boolean = LinkingInfo.isWebAssembly

    /** `thenp` when `cond` holds and `elsep` otherwise, resolved at link time: the untaken branch is compiled into the artifact but left out of
      * the linked output. `cond` may combine only `isJVM`, `isJS`, `isNative`, and `isWasm`.
      */
    transparent inline def linkTimeIf[T](inline cond: Boolean)(inline thenp: T)(inline elsep: T): T =
        LinkingInfo.linkTimeIf(cond)(thenp)(elsep)

    given platformOsCanEqual: CanEqual[Platform.Os, Platform.Os]       = CanEqual.derived
    given platformArchCanEqual: CanEqual[Platform.Arch, Platform.Arch] = CanEqual.derived
    given platformHostCanEqual: CanEqual[Platform.Host, Platform.Host] = CanEqual.derived
end PlatformStatic
