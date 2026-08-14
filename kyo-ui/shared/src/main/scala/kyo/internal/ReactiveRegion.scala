package kyo.internal

import kyo.UI
import kyo.UI.Ast.*

/** Identifies the DOM representation of one reactive UI boundary. */
sealed private[kyo] trait ReactiveRegion derives CanEqual

private[kyo] object ReactiveRegion:

    /** Render identity separates application paths from transparent reactive nesting. */
    final case class RegionIdentity(path: Seq[String], transparentDepth: Int) derives CanEqual:
        require(transparentDepth >= 0, s"transparentDepth must be non-negative: $transparentDepth")

        def child(segment: String): RegionIdentity = RegionIdentity(path :+ segment, 0)

        def transparent: RegionIdentity =
            if transparentDepth == Int.MaxValue then
                throw new IllegalStateException("Reactive region transparent nesting exceeds Int.MaxValue")
            else copy(transparentDepth = transparentDepth + 1)
    end RegionIdentity

    object RegionIdentity:
        def root(path: Seq[String]): RegionIdentity = RegionIdentity(path, 0)

    enum ParentContext derives CanEqual:
        case HtmlTable, Other

    enum Namespace derives CanEqual:
        case Html, Svg

    enum BoundaryMode derives CanEqual:
        case Emit, Suppress

    enum TableContent derives CanEqual:
        case Rows, AuthoredSections, Transparent, Other

    enum RenderHost derives CanEqual:
        case HtmlComments(id: String, parentContext: ParentContext)
        case HtmlTableBody(id: String)
        case SvgGroup(path: Seq[String])
    end RenderHost

    def tableContent(ui: UI): TableContent =
        ui match
            case _: Tbody                               => TableContent.AuthoredSections
            case _: Tr                                  => TableContent.Rows
            case _: Reactive[?]                         => TableContent.Transparent
            case _: Foreach[?, ?]                       => TableContent.Transparent
            case KeyedChild(_, child)                   => tableContent(child)
            case Fragment(children) if children.isEmpty => TableContent.Other
            case Fragment(children)                     => tableContent(children)
            case _                                      => TableContent.Other
    end tableContent

    def tableContent(children: IterableOnce[UI]): TableContent =
        val iterator    = children.iterator
        var hasAuthored = false
        var hasOther    = false
        while iterator.hasNext do
            tableContent(iterator.next()) match
                case TableContent.Rows             => return TableContent.Rows
                case TableContent.AuthoredSections => hasAuthored = true
                case TableContent.Transparent      => ()
                case TableContent.Other            => hasOther = true
        end while
        if hasOther then TableContent.Other
        else if hasAuthored then TableContent.AuthoredSections
        else TableContent.Transparent
    end tableContent

    final case class HtmlRange(id: String)         extends ReactiveRegion
    final case class SvgElement(path: Seq[String]) extends ReactiveRegion

    def renderHost(region: ReactiveRegion, parentContext: ParentContext, content: TableContent): RenderHost =
        region match
            case HtmlRange(id) =>
                (parentContext, content) match
                    case (ParentContext.HtmlTable, TableContent.Rows) => RenderHost.HtmlTableBody(id)
                    case _                                            => RenderHost.HtmlComments(id, parentContext)
            case SvgElement(path) => RenderHost.SvgGroup(path)
    end renderHost

    def namespace(host: RenderHost): Namespace =
        host match
            case _: RenderHost.HtmlComments  => Namespace.Html
            case _: RenderHost.HtmlTableBody => Namespace.Html
            case _: RenderHost.SvgGroup      => Namespace.Svg
    end namespace

    def contentParent(host: RenderHost): ParentContext =
        host match
            case RenderHost.HtmlComments(_, parentContext) => parentContext
            case _: RenderHost.HtmlTableBody               => ParentContext.Other
            case _: RenderHost.SvgGroup                    => ParentContext.Other
    end contentParent

    def from(path: Seq[String], svgContext: Boolean): ReactiveRegion =
        from(RegionIdentity.root(path), svgContext)

    def from(identity: RegionIdentity, svgContext: Boolean): ReactiveRegion =
        if svgContext then SvgElement(identity.path)
        else HtmlRange(htmlId(identity))

    def from(identity: RegionIdentity, namespace: Namespace): ReactiveRegion =
        namespace match
            case Namespace.Html => HtmlRange(htmlId(identity))
            case Namespace.Svg  => SvgElement(identity.path)

    def namespace(region: ReactiveRegion): Namespace =
        region match
            case _: HtmlRange  => Namespace.Html
            case _: SvgElement => Namespace.Svg

    def owns(region: ReactiveRegion, identity: RegionIdentity): Boolean =
        region match
            case HtmlRange(id)    => id == htmlId(identity)
            case SvgElement(path) => path == identity.path

    private[kyo] def htmlId(path: Seq[String]): String =
        htmlId(RegionIdentity.root(path))

    private[kyo] def htmlId(identity: RegionIdentity): String =
        var size = 1
        identity.path.foreach(segment => size += 8 + segment.length * 4)
        if identity.transparentDepth > 0 then size += 9
        val out = new StringBuilder(size)
        out.append('r')
        identity.path.foreach { segment =>
            appendHex(out, segment.length, 8)
            var i = 0
            while i < segment.length do
                appendHex(out, segment.charAt(i).toInt, 4)
                i += 1
            end while
        }
        if identity.transparentDepth > 0 then
            out.append('n')
            appendHex(out, identity.transparentDepth, 8)
        out.toString
    end htmlId

    private[kyo] def isValidHtmlId(id: String): Boolean =
        if id.isEmpty || id.charAt(0) != 'r' then false
        else
            val nestingAt = id.indexOf('n', 1)
            val pathEnd   = if nestingAt < 0 then id.length else nestingAt
            val validNesting =
                if nestingAt < 0 then true
                else if nestingAt + 9 != id.length then false
                else
                    var i       = nestingAt + 1
                    var nonZero = false
                    var valid   = true
                    while valid && i < id.length do
                        val c = id.charAt(i)
                        valid = isHex(c)
                        nonZero ||= c != '0'
                        i += 1
                    end while
                    valid && nonZero

            var i     = 1
            var valid = validNesting
            while valid && i < pathEnd do
                if i + 8 > pathEnd then valid = false
                else
                    var units = 0L
                    var j     = 0
                    while valid && j < 8 do
                        val digit = hexValue(id.charAt(i + j))
                        if digit < 0 then valid = false
                        else units = (units << 4) | digit
                        j += 1
                    end while
                    i += 8
                    val encodedUnits = units * 4
                    if valid && (encodedUnits > pathEnd - i) then valid = false
                    else
                        val unitsEnd = i + encodedUnits.toInt
                        while valid && i < unitsEnd do
                            valid = isHex(id.charAt(i))
                            i += 1
                        end while
                    end if
            end while
            valid && i == pathEnd
        end if
    end isValidHtmlId

    private def isHex(c: Char): Boolean = hexValue(c) >= 0

    private def hexValue(c: Char): Int =
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else -1

    private def appendHex(out: StringBuilder, value: Int, digits: Int): Unit =
        var shift = (digits - 1) * 4
        while shift >= 0 do
            val nibble = (value >>> shift) & 0xf
            out.append(if nibble < 10 then ('0' + nibble).toChar else ('a' + nibble - 10).toChar)
            shift -= 4
        end while
    end appendHex

end ReactiveRegion
