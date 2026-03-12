package kyo.stats.otlp

import kyo.*

class OTLPConfigTest extends Test:

    import AllowUnsafe.embrace.danger

    "loadIfEnabled" - {

        "returns None when OTEL_EXPORTER_OTLP_ENDPOINT is not set" in {
            assert(OTLPConfig.loadIfEnabled().isEmpty)
        }
    }

    "case class construction" - {

        "accepts all fields" in {
            val config = OTLPConfig(
                endpoint = "http://localhost:4318",
                tracesEndpoint = "http://localhost:4318/v1/traces",
                metricsEndpoint = "http://localhost:4318/v1/metrics",
                headers = Map("Authorization" -> "Bearer token"),
                timeout = 10.seconds,
                compression = "gzip",
                serviceName = "my-service",
                resourceAttributes = Map("env" -> "prod"),
                bspScheduleDelay = 5.seconds,
                bspMaxQueueSize = 2048,
                bspMaxExportBatchSize = 512,
                bspExportTimeout = 30.seconds,
                metricExportInterval = 60.seconds,
                metricExportTimeout = 30.seconds
            )
            assert(config.endpoint == "http://localhost:4318")
            assert(config.headers == Map("Authorization" -> "Bearer token"))
            assert(config.serviceName == "my-service")
            assert(config.resourceAttributes == Map("env" -> "prod"))
            assert(config.bspMaxQueueSize == 2048)
            assert(config.bspMaxExportBatchSize == 512)
        }
    }

end OTLPConfigTest
