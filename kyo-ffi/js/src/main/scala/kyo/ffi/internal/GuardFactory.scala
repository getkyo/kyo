package kyo.ffi.internal

import kyo.Frame
import kyo.ffi.Ffi
import scala.scalajs.js

/** Facade for the V8/Node [[FinalizationRegistry]] global. Node v14+, modern browsers and Deno all expose it. The cleanup callback fires on
  * unreachable targets at the JS host's discretion, best-effort only.
  */
@js.native
@js.annotation.JSGlobal("FinalizationRegistry")
private[ffi] class FinalizationRegistryFacade(cleanup: js.Function1[js.Any, Unit]) extends js.Object:
    def register(target: js.Any, heldValue: js.Any): Unit                = js.native
    def register(target: js.Any, heldValue: js.Any, token: js.Any): Unit = js.native
    def unregister(token: js.Any): Unit                                  = js.native
end FinalizationRegistryFacade

/** JS factory for [[Ffi.Guard]]. Registers with [[GuardRegistry]] and a process-wide [[FinalizationRegistryFacade]] for leak warnings. */
private[ffi] object GuardFactory:

    private val leakReg: FinalizationRegistryFacade =
        new FinalizationRegistryFacade((held: js.Any) =>
            val frameStr = held.asInstanceOf[String]
            val _        = js.Dynamic.global.console.error(FfiErrors.leakWarning(frameStr))
        )

    /** Open a new guard. The [[Frame]] is rendered to a string and handed to the finalization registry as the held diagnostic value. */
    def open(frame: Frame): Ffi.Guard =
        val g = GuardFactoryShared.register(new JsGuard(frame))
        leakReg.register(g.asInstanceOf[js.Any], (frame.show: String).asInstanceOf[js.Any])
        g
    end open
end GuardFactory
