package kyo.internal

import kyo.*
import kyo.Style.Prop.*
import org.scalajs.dom
import org.scalajs.dom.document

private[kyo] object DomStyleSheet:

    private val ids =
        import AllowUnsafe.embrace.danger
        AtomicInt.Unsafe.init(0).safe

    private val styleElement: dom.html.Style =
        val el = document.createElement("style").asInstanceOf[dom.html.Style]
        val _  = document.head.appendChild(el)
        el
    end styleElement

    private val baseCss = HtmlRenderer.baseCss

    private var baseInjected = false

    // Tracks CSS strings already injected via injectStylesheet to avoid duplicate appends.
    // The browser is single-threaded so a plain mutable Set is safe here.
    private val injectedSheets = scala.collection.mutable.Set.empty[String]

    /** Injects the base CSS reset once. Idempotent: the first call appends the reset rules; later calls are no-ops.
      * (kyo-ui is JS-only and the browser is single-threaded, so a plain flag is sufficient.)
      */
    def injectBase()(using Frame): Unit < Sync = Sync.defer {
        if !baseInjected then
            baseInjected = true
            inject(baseCss)
    }

    /** Applies a Style to a DOM element.
      *
      * Base properties go into a generated CSS rule (not inline) so that pseudo-state rules (:hover, :focus, :active, :disabled) can
      * override them properly. If the Style has no pseudo-states, inline style is used instead for simplicity.
      *
      * Returns the generated class name if a CSS rule was injected, Absent otherwise.
      */
    def apply(el: dom.Element, style: Style, inlineStyle: Maybe[String])(using Frame): Maybe[String] < Sync =
        if style.isEmpty then
            Sync.defer {
                inlineStyle.foreach(s => el.setAttribute("style", s))
                Absent
            }
        else
            val hover     = style.find[HoverProp].map(_.style)
            val focus     = style.find[FocusProp].map(_.style)
            val active    = style.find[ActiveProp].map(_.style)
            val disabled  = style.find[DisabledProp].map(_.style)
            val hasPseudo = hover.nonEmpty || focus.nonEmpty || active.nonEmpty || disabled.nonEmpty
            if hasPseudo then
                nextClass.map { cls =>
                    Sync.defer {
                        el.classList.add(cls)
                        val sb      = new StringBuilder
                        val baseCss = CssStyleRenderer.render(style)
                        if baseCss.nonEmpty then sb.append(s".$cls { $baseCss }\n")
                        hover.foreach(s => sb.append(s".$cls:hover { ${CssStyleRenderer.render(s)} }\n"))
                        focus.foreach(s => sb.append(s".$cls:focus { ${CssStyleRenderer.render(s)} }\n"))
                        active.foreach(s => sb.append(s".$cls:active { ${CssStyleRenderer.render(s)} }\n"))
                        disabled.foreach(s => sb.append(s".$cls:disabled { ${CssStyleRenderer.render(s)} }\n"))
                        inject(sb.toString)
                        inlineStyle.foreach(s => el.setAttribute("style", s))
                        Present(cls)
                    }
                }
            else
                Sync.defer {
                    val baseCss = CssStyleRenderer.render(style)
                    inlineStyle match
                        case Present(s) => el.setAttribute("style", s + " " + baseCss)
                        case Absent     => el.setAttribute("style", baseCss)
                    Absent
                }
            end if
        end if
    end apply

    /** Appends a CSS string to the kyo-ui document stylesheet if it has not been appended
      * before. Idempotent: a second call with the same CSS text is a no-op. Reuses the same
      * injection point as the per-element auto-class mechanism, so authored stylesheet rules
      * and per-element pseudo-state rules share one `<style>` element.
      */
    private[kyo] def injectStylesheet(css: String): Unit =
        if !injectedSheets.contains(css) then
            injectedSheets.add(css)
            inject(css)

    private def nextClass(using Frame): String < Sync =
        ids.incrementAndGet.map(n => s"kyo-s$n")

    private def inject(css: String): Unit =
        styleElement.textContent = styleElement.textContent + css

end DomStyleSheet
