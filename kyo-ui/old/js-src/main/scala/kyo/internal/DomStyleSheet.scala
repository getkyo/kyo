package kyo.internal

import kyo.*
import org.scalajs.dom
import org.scalajs.dom.document

private[kyo] object DomStyleSheet:

    private var idCounter    = 0
    private var baseInjected = false

    /** Injects base CSS reset and layout defaults. Makes web layout match JavaFx's VBox/HBox behavior: block containers get flex column,
      * inline containers get flex row.
      */
    def injectBase(): Unit =
        if !baseInjected then
            baseInjected = true
            inject(
                """*, *::before, *::after { box-sizing: border-box; }
                  |body { font-family: system-ui, -apple-system, sans-serif; margin: 0; padding: 0; }
                  |div, section, main, header, footer, form, article, aside, p, ul, ol { display: flex; flex-direction: column; }
                  |nav, li, span { display: flex; flex-direction: row; align-items: center; }
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
      * Base properties go into a generated CSS rule (not inline) so that pseudo-state rules (:hover, :focus, :active) can override them
      * properly. If the Style has no pseudo-states and no string style attribute is present, inline style is used instead for simplicity.
      *
      * Returns the generated class name if a CSS rule was injected, Absent otherwise.
      */
    def apply(el: dom.Element, style: Style, inlineStyle: Maybe[String]): Maybe[String] =
        if style.isEmpty then
            inlineStyle.foreach(s => el.setAttribute("style", s))
            Absent
        else
            val base   = style.baseProps
            val hover  = style.hoverStyle
            val focus  = style.focusStyle
            val active = style.activeStyle

            val hasPseudo = hover.nonEmpty || focus.nonEmpty || active.nonEmpty

            if hasPseudo then
                val cls = nextClass()
                el.classList.add(cls)
                val sb      = new StringBuilder
                val baseCss = CssStyleRenderer.render(base)
                if baseCss.nonEmpty then sb.append(s".$cls { $baseCss }\n")
                hover.foreach(s => sb.append(s".$cls:hover { ${CssStyleRenderer.render(s)} }\n"))
                focus.foreach(s => sb.append(s".$cls:focus { ${CssStyleRenderer.render(s)} }\n"))
                active.foreach(s => sb.append(s".$cls:active { ${CssStyleRenderer.render(s)} }\n"))
                inject(sb.toString)
                inlineStyle.foreach(s => el.setAttribute("style", s))
                Present(cls)
            else
                val baseCss = CssStyleRenderer.render(base)
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
