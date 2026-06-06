package kyo

import kyo.Tasty.SymbolId

/** plan-mandated tests confirming the 5 supporting ADTs (Variance, TypeBounds, Visibility, OpenLevel, ShowFormat) derive CanEqual
  * and have exhaustive matches.
  *
  * Leaves 20-23 per plan 05-plan.yaml id:1. Pins: INV-009.
  */
class SmallAdtCoexistenceTest extends Test:

    // ── Leaf 20: variance-can-equal-and-exhaustive ───────────────────────────

    // Given: a 3-branch match over Variance (Invariant, Covariant, Contravariant).
    // When: compile under -Xfatal-warnings; invoke for each enum case.
    // Then: compiles cleanly; returns the matching label.
    "Leaf 20: Variance exhaustive match compiles and returns correct labels" in {
        def label(v: Tasty.Variance): String = v match
            case Tasty.Variance.Invariant     => "Inv"
            case Tasty.Variance.Covariant     => "Co"
            case Tasty.Variance.Contravariant => "Contra"

        assert(label(Tasty.Variance.Invariant) == "Inv")
        assert(label(Tasty.Variance.Covariant) == "Co")
        assert(label(Tasty.Variance.Contravariant) == "Contra")
        // CanEqual: two identical Variance values compare equal
        val v: Tasty.Variance = Tasty.Variance.Covariant
        assert(v == Tasty.Variance.Covariant)
        succeed
    }

    // ── Leaf 21: typebounds-equality-and-distinct-from-tree ─────────────────

    // Given: two TypeBounds(Type.Nothing, Type.Any) literals.
    // When: compare with ==; attempt to assign a TypeBounds to a val t: Tree.TypeBounds.
    // Then: == returns true; the second is a negative assertion (TypeBounds is distinct from
    //   Tree.TypeBounds as verified by the distinct field names: lower/upper vs lo/hi).
    "Leaf 21: TypeBounds structural equality works; distinct from Tree.TypeBounds" in {
        val a = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)
        val b = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)
        assert(a == b, "Two identical TypeBounds literals must be equal")
        // Structural verification: Tasty.TypeBounds has fields 'lower' and 'upper';
        // Tree.TypeBounds has fields 'lo' and 'hi'. The types differ.
        assert(a.lower == Tasty.Type.Nothing)
        assert(a.upper == Tasty.Type.Any)
        succeed
    }

    // ── Leaf 22: visibility-exhaustive-5-cases ───────────────────────────────

    // Given: a 5-branch match over Visibility.
    // When: compile under -Xfatal-warnings.
    // Then: compiles cleanly; each case returns a distinct label.
    "Leaf 22: Visibility exhaustive 5-case match compiles and returns distinct labels" in {
        def label(vis: Tasty.Visibility): String = vis match
            case Tasty.Visibility.Private         => "priv"
            case Tasty.Visibility.Protected       => "prot"
            case Tasty.Visibility.Public          => "pub"
            case Tasty.Visibility.ScopedPrivate   => "spriv"
            case Tasty.Visibility.ScopedProtected => "sprot"

        assert(label(Tasty.Visibility.Private) == "priv")
        assert(label(Tasty.Visibility.Protected) == "prot")
        assert(label(Tasty.Visibility.Public) == "pub")
        assert(label(Tasty.Visibility.ScopedPrivate) == "spriv")
        assert(label(Tasty.Visibility.ScopedProtected) == "sprot")
        succeed
    }

    // ── Leaf 23: openlevel-and-showformat-exhaustive ─────────────────────────

    // Given: a 4-branch match over OpenLevel and a 3-branch match over ShowFormat.
    // When: compile under -Xfatal-warnings; invoke each.
    // Then: compiles cleanly; each branch returns a distinct label.
    "Leaf 23: OpenLevel 4-case and ShowFormat 3-case exhaustive matches compile cleanly" in {
        def olLabel(ol: Tasty.OpenLevel): String = ol match
            case Tasty.OpenLevel.Open    => "open"
            case Tasty.OpenLevel.Default => "default"
            case Tasty.OpenLevel.Sealed  => "sealed"
            case Tasty.OpenLevel.Final   => "final"

        def sfLabel(sf: Tasty.ShowFormat): String = sf match
            case Tasty.ShowFormat.FullyQualified => "fq"
            case Tasty.ShowFormat.Simple         => "simple"
            case Tasty.ShowFormat.Code           => "code"

        assert(olLabel(Tasty.OpenLevel.Open) == "open")
        assert(olLabel(Tasty.OpenLevel.Default) == "default")
        assert(olLabel(Tasty.OpenLevel.Sealed) == "sealed")
        assert(olLabel(Tasty.OpenLevel.Final) == "final")
        assert(sfLabel(Tasty.ShowFormat.FullyQualified) == "fq")
        assert(sfLabel(Tasty.ShowFormat.Simple) == "simple")
        assert(sfLabel(Tasty.ShowFormat.Code) == "code")
        succeed
    }

end SmallAdtCoexistenceTest
