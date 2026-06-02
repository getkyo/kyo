package kyo

import kyo.internal.CssStyleRenderer

/** A document-level stylesheet: an ordered, immutable collection of CSS rules, media queries,
  * top-level custom properties (CSS variables), and font-face declarations, rendered to one CSS
  * string. This is the document counterpart to [[kyo.Style]] (which styles a single element):
  * `Style` carries the declarations, `Stylesheet` carries the SELECTORS, breakpoints, variables,
  * and fonts that a per-element `Style` cannot express.
  *
  * A `Stylesheet` is a pure value built by appending entries (`rule`, `media`, `vars`,
  * `fontFace`); every builder returns a new `Stylesheet`, so it is safe to share and compose with
  * `++`. Render it with [[kyo.Stylesheet.render]] to a CSS string for a document `<head>` (pass it
  * as [[kyo.UI.PageHead.css]] to [[kyo.UI.runRenderPage]]) or inject it client-side with
  * [[kyo.UI.runStylesheet]]. Declarations reuse [[kyo.Style]] verbatim, so the same value types
  * (`Color`, `Length`, the pseudo-state nesting) apply; the selector targets elements that carry a
  * matching [[kyo.UI.cssClass]] (or an `id`/`data-*` selector).
  *
  * Emission order is preserved (CSS is last-declaration-wins at equal specificity), so author
  * base rules before overrides and place `@media` blocks after the rules they refine.
  *
  * @see
  *   [[kyo.Stylesheet.rule]], [[kyo.Stylesheet.media]], [[kyo.Stylesheet.vars]],
  *   [[kyo.Stylesheet.fontFace]] for the builders
  * @see
  *   [[kyo.Style]] for the per-element declaration value the rules reuse
  * @see
  *   [[kyo.UI.cssClass]] for the element-side class hook a class selector targets
  */
final case class Stylesheet private[kyo] (entries: Chunk[Stylesheet.Entry]) derives CanEqual:

    /** Appends a single rule (a selector and the [[kyo.Style]] declarations applied to it). */
    def rule(selector: Selector, style: Style): Stylesheet =
        Stylesheet(entries :+ Stylesheet.Entry.Rule(selector, style))

    /** Appends a rule whose selector is the given CSS class name (shorthand for
      * `rule(Selector.cls(name), style)`).
      */
    def rule(className: String, style: Style): Stylesheet =
        rule(Selector.cls(className), style)

    /** Appends an `@media` block: the `query` (a [[kyo.Stylesheet.MediaQuery]]) wrapping the rules
      * of the nested `Stylesheet`. Nested media is flattened to a single level on render.
      */
    def media(query: Stylesheet.MediaQuery)(nested: Stylesheet): Stylesheet =
        Stylesheet(entries :+ Stylesheet.Entry.Media(query, nested))

    /** Appends a `:root` block of CSS custom properties (variables). Each pair is `(name, value)`
      * emitted as `--name: value;`. Reference a variable in a declaration with
      * [[kyo.Style.Color.variable]] or [[kyo.Length]]'s variable form.
      */
    def vars(pairs: (String, String)*): Stylesheet =
        Stylesheet(entries :+ Stylesheet.Entry.Vars(pairs.toList))

    /** Appends an `@font-face` declaration (a [[kyo.Stylesheet.FontFace]]). */
    def fontFace(face: Stylesheet.FontFace): Stylesheet =
        Stylesheet(entries :+ Stylesheet.Entry.Font(face))

    /** Concatenates another stylesheet's entries after this one's (order preserved). */
    def ++(other: Stylesheet): Stylesheet =
        Stylesheet(entries ++ other.entries)

    def isEmpty: Boolean  = entries.isEmpty
    def nonEmpty: Boolean = entries.nonEmpty

    /** Renders the whole stylesheet to a single CSS string: each rule as `selector { declarations }`
      * (declarations from [[kyo.internal.CssStyleRenderer]], including nested pseudo-state rules),
      * each media block as `@media query { ... }`, each `vars` block as `:root { --k: v; }`, and each
      * font-face as `@font-face { ... }`. Entry order is preserved.
      */
    def render(using Frame): String = Stylesheet.renderSheet(this)

end Stylesheet

object Stylesheet:

    /** The empty stylesheet; the identity for `++`. */
    val empty: Stylesheet = Stylesheet(Chunk.empty[Entry])

    /** Starts a stylesheet from a single rule. */
    def rule(selector: Selector, style: Style): Stylesheet = empty.rule(selector, style)

    /** Starts a stylesheet from a single class-name rule. */
    def rule(className: String, style: Style): Stylesheet = empty.rule(className, style)

    /** Starts a stylesheet with a single `@media` block. */
    def media(query: MediaQuery)(nested: Stylesheet): Stylesheet = empty.media(query)(nested)

    /** Starts a stylesheet with a `:root` variables block. */
    def vars(pairs: (String, String)*): Stylesheet = empty.vars(pairs*)

    /** Starts a stylesheet with a single `@font-face` declaration. */
    def fontFace(face: FontFace): Stylesheet = empty.fontFace(face)

    /** One entry in a [[kyo.Stylesheet]]: a plain rule, an `@media` block, a `:root` variables block,
      * or an `@font-face`. Pattern-matchable for inspection and testing.
      */
    enum Entry derives CanEqual:
        case Rule(selector: Selector, style: Style)
        case Media(query: MediaQuery, nested: Stylesheet)
        case Vars(pairs: List[(String, String)])
        case Font(face: FontFace)
    end Entry

    /** A media query for an `@media` block. Built from the typed factories (`minWidth`, `maxWidth`,
      * `prefersDark`, `prefersReducedMotion`) and combined with `and`, so common responsive
      * breakpoints are expressible without a raw string; `raw` is the escape hatch for an arbitrary
      * media condition.
      */
    final case class MediaQuery private[kyo] (condition: String) derives CanEqual:
        /** Combines two media queries with `and`, e.g. `minWidth(768.px).and(prefersDark)`. */
        def and(other: MediaQuery): MediaQuery = MediaQuery(condition + " and " + other.condition)
    end MediaQuery

    object MediaQuery:
        /** A `(min-width: Npx)` media query. */
        def minWidth(px: Length.Px): MediaQuery = MediaQuery(s"(min-width: ${px.value.toInt}px)")

        /** A `(max-width: Npx)` media query. */
        def maxWidth(px: Length.Px): MediaQuery = MediaQuery(s"(max-width: ${px.value.toInt}px)")

        /** Targets `prefers-color-scheme: dark`. */
        val prefersDark: MediaQuery = MediaQuery("(prefers-color-scheme: dark)")

        /** Targets `prefers-reduced-motion: reduce`. */
        val prefersReducedMotion: MediaQuery = MediaQuery("(prefers-reduced-motion: reduce)")

        /** An arbitrary media condition string, for cases the typed factories do not cover. */
        def raw(condition: String): MediaQuery = MediaQuery(condition)
    end MediaQuery

    /** An `@font-face` declaration: the font `family` name, the `src` entries (each a `(url, format)`
      * pair, e.g. `("/fonts/inter.woff2", "woff2")`), the `weight` (a [[kyo.Style.FontWeight]]), the
      * `style` (a [[kyo.Style.FontStyle]]), and the `display` strategy. Reuses kyo-ui's existing
      * `FontWeight`/`FontStyle` enums rather than introducing new ones.
      */
    final case class FontFace(
        family: String,
        src: Seq[(String, String)],
        weight: Style.FontWeight = Style.FontWeight.normal,
        style: Style.FontStyle = Style.FontStyle.normal,
        display: FontFace.Display = FontFace.Display.swap
    ) derives CanEqual

    object FontFace:
        /** The CSS `font-display` strategy. */
        enum Display derives CanEqual:
            case auto, block, swap, fallback, optional
        end Display
    end FontFace

    /** Renders a `Stylesheet` to a CSS string. Called by `Stylesheet.render`. */
    private[kyo] def renderSheet(sheet: Stylesheet)(using Frame): String =
        val sb = new StringBuilder
        sheet.entries.foreach {
            case Entry.Rule(sel, style) =>
                val base = CssStyleRenderer.render(
                    style
                        .without[Style.Prop.HoverProp]
                        .without[Style.Prop.FocusProp]
                        .without[Style.Prop.ActiveProp]
                        .without[Style.Prop.DisabledProp]
                )
                if base.nonEmpty then sb.append(s"${sel.css} { $base }\n")
                style.find[Style.Prop.HoverProp].foreach(p =>
                    sb.append(s"${sel.css}:hover { ${CssStyleRenderer.render(p.style)} }\n")
                )
                style.find[Style.Prop.FocusProp].foreach(p =>
                    sb.append(s"${sel.css}:focus { ${CssStyleRenderer.render(p.style)} }\n")
                )
                style.find[Style.Prop.ActiveProp].foreach(p =>
                    sb.append(s"${sel.css}:active { ${CssStyleRenderer.render(p.style)} }\n")
                )
                style.find[Style.Prop.DisabledProp].foreach(p =>
                    sb.append(s"${sel.css}:disabled { ${CssStyleRenderer.render(p.style)} }\n")
                )
            case Entry.Media(q, nested) =>
                sb.append(s"@media ${q.condition} {\n")
                sb.append(renderSheet(nested))
                sb.append("}\n")
            case Entry.Vars(pairs) =>
                sb.append(":root {")
                pairs.foreach((k, v) => sb.append(s" --$k: $v;"))
                sb.append(" }\n")
            case Entry.Font(f) =>
                val srcs = f.src.map((url, fmt) => s"""url("$url") format("$fmt")""").mkString(", ")
                sb.append(s"""@font-face { font-family: "${f.family}"; src: $srcs;""")
                sb.append(s" font-weight: ${CssStyleRenderer.fontWeightCss(f.weight)};")
                sb.append(s" font-style: ${CssStyleRenderer.fontStyleCss(f.style)};")
                sb.append(s" font-display: ${f.display.toString}; }\n")
        }
        sb.toString
    end renderSheet

end Stylesheet
