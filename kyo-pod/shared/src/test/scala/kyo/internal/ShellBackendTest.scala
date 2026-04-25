package kyo.internal

import kyo.*

class ShellBackendTest extends kyo.Test:

    "lastLine" - {

        "empty input returns empty" in {
            assert(ShellBackend.lastLine("") == "")
            succeed
        }

        "whitespace-only input returns empty" in {
            assert(ShellBackend.lastLine("   \n  \n\t\n") == "")
            succeed
        }

        "single line returns the line trimmed" in {
            assert(
                ShellBackend.lastLine("66451f24177d4ba33ecf6aa2c7270c2ee8dc90d61e180fae085332f380b1f5e2") ==
                    "66451f24177d4ba33ecf6aa2c7270c2ee8dc90d61e180fae085332f380b1f5e2"
            )
            succeed
        }

        "trailing newline is stripped" in {
            assert(ShellBackend.lastLine("abc123\n") == "abc123")
            succeed
        }

        // Reproduces the CI failure: rootless podman without a systemd user session emits cgroupv2
        // fallback warnings to stderr, which run() merges into stdout via 2>&1. Without lastLine,
        // the entire blob would be passed to `podman start` as the container ID.
        "podman cgroupv2 warnings before container ID" in {
            val output =
                """time="2026-04-25T21:13:13Z" level=warning msg="The cgroupv2 manager is set to systemd but there is no systemd user session available"
                  |time="2026-04-25T21:13:13Z" level=warning msg="For using systemd, you may need to log in using a user session"
                  |time="2026-04-25T21:13:13Z" level=warning msg="Alternatively, you can enable lingering with: `loginctl enable-linger 1001` (possibly as root)"
                  |time="2026-04-25T21:13:13Z" level=warning msg="Falling back to --cgroup-manager=cgroupfs"
                  |Resolved "alpine" as an alias (/etc/containers/registries.conf.d/shortnames.conf)
                  |Trying to pull docker.io/library/alpine:latest...
                  |Getting image source signatures
                  |Copying blob sha256:6a0ac1617861a677b045b7ff88545213ec31c0ff08763195a70a4a5adda577bb
                  |Copying config sha256:3cb067eab609612d81b4d82ff8ad71d73482bb3059a87b642d7e14f0ed659cde
                  |Writing manifest to image destination
                  |66451f24177d4ba33ecf6aa2c7270c2ee8dc90d61e180fae085332f380b1f5e2""".stripMargin
            assert(
                ShellBackend.lastLine(output) ==
                    "66451f24177d4ba33ecf6aa2c7270c2ee8dc90d61e180fae085332f380b1f5e2"
            )
            succeed
        }

        // Reproduces the CI failure: docker auto-pulls when the image is not local on `docker create`,
        // emitting pull progress to stdout before the container ID.
        "docker auto-pull progress before container ID" in {
            val output =
                """Unable to find image 'alpine:latest' locally
                  |latest: Pulling from library/alpine
                  |6a0ac1617861: Pulling fs layer
                  |6a0ac1617861: Download complete
                  |6a0ac1617861: Pull complete
                  |Digest: sha256:5b10f432ef3da1b8d4c7eb6c487f2f5a8f096bc91145e68878dd4a5019afde11
                  |Status: Downloaded newer image for alpine:latest
                  |4aecf173944311ec19d8f8cdb5659c1ca2a64873d1c40c240a76ee9a38c5f743""".stripMargin
            assert(
                ShellBackend.lastLine(output) ==
                    "4aecf173944311ec19d8f8cdb5659c1ca2a64873d1c40c240a76ee9a38c5f743"
            )
            succeed
        }

        "blank lines between content are skipped" in {
            assert(ShellBackend.lastLine("first\n\n\nlast\n") == "last")
            succeed
        }

        "trailing whitespace lines are skipped" in {
            assert(ShellBackend.lastLine("the-id\n   \n\t\n") == "the-id")
            succeed
        }

        "windows CRLF line endings" in {
            assert(ShellBackend.lastLine("warning\r\nthe-id\r\n") == "the-id")
            succeed
        }
    }
end ShellBackendTest
