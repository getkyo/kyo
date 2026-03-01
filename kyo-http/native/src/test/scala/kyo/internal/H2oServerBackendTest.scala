package kyo.internal

import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class H2oServerBackendTest extends AnyFreeSpec with NonImplicitAssertions:

    "HttpPlatformBackend" - {

        "client is CurlClientBackend" in {
            assert(HttpPlatformBackend.client.isInstanceOf[CurlClientBackend])
        }

        "server is H2oServerBackend" in {
            assert(HttpPlatformBackend.server.isInstanceOf[H2oServerBackend])
        }
    }

end H2oServerBackendTest
