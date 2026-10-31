package kyo.internal

import kyo.*

class ContainerBackendTest extends kyo.BasePodTest:

    private def decoded(text: String): Result[Base64.Failure, String] =
        ContainerBackend.decodeBase64(text).map(bytes => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))

    "defaultCopy" - {
        import Container.Platform.*
        val arm64Host = LinuxArm64
        val amd64Host = LinuxAmd64

        "nothing cached picks nothing" in {
            assert(ContainerBackend.defaultCopy(amd64Host, Chunk.empty) == Absent)
        }

        "the host's own copy wins over every other" in {
            val armV8 = Container.Platform("linux", "arm64", Present("v8"))
            assert(ContainerBackend.defaultCopy(arm64Host, Chunk(LinuxAmd64, LinuxArmV7, armV8)) == Present(armV8))
            assert(ContainerBackend.defaultCopy(amd64Host, Chunk(Linux386, LinuxArm64, LinuxAmd64)) == Present(LinuxAmd64))
        }

        "a copy the host runs natively wins over one it would emulate" in {
            assert(ContainerBackend.defaultCopy(amd64Host, Chunk(LinuxArm64, Linux386)) == Present(Linux386))
            assert(ContainerBackend.defaultCopy(arm64Host, Chunk(LinuxAmd64, LinuxArmV6, LinuxArmV7)) == Present(LinuxArmV7))
        }

        "among copies the host would emulate, the index's first wins" in {
            assert(ContainerBackend.defaultCopy(amd64Host, Chunk(LinuxS390x, LinuxArm64)) == Present(LinuxS390x))
            assert(ContainerBackend.defaultCopy(arm64Host, Chunk(LinuxAmd64, LinuxPpc64le)) == Present(LinuxAmd64))
        }

        "a copy for another os is never native" in {
            val windows = Container.Platform("windows", "amd64")
            assert(ContainerBackend.defaultCopy(amd64Host, Chunk(windows, LinuxS390x)) == Present(windows))
        }
    }

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
