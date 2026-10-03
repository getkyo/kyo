package kyo

class SqlConformanceBackendTest extends kyo.Test:

    "a conformance body cannot branch on the engine" - {

        "comparing a backend's label with a name does not compile" in {
            typeCheckFailure("""(b: SqlConformanceBackend) => b.label == "postgres"""")("cannot be compared")
        }

        "matching a backend's label against a name does not compile" in {
            typeCheckFailure("""(b: SqlConformanceBackend) => b.label match { case "postgres" => 1; case _ => 2 }""")("cannot be compared")
        }

        "a backend has no id to branch on" in {
            typeCheckFailure("""(b: SqlConformanceBackend) => b.id""")("value id is not a member")
        }

        "a backend has no URL scheme to branch on" in {
            typeCheckFailure("""(b: SqlConformanceBackend) => b.urlScheme""")("value urlScheme is not a member")
        }

        "a backend does not hand out the engine it opens clients through" in {
            typeCheckFailure("""(b: SqlConformanceBackend) => b.backend.scheme == "postgres"""")("value backend cannot be accessed")
        }
    }

    "a backend's label renders as its name in a leaf or a failure message" in {
        val label = SqlConformanceBackend.Label("Engine A")
        assert(s"[$label]: expected one row" == "[Engine A]: expected one row")
    }

end SqlConformanceBackendTest
