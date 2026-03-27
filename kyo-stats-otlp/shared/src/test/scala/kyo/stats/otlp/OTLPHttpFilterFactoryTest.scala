package kyo.stats.otlp

import kyo.*

class OTLPHttpFilterFactoryTest extends Test:

    given Frame = Frame.internal
    import AllowUnsafe.embrace.danger

    "serverFilter" - {
        "returns None when endpoint not set" in {
            val factory = new OTLPHttpFilterFactory()
            assert(factory.serverFilter.isEmpty)
        }
    }

    "clientFilter" - {
        "returns None when endpoint not set" in {
            val factory = new OTLPHttpFilterFactory()
            assert(factory.clientFilter.isEmpty)
        }
    }

end OTLPHttpFilterFactoryTest
