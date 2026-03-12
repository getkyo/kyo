package kyo.internal

import kyo.*
import kyo.Style.Prop.*
import org.scalajs.dom
import org.scalajs.dom.document

private[kyo] object DomStyleSheet:

    private var idCounter    = 0
    private var baseInjected = false

    /** Injects base CSS reset and layout defaults. Makes web layout match flex behavior: block containers get flex column, inline
      * containers get flex row.
      */
    def injectBase(): Unit =
        if !baseInjected then
            baseInjected = true
            inject(
                """*, *::before, *::after { box-sizing: border-box; }
                  |body { font-family: system-ui, -apple-system, sans-serif; margin: 0; padding: 0; }
                  |div, section, main, header, footer, form, article, aside, p, ul, ol, pre, code, h1, h2, h3, h4, h5, h6, label { display: flex; flex-direction: column; }
                  |nav, li, span, button, a { display: flex; flex-direction: row; align-items: center; }
                  |ul, ol { list-style: none; padding: 0; margin: 0; }
                  |h1, h2, h3, h4, h5, h6, p { margin: 0; }
                  |a { color: inherit; text-decoration: none; }
                  |table { border-collapse: collapse; width: 100%; }
                  |""".stripMargin
            )
    end injectBase

    private lazy val styleElement: dom.html.Style =
        val el = document.createElement("style").asInstanceOf[dom.html.Style]
        val _  = document.head.appendChild(el)
        el
    end styleElement

    /** Applies a Style to a DOM element.
      *
      * Base properties go into a generated CSS rule (not inline) so that pseudo-state rules (:hover, :focus, :active, :disabled) can
      * override them properly. If the Style has no pseudo-states, inline style is used instead for simplicity.
      *
      * Returns the generated class name if a CSS rule was injected, Absent otherwise.
      */
    def apply(el: dom.Element, style: Style, inlineStyle: Maybe[String]): Maybe[String] =
        if style.isEmpty then
            inlineStyle.foreach(s => el.setAttribute("style", s))
            Absent
        else
            val hover    = style.find[HoverProp].map(_.style)
            val focus    = style.find[FocusProp].map(_.style)
            val active   = style.find[ActiveProp].map(_.style)
            val disabled = style.find[DisabledProp].map(_.style)

            val hasPseudo = hover.nonEmpty || focus.nonEmpty || active.nonEmpty || disabled.nonEmpty

            if hasPseudo then
                val cls = nextClass()
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
            else
                val baseCss = CssStyleRenderer.render(style)
                inlineStyle match
                    case Present(s) => el.setAttribute("style", s + " " + baseCss)
                    case Absent     => el.setAttribute("style", baseCss)
                Absent
            end if
        end if
    end apply

    private def nextClass(): String =
        idCounter += 1
        s"kyo-s$idCounter"

    private def inject(css: String): Unit =
        styleElement.textContent = styleElement.textContent + css

end DomStyleSheet
