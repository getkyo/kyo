package kyo

import kyo.Sql.*

/** Cross-backend conformance for `SqlClient.serverVersion`: the version a client reads at handshake parses to a well-formed triple,
  * sits at or above the connected dialect's own capability floor, and is actually the value a version-gated construct on the typed DSL
  * consults.
  *
  * The per-backend claim is what needs a battery of its own: that `serverVersion` parses correctly on each engine, and that it is the value
  * a version gate actually reads. Every leaf runs through [[SqlBackendTest.forEachBackend]] against whatever backend descriptors are
  * discovered, so a third backend that registers a descriptor gets these leaves for free with no change here.
  *
  * The third leaf's gate, `client.dialect.valuesConstructorSince`, is read off `client.dialect` (an `Idiom`) rather than a hardcoded
  * literal, so the leaf branches on a capability the connected dialect names, never on an engine. One shipping dialect gates the VALUES
  * source (`Present`, a real floor to clear) and the other never has (`Absent`, the default meaning every version renders it); both
  * shapes are exercised by the same leaf without naming either engine.
  */
class SqlServerVersionConformanceTest extends SqlBackendTest:

    case class Point(x: Int, y: Int) derives SqlSchema, CanEqual

    "serverVersion parses to a well-formed major.minor.patch triple" - {
        forEachBackend() { (_, client, _) =>
            client.serverVersion.map { version =>
                assert(version.major > 0, s"expected a real major version, got '${version.show}'")
                assert(
                    version.show.matches("""\d+\.\d+\.\d+"""),
                    s"expected a non-empty major.minor.patch triple, got '${version.show}'"
                )
            }
        }
    }

    "serverVersion is at or above the connected dialect's own capability floor" - {
        forEachBackend() { (_, client, _) =>
            client.serverVersion.map { version =>
                val floor = client.dialect.capabilityFloor
                assert(
                    version.atLeast(floor),
                    s"the running server ${version.show} must be at or above ${client.dialect.id.value}'s capability floor ${floor.show}"
                )
            }
        }
    }

    "serverVersion is the value a version-gated construct on the typed DSL consults" - {
        forEachBackend() { (_, client, _) =>
            client.serverVersion.flatMap { version =>
                client.dialect.valuesConstructorSince.foreach { gate =>
                    assert(
                        version.atLeast(gate),
                        s"the running server ${version.show} does not clear ${client.dialect.id.value}'s VALUES-source gate " +
                            s"${gate.show}, so the render below would abort with SqlUnsupportedDialectFeatureException rather than run"
                    )
                }
                Sql.values[Point]("v", Point(1, 2), Point(3, 4)).run.map { rows =>
                    assert(rows.size == 2, s"a two-row VALUES source must return two rows, got ${rows.size}")
                    assert(rows.head == Point(1, 2), s"expected Point(1,2), got ${rows.head}")
                    assert(rows(1) == Point(3, 4), s"expected Point(3,4), got ${rows(1)}")
                }
            }
        }
    }

end SqlServerVersionConformanceTest
