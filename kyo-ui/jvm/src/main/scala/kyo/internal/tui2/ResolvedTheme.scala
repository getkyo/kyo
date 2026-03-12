package kyo.internal.tui2

import kyo.*
import kyo.Style.BorderStyle
import kyo.Style.Size.*

/** Pre-resolved theme for TUI: maps element types to default Styles.
  *
  * Created once per session, zero per-frame cost. The lookup function pattern-matches on UI element type.
  */
final private[kyo] class ResolvedTheme(
    private val lookup: UI.Element => Style,
    val defaultFocusStyle: Style,
    val defaultDisabledStyle: Style
):
    inline def styleFor(elem: UI.Element): Style = lookup(elem)
end ResolvedTheme

private[kyo] object ResolvedTheme:

    val empty: ResolvedTheme = new ResolvedTheme(_ => Style.empty, Style.empty, Style.empty)

    def resolve(theme: Theme): ResolvedTheme = theme match
        case Theme.Default => defaultTheme
        case Theme.Minimal => minimalTheme
        case Theme.Plain   => empty

    private val defaultFocus    = Style.borderColor("#4488ff").borderStyle(BorderStyle.solid)
    private val defaultDisabled = Style.color("#666666")

    private val defaultTheme: ResolvedTheme =
        val inputStyle    = Style.border(1.px, BorderStyle.solid, "#666").padding(0.em, 1.em)
        val textareaStyle = Style.border(1.px, BorderStyle.solid, "#666").padding(0.em, 1.em)
        val buttonStyle   = Style.border(1.px, BorderStyle.solid, "#888").padding(0.em, 1.em)
        val h1Style       = Style.bold.padding(1.em, 0.em, 1.em, 0.em)
        val h2Style       = Style.bold.padding(1.em, 0.em, 0.em, 0.em)
        val headingStyle  = Style.bold
        val thStyle       = Style.bold.textAlign(Style.TextAlign.center)
        val anchorStyle   = Style.underline.color("#5599ff")
        val listStyle     = Style.padding(0.em, 0.em, 0.em, 1.em)
        val hrStyle       = Style.color("#666")
        val navStyle      = Style.gap(1.px)
        new ResolvedTheme(
            {
                case _: UI.Textarea                            => textareaStyle
                case _: UI.TextInput                           => inputStyle
                case _: UI.PickerInput | _: UI.FileInput       => inputStyle
                case _: UI.Button                              => buttonStyle
                case _: UI.H1                                  => h1Style
                case _: UI.H2                                  => h2Style
                case _: UI.H3 | _: UI.H4 | _: UI.H5 | _: UI.H6 => headingStyle
                case _: UI.Th                                  => thStyle
                case _: UI.Anchor                              => anchorStyle
                case _: UI.Ul | _: UI.Ol                       => listStyle
                case _: UI.Hr                                  => hrStyle
                case _: UI.Nav                                 => navStyle
                case _                                         => Style.empty
            },
            defaultFocus,
            defaultDisabled
        )
    end defaultTheme

    private val minimalTheme: ResolvedTheme =
        val h1Style      = Style.bold.padding(1.em, 0.em, 1.em, 0.em)
        val h2Style      = Style.bold.padding(1.em, 0.em, 0.em, 0.em)
        val headingStyle = Style.bold
        val thStyle      = Style.bold.textAlign(Style.TextAlign.center)
        val anchorStyle  = Style.underline.color("#5599ff")
        val listStyle    = Style.padding(0.em, 0.em, 0.em, 1.em)
        val hrStyle      = Style.borderBottom(1.px, "#666").borderStyle(BorderStyle.solid)
        val navStyle     = Style.gap(1.px)
        new ResolvedTheme(
            {
                case _: UI.H1                                  => h1Style
                case _: UI.H2                                  => h2Style
                case _: UI.H3 | _: UI.H4 | _: UI.H5 | _: UI.H6 => headingStyle
                case _: UI.Th                                  => thStyle
                case _: UI.Anchor                              => anchorStyle
                case _: UI.Ul | _: UI.Ol                       => listStyle
                case _: UI.Hr                                  => hrStyle
                case _: UI.Nav                                 => navStyle
                case _                                         => Style.empty
            },
            Style.empty,
            Style.empty
        )
    end minimalTheme

end ResolvedTheme
