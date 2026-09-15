package kyo.internal

import kyo.*

class LocalePlatformSpecificTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "browserLanguageTag is empty on Node, which defers to System" in {
        // The Node rows run under Node, which defines navigator.language; this leaf pins the documented contract for that host.
        assume(Platform.host == Platform.Host.Node, "not a Node host")
        assert(LocalePlatformSpecific.browserLanguageTag() == "")
    }

end LocalePlatformSpecificTest
