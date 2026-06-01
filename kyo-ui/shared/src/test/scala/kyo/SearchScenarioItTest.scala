package kyo

import kyo.Browser.*
import kyo.UI.foreach
import scala.language.implicitConversions

class SearchScenarioItTest extends UITest:

    "type filters results" in run {
        val app: UI < Async =
            for
                query <- Signal.initRef("")
                items = Chunk.from(Seq("apple", "application", "banana", "cherry"))
            yield UI.div(
                UI.input.id("q").value(query),
                query.map { q =>
                    val filtered = if q.isEmpty then items else items.filter(_.contains(q))
                    UI.span(s"results:${filtered.size}").id("v")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "results:4")
                _ <- Browser.fill(Selector.id("q"), "app")
                _ <- Browser.assertText(Selector.id("v"), "results:2")
                _ <- Browser.fill(Selector.id("q"), "apple")
                _ <- Browser.assertText(Selector.id("v"), "results:1")
            yield succeed
        }
    }

    "clear search shows all" in run {
        val app: UI < Async =
            for query <- Signal.initRef("")
            yield UI.div(
                UI.input.id("q").value(query),
                query.map { q =>
                    val count = if q.isEmpty then 10 else 3
                    UI.span(s"results:$count").id("v")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "results:10")
                _ <- Browser.fill(Selector.id("q"), "x")
                _ <- Browser.assertText(Selector.id("v"), "results:3")
                _ <- Browser.fill(Selector.id("q"), "")
                _ <- Browser.assertText(Selector.id("v"), "results:10")
            yield succeed
        }
    }

    "no results shows message" in run {
        val app: UI < Async =
            for query <- Signal.initRef("")
            yield UI.div(
                UI.input.id("q").value(query),
                query.map { q =>
                    if q == "zzz" then UI.span("No results").id("v")
                    else UI.span("Has results").id("v")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "Has results")
                _ <- Browser.fill(Selector.id("q"), "zzz")
                _ <- Browser.assertText(Selector.id("v"), "No results")
            yield succeed
        }
    }

    "click result selects it" in run {
        val app: UI < Async =
            for
                query    <- Signal.initRef("")
                selected <- Signal.initRef("")
                items = Chunk.from(Seq("apple", "banana"))
            yield UI.div(
                UI.input.id("q").value(query),
                query.map { q =>
                    val filtered = if q.isEmpty then items else items.filter(_.contains(q))
                    UI.div(filtered.toSeq.map(item =>
                        UI.button(item).id(s"r-$item").onClick(selected.set(item).andThen(query.set(item)))
                    )*)
                },
                selected.map(v => UI.span(s"selected:$v").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("r-banana"))
                _ <- Browser.assertText(Selector.id("v"), "selected:banana")
            yield succeed
        }
    }

    "type fast final results correct" in run {
        val app: UI < Async =
            for query <- Signal.initRef("")
            yield UI.div(
                UI.input.id("q").value(query),
                query.map(q => UI.span(s"q:$q").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("q"), "application")
                _ <- Browser.assertText(Selector.id("v"), "q:application")
            yield succeed
        }
    }

    "click clear clears search" in run {
        val app: UI < Async =
            for query <- Signal.initRef("")
            yield UI.div(
                UI.input.id("q").value(query),
                UI.button("Clear").id("clear").onClick(query.set("")),
                query.map(v => UI.span(s"q:[$v]").id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("q"), "something")
                _ <- Browser.assertText(Selector.id("v"), "q:[something]")
                _ <- Browser.click(Selector.id("clear"))
                _ <- Browser.assertText(Selector.id("v"), "q:[]")
            yield succeed
        }
    }

    "empty search shows all items" in run {
        val app: UI < Async =
            for query <- Signal.initRef("")
            yield UI.div(
                UI.input.id("q").value(query),
                query.map { q =>
                    val count = if q.isEmpty then 10 else 2
                    UI.span(s"showing:$count").id("v")
                }
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("v"), "showing:10")
                _ <- Browser.fill(Selector.id("q"), "x")
                _ <- Browser.assertText(Selector.id("v"), "showing:2")
                _ <- Browser.fill(Selector.id("q"), "")
                _ <- Browser.assertText(Selector.id("v"), "showing:10")
            yield succeed
        }
    }

    "search preserves across other interactions" in run {
        val app: UI < Async =
            for
                query   <- Signal.initRef("")
                counter <- Signal.initRef(0)
            yield UI.div(
                UI.input.id("q").value(query),
                UI.button("Inc").id("inc").onClick(counter.getAndUpdate(_ + 1).unit),
                query.map(v => UI.span(s"q:$v").id("vq")),
                counter.map(n => UI.span(s"c:$n").id("vc"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("q"), "search text")
                _ <- Browser.click(Selector.id("inc"))
                _ <- Browser.assertText(Selector.id("vq"), "q:search text")
                _ <- Browser.assertText(Selector.id("vc"), "c:1")
            yield succeed
        }
    }

end SearchScenarioItTest
