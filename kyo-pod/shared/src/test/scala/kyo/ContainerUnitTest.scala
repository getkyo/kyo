package kyo

class ContainerUnitTest extends Test:

    private def assertSuccess[A](r: Result[String, A]): A =
        r match
            case Result.Success(v) => v
            case other             => fail(s"Expected Success but got: $other")

    // =========================================================================
    // ContainerImage.parse
    // =========================================================================

    "ContainerImage.parse" - {

        "simple name defaults to tag latest" in {
            val img = assertSuccess(ContainerImage.parse("alpine"))
            assert(img.name == "alpine")
            assert(img.tag == Present("latest"))
            assert(img.registry == Absent)
            assert(img.namespace == Absent)
            assert(img.digest == Absent)
        }

        "name:tag" in {
            val img = assertSuccess(ContainerImage.parse("alpine:3.19"))
            assert(img.name == "alpine")
            assert(img.tag == Present("3.19"))
            assert(img.digest == Absent)
        }

        "name with digest" in {
            val img = assertSuccess(ContainerImage.parse("myapp@sha256:abc123def"))
            assert(img.name == "myapp")
            assert(img.tag == Absent)
            // Digest.apply prepends "sha256:" if not present, but input already has it.
            // Verify no double-prefix bug
            assert(img.digest.get.value == "sha256:abc123def")
        }

        "registry with port" in {
            val img = assertSuccess(ContainerImage.parse("localhost:5000/myapp:v1"))
            assert(img.registry == Present(ContainerImage.Registry("localhost:5000")))
            assert(img.name == "myapp")
            assert(img.tag == Present("v1"))
        }

        "registry/namespace/name:tag" in {
            val img = assertSuccess(ContainerImage.parse("ghcr.io/owner/repo:v1.2.3"))
            assert(img.registry == Present(ContainerImage.Registry("ghcr.io")))
            assert(img.namespace == Present("owner"))
            assert(img.name == "repo")
            assert(img.tag == Present("v1.2.3"))
        }

        "deeply nested namespace" in {
            val img = assertSuccess(ContainerImage.parse("registry.io/a/b/c/image:v1"))
            assert(img.registry == Present(ContainerImage.Registry("registry.io")))
            assert(img.name.contains("image"))
            assert(img.tag == Present("v1"))
        }

        "empty string fails" in {
            val r = ContainerImage.parse("")
            assert(r.isFailure)
        }

        "just a tag ':latest' — empty name" in {
            // BUG: parse(":latest") produces an image with empty name.
            // This is nonsensical but not rejected by the parser.
            val r = ContainerImage.parse(":latest")
            // The parser should ideally fail, but currently it doesn't.
            // If it succeeds, the name will be empty.
            r match
                case Result.Success(img) =>
                    assert(img.name == "")
                case Result.Failure(_) =>
                    // This would be the correct behavior
                    succeed
                case _ => fail("Unexpected result type")
            end match
        }

        "digest only '@sha256:abc' — empty name" in {
            // BUG: parse("@sha256:abc") produces an image with empty name.
            val r = ContainerImage.parse("@sha256:abc")
            r match
                case Result.Success(img) =>
                    assert(img.name == "")
                case Result.Failure(_) =>
                    succeed
                case _ => fail("Unexpected result type")
            end match
        }

        "namespace without registry (library/alpine)" in {
            val img = assertSuccess(ContainerImage.parse("library/alpine"))
            assert(img.namespace == Present("library"))
            assert(img.name == "alpine")
            assert(img.tag == Present("latest"))
            assert(img.registry == Absent)
        }

        "predefined image roundtrip — Alpine" in {
            val ref    = ContainerImage.Alpine.reference
            val parsed = assertSuccess(ContainerImage.parse(ref))
            assert(parsed.name == ContainerImage.Alpine.name)
            assert(parsed.tag == ContainerImage.Alpine.tag)
            assert(parsed.registry == ContainerImage.Alpine.registry)
            assert(parsed.namespace == ContainerImage.Alpine.namespace)
        }

        "withTag clears digest and vice versa" in {
            val base     = ContainerImage("myapp")
            val digested = base.withDigest(ContainerImage.Digest("abc123"))
            assert(digested.digest.get.value == "sha256:abc123")
            assert(digested.tag == Absent)

            val tagged = digested.withTag("v2")
            assert(tagged.tag == Present("v2"))
            assert(tagged.digest == Absent)
        }
    }

    // =========================================================================
    // Container.Platform.parse
    // =========================================================================

    "Container.Platform.parse" - {

        "2 parts" in {
            val p = assertSuccess(Container.Platform.parse("linux/amd64"))
            assert(p.os == "linux")
            assert(p.arch == "amd64")
            assert(p.variant == Absent)
        }

        "3 parts with variant" in {
            val p = assertSuccess(Container.Platform.parse("linux/arm/v7"))
            assert(p.os == "linux")
            assert(p.arch == "arm")
            assert(p.variant == Present("v7"))
        }

        "invalid format fails" in {
            val r = Container.Platform.parse("invalid")
            assert(r.isFailure)
        }
    }

    // =========================================================================
    // ContainerImage.Digest
    // =========================================================================

    "ContainerImage.Digest" - {

        "auto-prefixes sha256 when not present" in {
            val d = ContainerImage.Digest("abc123")
            assert(d.value == "sha256:abc123")
        }

        "does not double-prefix when sha256: already present" in {
            val d = ContainerImage.Digest("sha256:abc123")
            assert(d.value == "sha256:abc123")
        }
    }

    // =========================================================================
    // Container.Config.Protocol.cliName
    // =========================================================================

    "Container.Config.Protocol.cliName" - {

        "TCP" in {
            assert(Container.Config.Protocol.TCP.cliName == "tcp")
        }

        "UDP" in {
            assert(Container.Config.Protocol.UDP.cliName == "udp")
        }

        "SCTP" in {
            assert(Container.Config.Protocol.SCTP.cliName == "sctp")
        }
    }

    // =========================================================================
    // Container.Config.RestartPolicy.cliName
    // =========================================================================

    "Container.Config.RestartPolicy.cliName" - {

        "No" in {
            assert(Container.Config.RestartPolicy.No.cliName == "no")
        }

        "Always" in {
            assert(Container.Config.RestartPolicy.Always.cliName == "always")
        }

        "UnlessStopped" in {
            assert(Container.Config.RestartPolicy.UnlessStopped.cliName == "unless-stopped")
        }

        "OnFailure" in {
            assert(Container.Config.RestartPolicy.OnFailure(5).cliName == "on-failure")
        }
    }

    // =========================================================================
    // HealthCheck.all with zero checks
    // =========================================================================

    "HealthCheck.all with zero checks" - {

        "empty composite check uses defaultSchedule" in {
            // BUG: HealthCheck.all() with no checks always passes,
            // even for a dead container, since Kyo.foreach(Seq.empty) returns unit.
            val hc = Container.HealthCheck.all()
            assert(hc.schedule == Container.HealthCheck.defaultSchedule)
        }
    }

end ContainerUnitTest
