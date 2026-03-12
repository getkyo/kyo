package kyo.stats.otlp

import kyo.*

class OTLPExporterFactoryTest extends Test:

    given AllowUnsafe = AllowUnsafe.embrace.danger

    "traceExporter" - {

        "returns None when endpoint not set" in {
            val factory = new OTLPExporterFactory()
            assert(factory.traceExporter().isEmpty)
        }
    }

end OTLPExporterFactoryTest
