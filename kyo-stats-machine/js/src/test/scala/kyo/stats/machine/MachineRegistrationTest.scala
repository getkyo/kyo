package kyo.stats.machine

import kyo.*
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.JSServiceLoaderRegistry

// JS/Wasm-axis: MachineRegistration is a Scala.js @JSExportTopLevel object with no JVM/Native
// counterpart, so this test lives in js-wasm/src/test and cannot live in shared/src/test (it
// would fail to cross-compile). It runs on both the JS and the Wasm backends.
class MachineRegistrationTest extends kyo.test.Test[Any]:

    "registration fires via @JSExportTopLevel alone (no META-INF scan on Scala.js)" in {
        // Referencing the object forces its module-load initializer, the same forcing every
        // @JSExportTopLevel object gets from the module system at real page/script load.
        assert(MachineRegistration.init)
        val registered = JSServiceLoaderRegistry.get(classOf[ExporterFactory].getName)
        assert(registered.exists(_.isInstanceOf[MachineStatFactory]))
    }

end MachineRegistrationTest
