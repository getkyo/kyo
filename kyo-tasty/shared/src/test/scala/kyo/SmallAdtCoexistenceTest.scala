package kyo

import kyo.Tasty.SymbolId

/** Confirms the supporting ADTs (Variance, TypeBounds, Visibility, OpenLevel, ShowFormat) derive
  * CanEqual and have exhaustive matches.
  */
class SmallAdtCoexistenceTest extends kyo.test.Test[Any]:

    "Variance exhaustive match compiles and returns correct labels" in {
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

    // Tasty.TypeBounds has fields 'lower' and 'upper'; Tree.TypeBounds has fields 'lo' and 'hi'.
    "TypeBounds structural equality works; distinct from Tree.TypeBounds" in {
        val a = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)
        val b = Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)
        assert(a == b, "Two identical TypeBounds literals must be equal")
        // Structural verification: Tasty.TypeBounds has fields 'lower' and 'upper';
        // Tree.TypeBounds has fields 'lo' and 'hi'. The types differ.
        assert(a.lower == Tasty.Type.Nothing)
        assert(a.upper == Tasty.Type.Any)
        succeed
    }

    "Visibility exhaustive 5-case match compiles and returns distinct labels" in {
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

    "OpenLevel 4-case and ShowFormat 3-case exhaustive matches compile cleanly" in {
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
