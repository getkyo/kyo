package kyo.stats.machine

import kyo.*
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.JSServiceLoaderRegistry

// JS-axis-only: MachineRegistration is a JS-only @JSExportTopLevel object with no JVM/Native
// counterpart, so this test cannot live in shared/src/test (it would fail to cross-compile).
class MachineRegistrationTest extends kyo.test.Test[Any]:

    "JS registration fires via @JSExportTopLevel alone (no META-INF scan on JS)" in {
        // Referencing the object forces its module-load initializer, the same forcing every
        // @JSExportTopLevel object gets from the JS module system at real page/script load.
        assert(MachineRegistration.init)
        val registered = JSServiceLoaderRegistry.get(classOf[ExporterFactory].getName)
        assert(registered.exists(_.isInstanceOf[MachineStatFactory]))
    }

end MachineRegistrationTest
