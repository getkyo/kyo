package kyo.stats.machine

import kyo.*
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.JSServiceLoaderRegistry

// JS/Wasm-axis: MachineRegistration is a Scala.js @JSExportTopLevel object with no JVM/Native
// counterpart, so this test lives in js/src/test and cannot live in shared/src/test (it
// would fail to cross-compile). It runs on both the JS and the Wasm rows.
class MachineRegistrationTest extends kyo.test.Test[Any]:

    "registration fires via @JSExportTopLevel alone (no META-INF scan on Scala.js)" in {
        // Referencing the object forces its module-load initializer, the same forcing every
        // @JSExportTopLevel object gets from the module system at real page/script load.
        assert(MachineRegistration.init)
        val registered = JSServiceLoaderRegistry.get(classOf[ExporterFactory].getName)
        // Registering constructs the factory, which starts the sampler, which reads the machine through Node's
        // own modules. This row is a Node-like host, so the factory belongs here; a host with no machine to read
        // gets none, which is what the MachineStats link-check program states.
        assert(kyo.internal.Platform.isNodeLike, "this row runs on a Node-like host")
        assert(registered.exists(_.isInstanceOf[MachineStatFactory]))
    }

end MachineRegistrationTest
