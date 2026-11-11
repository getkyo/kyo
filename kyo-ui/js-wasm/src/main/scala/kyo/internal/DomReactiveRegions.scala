package kyo.internal

import kyo.*
import org.scalajs.dom
import scala.collection.mutable
import scala.scalajs.js

/** Mount-scoped registry of live HTML reactive range anchors. */
final private[kyo] class DomReactiveRegions private (
    private val document: dom.Document,
    private val ranges: mutable.HashMap[String, DomReactiveRegions.Endpoints],
    private val inserted: mutable.HashMap[String, String]
):

    private var open = true

    def replace(regionId: String, html: String)(using Frame): Unit < Sync =
        replaceWith(regionId, html, keepCurrent = false)((_, _, _) => false)((_, _) => ())((_, _) => ())

    /** Replaces the range's content with `html`, morphing where `tryMorph` accepts.
      *
      * `keepCurrent` marks `html` as the range's first value since its markup was inserted. Such a value is kept, with
      * no node touched, when its serialized markup equals the serialization of the range's markup as it was inserted.
      * The comparison is against the inserted markup, not the live nodes, because the live nodes are rewritten once
      * inserted. It covers markup only (structure, attributes, text): live property state such as an input's typed value
      * is deliberately left alone.
      *
      * Later values also retain nodes when the live content already matches, including form properties. An edited field
      * therefore still gets repaired even when its attributes match the replacement markup.
      */
    private[kyo] def replaceWith[A](regionId: String, html: String, keepCurrent: Boolean)(
        tryMorph: (Seq[dom.Element], Seq[dom.Element], Boolean) => Boolean
    )(
        before: (Seq[dom.Element], Seq[dom.Element]) => A
    )(
        after: (A, Seq[dom.Element]) => Unit
    )(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            // Unsafe: the range operation mutates the document and its scoped registry.
            ensureOpen()
            if !ReactiveRegion.isValidHtmlId(regionId) then fail(s"Malformed reactive range id: $regionId")
            val endpoints = ranges.getOrElse(regionId, fail(s"Unknown reactive range: $regionId"))
            val parent    = validatedParent(regionId, endpoints)
            val range     = document.createRange()
            range.setStartAfter(endpoints.start)
            range.setEndBefore(endpoints.end)

            val liveHost = parent match
                case element: dom.Element
                    if element.tagName == "TBODY" && element.getAttribute("data-kyo-range-host") == regionId =>
                    val table = DomReactiveRegions.parent(element).getOrElse(fail(s"Reactive table range host is detached: $regionId"))
                    DomReactiveRegions.LiveHost.Synthetic(element, table)
                case _ => DomReactiveRegions.LiveHost.Siblings(parent)
            val fragment = liveHost match
                case DomReactiveRegions.LiveHost.Synthetic(host, _) =>
                    val parser = document.createRange()
                    parser.selectNode(host)
                    parser.createContextualFragment(html)
                case DomReactiveRegions.LiveHost.Siblings(_) =>
                    range.createContextualFragment(html)
            val incomingContent = classifyIncoming(regionId, fragment)
            val unchanged       = keepCurrent &&
                (Maybe.fromOption(inserted.remove(regionId)) match
                    case Present(recorded) =>
                        (liveHost, incomingContent) match
                            case (DomReactiveRegions.LiveHost.Siblings(_), DomReactiveRegions.IncomingContent.Semantic(_)) =>
                                recorded == serialize(fragment.cloneNode(true))
                            case _ => false
                    case Absent => false)
            if !unchanged then
                replaceContent(regionId, endpoints, range, liveHost, fragment, incomingContent)(tryMorph)(before)(after)
        }
    end replaceWith

    private def serialize(content: dom.Node): String =
        val probe = document.createElement("div")
        discard(probe.appendChild(content))
        probe.innerHTML
    end serialize

    private def serializeBetween(endpoints: DomReactiveRegions.Endpoints): String =
        val content = document.createDocumentFragment()
        var current = DomReactiveRegions.next(endpoints.start)
        while current.nonEmpty && (current.get ne endpoints.end) do
            discard(content.appendChild(current.get.cloneNode(true)))
            current = DomReactiveRegions.next(current.get)
        end while
        serialize(content)
    end serializeBetween

    private def recordInserted(): Unit =
        ranges.foreach((id, endpoints) => inserted(id) = serializeBetween(endpoints))

    private def replaceContent[A](
        regionId: String,
        endpoints: DomReactiveRegions.Endpoints,
        range: dom.Range,
        liveHost: DomReactiveRegions.LiveHost,
        fragment: dom.DocumentFragment,
        incomingContent: DomReactiveRegions.IncomingContent
    )(
        tryMorph: (Seq[dom.Element], Seq[dom.Element], Boolean) => Boolean
    )(
        before: (Seq[dom.Element], Seq[dom.Element]) => A
    )(
        after: (A, Seq[dom.Element]) => Unit
    )(using Frame, AllowUnsafe): Unit =
        val incoming = DomReactiveRegions.scan(document, fragment)
        val removed  = ranges.iterator.collect {
            case (id, nested) if id != regionId && intersects(range, nested.start) => id
        }.toSet

        incoming.keysIterator.foreach { id =>
            if ranges.contains(id) && !removed.contains(id) then
                fail(s"Duplicate reactive range id: $id")
        }

        val oldElements = elementsBetween(endpoints)
        val newElements = incomingContent.semanticRoots
        val unchanged   = (liveHost, incomingContent) match
            case (DomReactiveRegions.LiveHost.Synthetic(host, _), DomReactiveRegions.IncomingContent.Synthetic(fresh, _)) =>
                host.cloneNode(false).isEqualNode(fresh.cloneNode(false)) && sameElementState(host, fresh) && sameContent(endpoints, fresh)
            case (DomReactiveRegions.LiveHost.Siblings(_), DomReactiveRegions.IncomingContent.Semantic(_)) =>
                sameContent(endpoints, fragment)
            case _ => false
        if !unchanged && !tryMorph(oldElements, newElements, incoming.isEmpty) then
            val incomingMarkup = incoming.iterator.map((id, nested) => id -> serializeBetween(nested)).toSeq
            val state          = before(oldElements, newElements)
            // A <select>'s value is its own state: new option elements reset the selection to the first, so it is kept across the swap.
            val select = liveHost match
                case DomReactiveRegions.LiveHost.Siblings(parent: dom.Element) =>
                    // closest("select") names an HTML select in this HTML range.
                    Maybe(parent.closest("select")).map(_.asInstanceOf[dom.html.Select]).filter(_.selectedIndex >= 0)
                case _ => Absent
            val selections = select.toList.map(element => (element, element.value, selectedDefaultValue(element))) ++
                replacedSelections(oldElements, newElements)
            range.deleteContents()
            removed.foreach { id =>
                discard(ranges.remove(id))
                discard(inserted.remove(id))
            }
            val insertedRoots = (liveHost, incomingContent) match
                case (
                        DomReactiveRegions.LiveHost.Synthetic(host, _),
                        DomReactiveRegions.IncomingContent.Synthetic(incomingHost, _)
                    ) =>
                    syncHostAttributes(host, incomingHost, regionId)
                    var child = DomReactiveRegions.firstChild(incomingHost)
                    while child.nonEmpty do
                        val next = DomReactiveRegions.next(child.get)
                        discard(host.insertBefore(child.get, endpoints.end))
                        child = next
                    end while
                    elementsBetween(endpoints)
                case (
                        DomReactiveRegions.LiveHost.Synthetic(host, table),
                        DomReactiveRegions.IncomingContent.Semantic(roots)
                    ) =>
                    discard(table.insertBefore(endpoints.start, host))
                    discard(table.insertBefore(fragment, host))
                    discard(table.insertBefore(endpoints.end, host))
                    discard(table.removeChild(host))
                    roots
                case (
                        DomReactiveRegions.LiveHost.Siblings(parent),
                        DomReactiveRegions.IncomingContent.Synthetic(host, roots)
                    ) =>
                    discard(parent.insertBefore(fragment, endpoints.end))
                    DomReactiveRegions.firstChild(host) match
                        case Present(first) => discard(host.insertBefore(endpoints.start, first))
                        case Absent         => discard(host.appendChild(endpoints.start))
                    discard(host.appendChild(endpoints.end))
                    roots
                case (
                        DomReactiveRegions.LiveHost.Siblings(parent),
                        DomReactiveRegions.IncomingContent.Semantic(roots)
                    ) =>
                    discard(parent.insertBefore(fragment, endpoints.end))
                    roots
            ranges.addAll(incoming)
            discard(inserted.addAll(incomingMarkup))
            after(state, insertedRoots)
            selections.foreach { (element, value, previousDefault) =>
                if previousDefault == selectedDefaultValue(element) &&
                    (0 until element.options.length).exists(i => element.options(i).value == value)
                then
                    element.value = value
                end if
                if element.value != value then
                    discard(element.dispatchEvent(new dom.CustomEvent(
                        "change",
                        new dom.CustomEventInit:
                            bubbles = true
                            detail = js.Dynamic.literal(kyoSelectPreviousValue = value)
                    )))
                end if
            }
        end if
    end replaceContent

    // A bound select's own observer can replay after its options changed. An unchanged value declaration must not clear the
    // native fallback or undo a live choice when that replay replaces the entire select rather than only its options.
    private def replacedSelections(oldRoots: Seq[dom.Element], newRoots: Seq[dom.Element])(using
        AllowUnsafe
    )
        : Seq[(dom.html.Select, String, Maybe[String])] =
        val previous = selectsIn(oldRoots)
        val incoming = selectsIn(newRoots)
        previous.flatMap { old =>
            Maybe(old.getAttribute("data-kyo-path")).toList.flatMap { path =>
                val matches = incoming.filter(_.getAttribute("data-kyo-path") == path)
                if old.selectedIndex >= 0 && previous.count(_.getAttribute("data-kyo-path") == path) == 1 && matches.size == 1 then
                    val fresh = matches.head
                    if fresh.id == old.id && Maybe(fresh.getAttribute("value")) == Maybe(old.getAttribute("value")) then
                        fresh.removeAttribute("data-kyo-prop-value")
                        Seq((fresh, old.value, selectedDefaultValue(old)))
                    else Seq.empty
                    end if
                else Seq.empty
                end if
            }
        }
    end replacedSelections

    private def selectsIn(roots: Seq[dom.Element])(using AllowUnsafe): Seq[dom.html.Select] =
        roots.flatMap { root =>
            val descendants = root.querySelectorAll("select")
            (Seq(root) ++ (0 until descendants.length).map(descendants(_))).collect {
                // Unsafe: the HTML namespace and SELECT tag determine this DOM interface.
                case element if element.namespaceURI == "http://www.w3.org/1999/xhtml" && element.tagName == "SELECT" =>
                    element.asInstanceOf[dom.html.Select]
            }
        }
    end selectsIn

    private def sameContent(endpoints: DomReactiveRegions.Endpoints, content: dom.Node)(using AllowUnsafe): Boolean =
        @scala.annotation.tailrec
        def loop(left: Maybe[dom.Node], right: Maybe[dom.Node]): Boolean =
            (left, right) match
                case (Present(node), Absent)                                            => node eq endpoints.end
                case (Present(a), Present(b)) if (a ne endpoints.end) && sameNode(a, b) =>
                    loop(DomReactiveRegions.next(a), DomReactiveRegions.next(b))
                case _ => false
        loop(DomReactiveRegions.next(endpoints.start), DomReactiveRegions.firstChild(content))
    end sameContent

    private def sameNode(left: dom.Node, right: dom.Node)(using AllowUnsafe): Boolean =
        if !left.isEqualNode(right) then false
        else
            (left, right) match
                case (a: dom.Element, b: dom.Element) =>
                    var same          = sameElementState(a, b)
                    val leftChildren  = a.querySelectorAll("*")
                    val rightChildren = b.querySelectorAll("*")
                    var index         = 0
                    while same && index < leftChildren.length do
                        same = sameElementState(leftChildren(index), rightChildren(index))
                        index += 1
                    end while
                    same
                case _ => true
    end sameNode

    private def sameElementState(left: dom.Element, right: dom.Element)(using AllowUnsafe): Boolean =
        var index = 0
        while index < left.attributes.length do
            if left.attributes(index).name.startsWith("data-kyo-prop-") then return false
            index += 1
        end while
        if left.namespaceURI != "http://www.w3.org/1999/xhtml" then true
        else
            // Unsafe: isEqualNode established equal namespaces and tag names, which determine both HTML DOM interfaces.
            left.tagName match
                case "INPUT" =>
                    val a = left.asInstanceOf[dom.html.Input]
                    val b = right.asInstanceOf[dom.html.Input]
                    a.value == b.value && a.checked == b.checked && a.indeterminate == b.indeterminate
                case "TEXTAREA" =>
                    left.asInstanceOf[dom.html.TextArea].value == right.asInstanceOf[dom.html.TextArea].value
                case "SELECT" =>
                    left.asInstanceOf[dom.html.Select].selectedIndex == right.asInstanceOf[dom.html.Select].selectedIndex
                case "OPTION" =>
                    left.asInstanceOf[dom.html.Option].selected == right.asInstanceOf[dom.html.Option].selected
                case _ => true
        end if
    end sameElementState

    // The last selected attribute declares a single select's default. A changed declaration must take
    // effect; an unchanged one must not undo a live choice when the option nodes are replaced.
    private def selectedDefaultValue(select: dom.html.Select)(using AllowUnsafe): Maybe[String] =
        var index                = select.options.length - 1
        var value: Maybe[String] = Absent
        while index >= 0 && value.isEmpty do
            val option = select.options(index)
            if option.defaultSelected then value = Present(option.value)
            index -= 1
        end while
        value
    end selectedDefaultValue

    private[kyo] def size(using Frame): Int < Sync =
        Sync.defer(ranges.size)

    private[kyo] def contains(regionId: String)(using Frame): Boolean < Sync =
        Sync.defer(ranges.contains(regionId))

    private[kyo] def close(using Frame): Unit < Sync =
        Sync.defer {
            if open then
                open = false
                ranges.clear()
                inserted.clear()
        }

    private def ensureOpen()(using Frame): Unit =
        if !open then fail("Reactive range registry is closed")

    private def validatedParent(regionId: String, endpoints: DomReactiveRegions.Endpoints)(using Frame): dom.Node =
        if endpoints.start.data != s"${DomReactiveRegions.StartPrefix}$regionId" ||
            endpoints.end.data != s"${DomReactiveRegions.EndPrefix}$regionId"
        then fail(s"Reactive range markers are corrupted: $regionId")
        (DomReactiveRegions.parent(endpoints.start), DomReactiveRegions.parent(endpoints.end)) match
            case (Present(startParent), Present(endParent)) if startParent eq endParent =>
                var current = DomReactiveRegions.next(endpoints.start)
                while current.nonEmpty && (current.get ne endpoints.end) do
                    current = DomReactiveRegions.next(current.get)
                if current.isEmpty then fail(s"Reactive range end is not after its start: $regionId")
                startParent
            case _ => fail(s"Reactive range anchors are no longer siblings: $regionId")
        end match
    end validatedParent

    private def fail(message: String)(using Frame): Nothing =
        throw UIException(message)

    private def intersects(range: dom.Range, node: dom.Node): Boolean =
        range.asInstanceOf[js.Dynamic].intersectsNode(node).asInstanceOf[Boolean]

    private def elementsBetween(endpoints: DomReactiveRegions.Endpoints): Seq[dom.Element] =
        val elements = mutable.ArrayBuffer.empty[dom.Element]
        var current  = DomReactiveRegions.next(endpoints.start)
        while current.nonEmpty && (current.get ne endpoints.end) do
            current.get match
                case element: dom.Element => elements += element
                case _                    => ()
            current = DomReactiveRegions.next(current.get)
        end while
        elements.toSeq
    end elementsBetween

    private def classifyIncoming(regionId: String, fragment: dom.DocumentFragment): DomReactiveRegions.IncomingContent =
        val roots = childElements(fragment)
        if roots.size == 1 && roots.head.tagName == "TBODY" && roots.head.getAttribute("data-kyo-range-host") == regionId then
            val host = roots.head
            DomReactiveRegions.IncomingContent.Synthetic(host, childElements(host))
        else DomReactiveRegions.IncomingContent.Semantic(roots)
        end if
    end classifyIncoming

    private def childElements(node: dom.Node): Seq[dom.Element] =
        val elements = mutable.ArrayBuffer.empty[dom.Element]
        var current  = DomReactiveRegions.firstChild(node)
        while current.nonEmpty do
            current.get match
                case element: dom.Element => elements += element
                case _                    => ()
            current = DomReactiveRegions.next(current.get)
        end while
        elements.toSeq
    end childElements

    private def syncHostAttributes(host: dom.Element, incoming: dom.Element, regionId: String): Unit =
        var i = host.attributes.length - 1
        while i >= 0 do
            val name = host.attributes(i).name
            if name != "data-kyo-range-host" then host.removeAttribute(name)
            i -= 1
        end while
        i = 0
        while i < incoming.attributes.length do
            val attribute = incoming.attributes(i)
            if attribute.name != "data-kyo-range-host" then host.setAttribute(attribute.name, attribute.value)
            i += 1
        end while
        host.setAttribute("data-kyo-range-host", regionId)
    end syncHostAttributes

end DomReactiveRegions

private[kyo] object DomReactiveRegions:

    private val StartPrefix = "kyo-rs:"
    private val EndPrefix   = "kyo-re:"

    final private case class Endpoints(start: dom.Comment, end: dom.Comment)

    private enum LiveHost:
        case Siblings(parent: dom.Node)
        case Synthetic(host: dom.Element, table: dom.Node)

    private enum IncomingContent:
        case Semantic(roots: Seq[dom.Element])
        case Synthetic(host: dom.Element, roots: Seq[dom.Element])

        def semanticRoots: Seq[dom.Element] = this match
            case Semantic(roots)     => roots
            case Synthetic(_, roots) => roots
    end IncomingContent

    def init(root: dom.Element)(using Frame): DomReactiveRegions < (Sync & Scope) =
        for
            registry <- Sync.defer {
                val document = ownerDocument(root)
                val registry = new DomReactiveRegions(document, scan(document, root), mutable.HashMap.empty)
                registry.recordInserted()
                registry
            }
            _ <- Scope.ensure(registry.close)
        yield registry

    private def scan(document: dom.Document, root: dom.Node)(using Frame): mutable.HashMap[String, Endpoints] =
        val found  = mutable.HashMap.empty[String, Endpoints]
        val seen   = mutable.HashSet.empty[String]
        val open   = mutable.ArrayBuffer.empty[(String, dom.Comment)]
        val walker = commentWalker(document, root)
        var next   = nextComment(walker)
        while next.nonEmpty do
            val comment = next.get
            val value   = comment.data
            if value.startsWith(StartPrefix) then
                val id = value.substring(StartPrefix.length)
                if !ReactiveRegion.isValidHtmlId(id) then fail(s"Malformed reactive range id: $id")
                if seen.contains(id) then fail(s"Duplicate reactive range id: $id")
                seen += id
                open += ((id, comment))
            else if value.startsWith(EndPrefix) then
                val id = value.substring(EndPrefix.length)
                if !ReactiveRegion.isValidHtmlId(id) then fail(s"Malformed reactive range id: $id")
                if open.isEmpty then fail(s"Reactive range end marker has no start: $id")
                val (expected, start) = open.last
                if id != expected then fail(s"Crossed reactive ranges: expected $expected, found $id")
                discard(open.remove(open.length - 1))
                (parent(start), parent(comment)) match
                    case (Present(startParent), Present(endParent)) if startParent eq endParent =>
                        found(id) = Endpoints(start, comment)
                    case _ => fail(s"Reactive range anchors are not siblings: $id")
                end match
            end if
            next = nextComment(walker)
        end while
        if open.nonEmpty then fail(s"Reactive range start marker has no end: ${open.last._1}")
        found
    end scan

    private def nextComment(walker: dom.TreeWalker): Maybe[dom.Comment] =
        val node = walker.nextNode()
        if node == null then Absent else Present(node.asInstanceOf[dom.Comment])

    private def parent(node: dom.Node): Maybe[dom.Node] =
        val value = node.parentNode
        if value == null then Absent else Present(value)

    private def firstChild(node: dom.Node): Maybe[dom.Node] =
        val value = node.firstChild
        if value == null then Absent else Present(value)

    private def next(node: dom.Node): Maybe[dom.Node] =
        val value = node.nextSibling
        if value == null then Absent else Present(value)

    private def ownerDocument(node: dom.Node): dom.Document =
        val value = node.ownerDocument
        if value == null then dom.document else value

    private def commentWalker(document: dom.Document, root: dom.Node): dom.TreeWalker =
        document.createTreeWalker(root, 128, null, false)

    private def fail(message: String)(using Frame): Nothing =
        throw UIException(message)

end DomReactiveRegions
