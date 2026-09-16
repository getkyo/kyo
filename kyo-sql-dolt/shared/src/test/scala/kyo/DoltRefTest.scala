package kyo

/** Pins the text each ref renders to, which is the one place a ref becomes something the server parses.
  *
  * Exact strings rather than structural checks: a ref that renders slightly differently resolves to a different commit or to none, and
  * nothing downstream would catch it.
  */
class DoltRefTest extends Test:

    "HEAD resolves against the session rather than naming a commit" in {
        assert(DoltRef.Head.render == "HEAD")
    }

    "a branch and a tag both render as their bare name" in {
        assert(DoltRef.Branch("feature").render == "feature")
        assert(DoltRef.Tag("v1").render == "v1")
    }

    // They render alike because the server holds them in one namespace.
    "a branch and a tag with the same name render identically" in {
        assert(DoltRef.Branch("release").render == DoltRef.Tag("release").render)
    }

    "a commit renders as its hash" in {
        assert(DoltRef.Commit(DoltCommitHash("28ht3guqdi6hsefqgev7tnahp192cmu1")).render == "28ht3guqdi6hsefqgev7tnahp192cmu1")
    }

    "an ancestor appends the tilde generation count" in {
        assert(DoltRef.Ancestor(DoltRef.Branch("main"), 1).render == "main~1")
        assert(DoltRef.Ancestor(DoltRef.Head, 3).render == "HEAD~3")
    }

    // Zero generations back IS the ref itself, and a negative count means the same rather than producing `main~-2`,
    // which the server would refuse.
    "a non-positive generation count renders as the ref itself" in {
        assert(DoltRef.Ancestor(DoltRef.Branch("main"), 0).render == "main")
        assert(DoltRef.Ancestor(DoltRef.Branch("main"), -2).render == "main")
    }

    "ancestors nest, so each step walks one more generation back" in {
        val twoSteps = DoltRef.Ancestor(DoltRef.Ancestor(DoltRef.Branch("main"), 1), 2)
        assert(twoSteps.render == "main~1~2")
    }

end DoltRefTest
