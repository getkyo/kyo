package kyo

class JsonDtoTest extends Test:

    private case class Inner(X: String = "", Y: Int = 0) derives Json, CanEqual
    private case class Outer(Id: String = "", Inner: Inner = Inner()) derives Json, CanEqual

    "Json DTO with defaults" - {
        "decodes with all fields present" in {
            val result = Json[Outer].decode("""{"Id":"hello","Inner":{"X":"world","Y":42}}""")
            assert(result == Result.succeed(Outer("hello", Inner("world", 42))))
        }

        "decodes with missing optional fields uses defaults" in {
            val result = Json[Outer].decode("""{"Id":"hello"}""")
            assert(result == Result.succeed(Outer("hello", Inner())))
        }

        "decodes with extra unknown fields" in {
            val result = Json[Outer].decode("""{"Id":"hello","Unknown":"ignored"}""")
            assert(result == Result.succeed(Outer("hello", Inner())))
        }
    }
    private case class PortMap(HostIp: String = "", HostPort: String = "") derives Json, CanEqual
    private case class NetSettings(
        IPAddress: String = "",
        Ports: Option[Map[String, Seq[PortMap]]] = None,
        Networks: Option[Map[String, Inner]] = None
    ) derives Json, CanEqual

    "Json DTO ports/networks" - {
        "decodes port mappings" in {
            val json =
                """{"IPAddress":"10.0.0.1","Ports":{"80/tcp":[{"HostIp":"0.0.0.0","HostPort":"8080"}]},"Networks":{"bridge":{"X":"test","Y":1}}}"""
            val result = Json[NetSettings].decode(json)
            result match
                case Result.Success(ns) =>
                    assert(ns.IPAddress == "10.0.0.1")
                    assert(ns.Ports.get.apply("80/tcp").head.HostPort == "8080")
                    assert(ns.Networks.get.apply("bridge").X == "test")
                case Result.Failure(err) => fail(s"Decode failed: $err")
            end match
        }

        "decodes null ports as empty" in {
            val json   = """{"IPAddress":"10.0.0.1","Ports":null}"""
            val result = Json[NetSettings].decode(json)
            assert(result.isSuccess)
        }
    }
    // Test: can union-typed fields decode both formats?
    "Union-typed fields" - {
        "String | Map decodes string" in {
            case class UnionTest(Labels: String | Map[String, String] = "") derives Json
            val result = Json[UnionTest].decode("""{"Labels":"key=val"}""")
            assert(result.isSuccess)
        }

        "String | Map decodes object" in {
            case class UnionTest(Labels: String | Map[String, String] = "") derives Json
            val result = Json[UnionTest].decode("""{"Labels":{"key":"val"}}""")
            assert(result.isSuccess)
        }

        "String | Seq decodes string" in {
            case class UnionTest2(Command: String | Seq[String] = "") derives Json
            val result = Json[UnionTest2].decode("""{"Command":"echo hello"}""")
            assert(result.isSuccess)
        }

        "String | Seq decodes array" in {
            case class UnionTest2(Command: String | Seq[String] = "") derives Json
            val result = Json[UnionTest2].decode("""{"Command":["echo","hello"]}""")
            assert(result.isSuccess)
        }
    }
end JsonDtoTest
