package kyo.internal.dolt

import kyo.*

/** Pins how a Dolt database name carries a revision, and how that name reaches a `USE`.
  *
  * The quoting leaf is the one that matters: an unquoted `USE app/feature` is a syntax error rather than a wrong answer, so it would fail
  * every branch scope on the first statement.
  */
class DoltDatabaseTest extends Test:

    "a plain database name carries no revision" in {
        assert(DoltDatabase.split("app") == ("app", Absent))
    }

    "a revision after the slash is split off" in {
        assert(DoltDatabase.split("app/feature") == ("app", Present("feature")))
    }

    "only the first slash separates, and the rest stays in the revision" in {
        assert(DoltDatabase.split("app/feature/extra") == ("app", Present("feature/extra")))
    }

    "an empty revision is still a declared one, because the slash was written" in {
        assert(DoltDatabase.split("app/") == ("app", Present("")))
    }

    "qualify is the inverse of split" in {
        assert(DoltDatabase.qualify("app", "feature") == "app/feature")
        val (db, rev) = DoltDatabase.split(DoltDatabase.qualify("app", "feature"))
        assert(db == "app" && rev == Present("feature"))
    }

    "the USE statement quotes the qualified name, because it contains a slash" in {
        assert(DoltDatabase.useStatement("app", "feature") == "USE `app/feature`")
    }

    "an embedded backtick is doubled rather than ending the quoting" in {
        assert(DoltDatabase.useStatement("a`b", "feature") == "USE `a``b/feature`")
        assert(DoltDatabase.useStatement("app", "fe`ature") == "USE `app/fe``ature`")
    }

end DoltDatabaseTest
