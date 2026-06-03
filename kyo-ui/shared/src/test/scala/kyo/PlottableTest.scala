package kyo

import kyo.internal.Domain
import kyo.internal.Scale

class PlottableTest extends Test:

    // ---- test enum for derivation tests ----
    enum Region derives Plottable:
        case NA, EU, APAC

    // ---- test opaque type for numeric test ----
    opaque type Usd <: Double = Double
    object Usd:
        def apply(d: Double): Usd = d

    "Plottable[Int].kind is Linear" in {
        assert(summon[Plottable[Int]].kind == Scale.Kind.Linear)
    }

    "Plottable[String].kind is Band" in {
        assert(summon[Plottable[String]].kind == Scale.Kind.Band)
    }

    "Plottable[Instant].kind is Time" in {
        assert(summon[Plottable[Instant]].kind == Scale.Kind.Time)
    }

    "enum Region derivation resolves and labels are Chunk(NA,EU,APAC) in order" in {
        val p = summon[Plottable[Region]]
        assert(p.label(Region.NA) == "NA")
        assert(p.label(Region.EU) == "EU")
        assert(p.label(Region.APAC) == "APAC")
        assert(p.toDomain(Region.NA) == Present(Domain.Category("NA")))
        assert(p.toDomain(Region.EU) == Present(Domain.Category("EU")))
        assert(p.toDomain(Region.APAC) == Present(Domain.Category("APAC")))
    }

    "two summons of Plottable[Region] are reference-equal (caching guard)" in {
        val p1 = summon[Plottable[Region]]
        val p2 = summon[Plottable[Region]]
        assert(p1 eq p2)
    }

    "two summons of Plottable[Region] from distinct call sites are reference-equal" in {
        def site1(): Plottable[Region] = summon[Plottable[Region]]
        def site2(): Plottable[Region] = summon[Plottable[Region]]
        assert(site1() eq site2())
    }

    "Plottable.numeric[Usd].toDomain(Usd(10.0)) equals Present(Continuous(10.0))" in {
        val p: Plottable[Usd] = Plottable.numeric[Usd]
        assert(p.toDomain(Usd(10.0)) == Present(Domain.Continuous(10.0)))
    }

    "Plottable[Maybe[Int]] kind equals inner kind (Linear)" in {
        val p = summon[Plottable[Maybe[Int]]]
        assert(p.kind == Scale.Kind.Linear)
    }

    "Plottable[Maybe[Int]] Present(42) projects to Present(Continuous(42.0))" in {
        val p = summon[Plottable[Maybe[Int]]]
        assert(p.toDomain(Present(42)) == Present(Domain.Continuous(42.0)))
    }

    "Plottable[Maybe[Int]] Absent yields Absent (no domain contribution)" in {
        val p = summon[Plottable[Maybe[Int]]]
        assert(p.toDomain(Absent) == Absent)
    }

    "Plottable[Maybe[Int]] all-Absent column yields empty extent (no Continuous contributions)" in {
        val p      = summon[Plottable[Maybe[Int]]]
        val values = Chunk[Maybe[Int]](Absent, Absent, Absent)
        // Folding all-Absent values through toDomain must produce no Present entries.
        val domains = values.flatMap(v => p.toDomain(v).toList)
        assert(domains.isEmpty)
    }

    "summon[Plottable[Boolean]] does not compile" in {
        typeCheckFailure("""
          import kyo.*
          summon[Plottable[Boolean]]
        """)("No given instance")
    }

end PlottableTest
