package kyo.internal

import kyo.*

class ContainerBackendTest extends kyo.BasePodTest:

    private def decoded(text: String): Result[Base64.Failure, String] =
        ContainerBackend.decodeBase64(text).map(bytes => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))

    "decodeBase64" - {
        "reads padded standard base64" in {
            assert(decoded("YWxpY2U6cHc=") == Result.succeed("alice:pw"))
        }

        "reads the same text with its padding omitted" in {
            assert(decoded("YWxpY2U6cHc") == Result.succeed("alice:pw"))
            assert(decoded("YQ") == Result.succeed("a"))
            assert(decoded("YWI") == Result.succeed("ab"))
        }

        "reads the URL-safe alphabet" in {
            // "~~~" is "fn5+" in the standard alphabet and "fn5-" in the URL-safe one; "???" is "Pz8/" and "Pz8_".
            assert(decoded("fn5-") == Result.succeed("~~~"))
            assert(decoded("Pz8_") == Result.succeed("???"))
        }

        "refuses text no padding can complete" in {
            assert(decoded("YWxpY").isFailure)
            assert(decoded("not*base64").isFailure)
        }
    }

end ContainerBackendTest
