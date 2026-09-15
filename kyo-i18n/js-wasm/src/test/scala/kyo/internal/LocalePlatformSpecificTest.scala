package kyo.internal

import kyo.*
import scala.scalajs.js

class LocalePlatformSpecificTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "browserLanguageTag is empty on Node, which defers to System" in {
        // The Node rows run under Node; this leaf pins the documented contract for that host.
        assume(js.typeOf(js.Dynamic.global.process) != "undefined", "not a Node host")
        assert(LocalePlatformSpecific.browserLanguageTag() == "")
    }

end LocalePlatformSpecificTest
