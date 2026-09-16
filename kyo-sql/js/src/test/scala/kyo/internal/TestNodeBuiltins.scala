package kyo.internal

import kyo.*
import scala.scalajs.js

/** A Node built-in module, resolved when it is asked for rather than when this file loads.
  *
  * A static `@JSImport` compiles to a `require` under CommonJS and to an `import` under ESModule, either of which runs as the module loads.
  * A page resolves neither, so one such import in one test helper kept the whole link from loading there, and with it every suite in this
  * module, including the ones that never touch Node. `process.getBuiltinModule` moves that to the call: a Node-like host answers, a page
  * answers `Absent`, and the suites that need neither run in both.
  *
  * The `Test` prefix is deliberate, for the reason [[TestProcessId]] records: a second class named after one of kyo's own is a name the
  * Scala.js linker resolves to whichever it saw first.
  */
private[kyo] object TestNodeBuiltins:

    def get(id: String): Maybe[js.Dynamic] =
        val process = js.Dynamic.global.selectDynamic("process")
        if js.isUndefined(process) || process == null then Absent
        else if js.typeOf(process.selectDynamic("getBuiltinModule")) != "function" then Absent
        else
            try Maybe(process.applyDynamic("getBuiltinModule")(id))
            catch case _: Throwable => Absent
        end if
    end get

end TestNodeBuiltins
