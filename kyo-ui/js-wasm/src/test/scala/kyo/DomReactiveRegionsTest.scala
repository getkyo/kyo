package kyo

import kyo.internal.DomReactiveRegions
import org.scalajs.dom

class DomReactiveRegionsTest extends kyo.test.Test[Any]:

    DomTestEnv.install

    override def config = super.config.sequential

    private val One   = "r000000010031"
    private val Two   = "r000000010032"
    private val Outer = "r00000001006f"
    private val Old   = "r000000010064"
    private val New   = "r00000001006e"

    private def host(html: String): dom.Element =
        val root = dom.document.createElement("div")
        root.innerHTML = html
        root
    end host

    private def initResult(html: String)(using Frame): Result[Any, DomReactiveRegions] < Async =
        Fiber.initUnscoped(Scope.run(DomReactiveRegions.init(host(html)))).map(_.getResult)

    private def assertPanicContains[E, A](result: Result[E, A], expected: String)(using kyo.test.AssertScope): Unit =
        result match
            case Result.Panic(error) => assert(error.getMessage.contains(expected))
            case other               => fail(s"Expected panic containing '$expected', got: $other")

    "initial scan records nested live ranges" in {
        val root = host(s"<!--kyo-rs:$One--><div><!--kyo-rs:$Two-->x<!--kyo-re:$Two--></div><!--kyo-re:$One-->")
        Scope.run {
            for
                regions <- DomReactiveRegions.init(root)
                size    <- regions.size
            yield assert(size == 2)
        }
    }

    "initial scan rejects duplicate ids" in {
        initResult(s"<!--kyo-rs:$One--><!--kyo-re:$One--><!--kyo-rs:$One--><!--kyo-re:$One-->")
            .map(result => assertPanicContains(result, s"Duplicate reactive range id: $One"))
    }

    "initial scan rejects malformed ids" in {
        initResult("<!--kyo-rs:r1--><!--kyo-re:r1-->")
            .map(result => assertPanicContains(result, "Malformed reactive range id: r1"))
    }

    "initial scan rejects missing ends" in {
        initResult(s"<!--kyo-rs:$One--><span>x</span>")
            .map(result => assertPanicContains(result, s"Reactive range start marker has no end: $One"))
    }

    "initial scan rejects crossed pairs" in {
        initResult(s"<!--kyo-rs:$One--><!--kyo-rs:$Two--><!--kyo-re:$One--><!--kyo-re:$Two-->")
            .map(result => assertPanicContains(result, s"Crossed reactive ranges: expected $Two, found $One"))
    }

    "initial scan rejects non-sibling anchors" in {
        initResult(s"<!--kyo-rs:$One--><span><!--kyo-re:$One--></span>")
            .map(result => assertPanicContains(result, s"Reactive range anchors are not siblings: $One"))
    }

    "replacement uses the actual table parent context and retains anchors" in {
        val root = host(s"<table><tbody><!--kyo-rs:$One--><tr id='old'><td>old</td></tr><!--kyo-re:$One--></tbody></table>")
        Scope.run {
            for
                regions <- DomReactiveRegions.init(root)
                _       <- regions.replace(One, "<tr id='new'><td>new</td></tr>")
                size    <- regions.size
            yield
                val tbody = root.querySelector("tbody")
                assert(tbody.children.length == 1)
                assert(tbody.children(0).asInstanceOf[dom.Element].tagName == "TR")
                assert(root.querySelector("#new") != null)
                assert(root.innerHTML.contains(s"kyo-rs:$One"))
                assert(root.innerHTML.contains(s"kyo-re:$One"))
                assert(size == 1)
        }
    }

    "table range transitions between rows and an authored section without losing attributes" in {
        val root = host(
            s"<table><tbody data-kyo-range-host='$One'><!--kyo-rs:$One--><tr id='row'><td>row</td></tr><!--kyo-re:$One--></tbody></table>"
        )
        Scope.run {
            for
                regions <- DomReactiveRegions.init(root)
                _ <- regions.replace(
                    One,
                    "<tbody id='authored' class='section'><tr id='section-row'><td>section</td></tr></tbody>"
                )
                authored <- Sync.defer {
                    val section = root.querySelector("table > tbody")
                    val walker  = dom.document.createTreeWalker(root, 128, null, false)
                    val anchors = Iterator
                        .continually(walker.nextNode())
                        .takeWhile(_ != null)
                        .filter(_.nodeValue.endsWith(One))
                        .map(_.parentNode)
                        .toSeq
                    (
                        id = section.id,
                        className = section.getAttribute("class"),
                        rangeHost = section.getAttribute("data-kyo-range-host"),
                        anchorParents = anchors
                    )
                }
                _ <- regions.replaceWith(
                    One,
                    s"<tbody data-kyo-range-host='$One'><tr id='new-row'><td>new</td></tr></tbody>"
                )((_, _, _) => false) { (oldRoots, newRoots) =>
                    assert(oldRoots.map(_.id) == Seq("authored"))
                    assert(newRoots.map(_.id) == Seq("new-row"))
                } { (_, insertedRoots) =>
                    assert(insertedRoots.map(_.id) == Seq("new-row"))
                }
            yield
                assert(authored.id == "authored")
                assert(authored.className == "section")
                assert(authored.rangeHost == null)
                assert(authored.anchorParents.size == 2)
                assert(authored.anchorParents.forall(_ eq root.querySelector("table")))
                assert(root.querySelector("#section-row") == null)
                val rows = root.querySelectorAll("table > tbody")
                assert(rows.length == 1)
                val current = rows(0).asInstanceOf[dom.Element]
                assert(current.id == "")
                assert(!current.hasAttribute("class"))
                assert(current.getAttribute("data-kyo-range-host") == One)
                assert(current.querySelector("#new-row") != null)
                assert(current.innerHTML.contains(s"kyo-rs:$One"))
                assert(current.innerHTML.contains(s"kyo-re:$One"))
        }
    }

    "table range moves anchors out for multiple authored sections and back into a row host" in {
        val root = host(
            s"<table><tbody data-kyo-range-host='$One'><!--kyo-rs:$One--><tr><td>row</td></tr><!--kyo-re:$One--></tbody></table>"
        )
        Scope.run {
            for
                regions <- DomReactiveRegions.init(root)
                _ <- regions.replace(
                    One,
                    "<tbody id='first'><tr><td>one</td></tr></tbody><tbody id='second'><tr><td>two</td></tr></tbody>"
                )
                authoredCount <- Sync.defer(root.querySelectorAll("table > tbody").length)
                _ <- regions.replace(
                    One,
                    s"<tbody data-kyo-range-host='$One'><tr id='returned'><td>row</td></tr></tbody>"
                )
            yield
                assert(authoredCount == 2)
                val table = root.querySelector("table")
                assert(table.children.length == 1)
                val current = table.children(0).asInstanceOf[dom.Element]
                assert(current.getAttribute("data-kyo-range-host") == One)
                assert(current.querySelector("#returned") != null)
                assert(current.innerHTML.contains(s"kyo-rs:$One"))
                assert(current.innerHTML.contains(s"kyo-re:$One"))
        }
    }

    "replacement uses the actual select parent context" in {
        val root = host(s"<select><!--kyo-rs:$One--><option id='old'>old</option><!--kyo-re:$One--></select>")
        Scope.run {
            for
                regions <- DomReactiveRegions.init(root)
                _       <- regions.replace(One, "<option id='new'>new</option>")
            yield
                val select = root.querySelector("select")
                assert(select.children.length == 1)
                assert(select.children(0).asInstanceOf[dom.Element].tagName == "OPTION")
                assert(root.querySelector("#new") != null)
        }
    }

    "outer replacement unregisters removed nested ranges and registers new nested ranges" in {
        val root = host(
            s"<!--kyo-rs:$Outer--><div><!--kyo-rs:$Old-->old<!--kyo-re:$Old--></div><!--kyo-re:$Outer-->"
        )
        Scope.run {
            for
                regions <- DomReactiveRegions.init(root)
                _ <- regions.replace(
                    Outer,
                    s"<section><!--kyo-rs:$New-->new<!--kyo-re:$New--></section>"
                )
                size     <- regions.size
                hasOuter <- regions.contains(Outer)
                hasOld   <- regions.contains(Old)
                hasNew   <- regions.contains(New)
            yield
                assert(size == 2)
                assert(hasOuter)
                assert(!hasOld)
                assert(hasNew)
        }
    }

    "malformed incoming markers reject before mutating the live range" in {
        val root   = host(s"<!--kyo-rs:$One--><span id='kept'>kept</span><!--kyo-re:$One-->")
        val before = root.innerHTML
        Scope.run {
            for
                regions <- DomReactiveRegions.init(root)
                result  <- Fiber.initUnscoped(regions.replace(One, s"<!--kyo-rs:$Two--><b>broken</b>")).map(_.getResult)
                size    <- regions.size
            yield
                result match
                    case Result.Panic(error) => assert(error.getMessage.contains(s"Reactive range start marker has no end: $Two"))
                    case other               => fail(s"Expected malformed replacement panic, got: $other")
                assert(root.innerHTML == before)
                assert(size == 1)
        }
    }

    "unknown replacement reports the exact diagnostic without mutation" in {
        val root   = host(s"<!--kyo-rs:$One--><span id='kept-unknown'>kept</span><!--kyo-re:$One-->")
        val before = root.innerHTML
        Scope.run {
            for
                regions <- DomReactiveRegions.init(root)
                result  <- Fiber.initUnscoped(regions.replace(Two, "changed")).map(_.getResult)
            yield
                // KyoException.getMessage decorates the diagnostic with environment-aware formatting, so the
                // full diagnostic (including the region id) is asserted as a substring, never by equality.
                assertPanicContains(result, s"Unknown reactive range: $Two")
                assert(root.innerHTML == before)
        }
    }

    "replacement rejects corrupted and reordered live anchors before mutation" in {
        val corrupted = host(s"<!--kyo-rs:$One--><span>kept</span><!--kyo-re:$One-->")
        val reordered = host(s"<!--kyo-rs:$One--><span>kept</span><!--kyo-re:$One-->")
        Scope.run {
            for
                corruptedRegions <- DomReactiveRegions.init(corrupted)
                corruptedEnd = corrupted.lastChild.asInstanceOf[dom.Comment]
                _                <- Sync.defer(corruptedEnd.data = s"kyo-re:$Two")
                corruptedBefore  <- Sync.defer(corrupted.innerHTML)
                corruptedResult  <- Fiber.initUnscoped(corruptedRegions.replace(One, "new")).map(_.getResult)
                reorderedRegions <- DomReactiveRegions.init(reordered)
                reorderedStart = reordered.firstChild
                reorderedEnd   = reordered.lastChild
                _               <- Sync.defer(discard(reordered.insertBefore(reorderedEnd, reorderedStart)))
                reorderedBefore <- Sync.defer(reordered.innerHTML)
                reorderedResult <- Fiber.initUnscoped(reorderedRegions.replace(One, "new")).map(_.getResult)
            yield
                assertPanicContains(corruptedResult, "Reactive range markers are corrupted")
                assertPanicContains(reorderedResult, "Reactive range end is not after its start")
                assert(corrupted.innerHTML == corruptedBefore)
                assert(reordered.innerHTML == reorderedBefore)
        }
    }

    "scope close clears the registry and later replacement rejects" in {
        val root = host(s"<!--kyo-rs:$One-->x<!--kyo-re:$One-->")
        for
            saved <- AtomicRef.init(Absent: Maybe[DomReactiveRegions])
            _ <- Scope.run {
                DomReactiveRegions.init(root).map(regions => saved.set(Present(regions)))
            }
            regions <- saved.get.map(_.get)
            size    <- regions.size
            result  <- Fiber.initUnscoped(regions.replace(One, "later")).map(_.getResult)
        yield
            assert(size == 0)
            result match
                case Result.Panic(error) => assert(error.getMessage.contains("Reactive range registry is closed"))
                case other               => fail(s"Expected closed-registry panic, got: $other")
        end for
    }

end DomReactiveRegionsTest
