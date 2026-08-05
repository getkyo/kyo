package kyo

import kyo.db.Idiom

class IdiomIdTest extends Test:

    "idEqualityFollowsTheWrappedString" in {
        assert(Idiom.Id("postgres") == Idiom.Id("postgres"))
        assert(Idiom.Id("postgres") != Idiom.Id("mysql"))
    }

    "idExposesTheWrappedString" in {
        assert(Idiom.Id("postgres").value == "postgres")
        assert(Idiom.Id("").value == "")
    }

    "serverVersionRoundTripsItsTriple" in {
        val v = Idiom.ServerVersion(1, 2, 3)
        assert(v.major == 1)
        assert(v.minor == 2)
        assert(v.patch == 3)
        assert(v.show == "1.2.3")
    }

    "serverVersionRoundTripsAWideTriple" in {
        val v = Idiom.ServerVersion(1048575, 1048575, 1048575)
        assert(v.major == 1048575)
        assert(v.minor == 1048575)
        assert(v.patch == 1048575)
        assert(v.show == "1048575.1048575.1048575")
    }

    "serverVersionEqualityFollowsTheTriple" in {
        assert(Idiom.ServerVersion(8, 0, 31) == Idiom.ServerVersion(8, 0, 31))
        assert(Idiom.ServerVersion(8, 0, 31) != Idiom.ServerVersion(8, 0, 30))
        assert(Idiom.ServerVersion(8, 0, 31) != Idiom.ServerVersion(8, 31, 0))
        assert(Idiom.ServerVersion(8, 0, 0) != Idiom.ServerVersion(0, 0, 8))
    }

    // The packing exists so that a version-gate check is a single comparison. These pin that the packed order matches the triple order,
    // which is the property every supports* predicate will rely on.
    "serverVersionOrderingFollowsTheTriple" in {
        assert(Idiom.ServerVersion(8, 0, 30).below(Idiom.ServerVersion(8, 0, 31)))
        assert(Idiom.ServerVersion(8, 0, 31).below(Idiom.ServerVersion(8, 1, 0)))
        assert(Idiom.ServerVersion(8, 4, 0).below(Idiom.ServerVersion(9, 0, 0)))
        assert(Idiom.ServerVersion(5, 7, 44).below(Idiom.ServerVersion(8, 0, 14)))
    }

    "serverVersionOrderingDoesNotCarryBetweenComponents" in {
        assert(Idiom.ServerVersion(8, 0, 1048575).below(Idiom.ServerVersion(8, 1, 0)))
        assert(Idiom.ServerVersion(0, 1048575, 1048575).below(Idiom.ServerVersion(1, 0, 0)))
    }

    // atLeast is the shape a version gate takes: MySQL renders INTERSECT from 8.0.31 onwards.
    "serverVersionAtLeastIsInclusiveAtTheGate" in {
        val gate = Idiom.ServerVersion(8, 0, 31)
        assert(Idiom.ServerVersion(8, 0, 31).atLeast(gate))
        assert(Idiom.ServerVersion(8, 0, 32).atLeast(gate))
        assert(Idiom.ServerVersion(9, 0, 0).atLeast(gate))
        assert(!Idiom.ServerVersion(8, 0, 30).atLeast(gate))
        assert(!Idiom.ServerVersion(5, 7, 44).atLeast(gate))
    }

end IdiomIdTest
