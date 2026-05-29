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

    // Class-loading side effect: base CSS reset injected once when DomStyleSheet is first referenced.
    inject(
        """*, *::before, *::after { box-sizing: border-box; }
          |body { font-family: system-ui, -apple-system, sans-serif; margin: 0; padding: 0; }
          |div, section, main, header, footer, form, article, aside, p, ul, ol, pre, code, h1, h2, h3, h4, h5, h6, label { display: flex; flex-direction: column; }
          |nav, li, span, button, a { display: flex; flex-direction: row; align-items: center; }
          |[data-kyo-reactive] { display: contents; }
          |ul, ol { list-style: none; padding: 0; margin: 0; }
          |h1, h2, h3, h4, h5, h6, p { margin: 0; }
          |a { color: inherit; text-decoration: none; }
          |table { border-collapse: collapse; width: 100%; }
          |""".stripMargin
    )

    /** Forces class loading so the base CSS reset is injected. No-op after the first invocation. */
    def injectBase()(using Frame): Unit < Sync = Sync.defer(())

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

    private def nextClass(using Frame): String < Sync =
        ids.incrementAndGet.map(n => s"kyo-s$n")

    private def inject(css: String): Unit =
        styleElement.textContent = styleElement.textContent + css

end DomStyleSheet
