package kyo

class UIBaseCssTest extends Test:

    "UI.baseCss is non-empty and contains box-sizing: border-box" in {
        assert(UI.baseCss.nonEmpty)
        assert(UI.baseCss.contains("box-sizing: border-box"))
    }

    "UI.baseCss contains the data-kyo-reactive display:contents rule" in {
        assert(UI.baseCss.contains("[data-kyo-reactive]"))
        assert(UI.baseCss.contains("display: contents"))
    }

    "runRenderPage output contains UI.baseCss verbatim" in run {
        for
            frames <- UI.runRenderPage(UI.PageHead(title = "t"))(UI.div).take(1).run
            html = frames.headMaybe.getOrElse("")
        yield
            // baseCss must appear inside the document
            assert(html.contains(UI.baseCss.take(30)))
        end for
    }

    "UI.baseCss returns the same string on repeated reads (stable val)" in {
        val s1 = UI.baseCss
        val s2 = UI.baseCss
        assert(s1 == s2)
        assert(s1 eq s2) // same reference
    }

end UIBaseCssTest
