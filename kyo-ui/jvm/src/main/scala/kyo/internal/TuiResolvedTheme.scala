package kyo.internal

import kyo.*
import kyo.Style.BorderStyle
import kyo.Style.Size
import kyo.Style.Size.*

/** Pre-resolved theme for TUI: maps element types to default Styles. Created once per session, zero per-frame cost. */
final private[kyo] class TuiResolvedTheme(private val lookup: UI.Element => Style):
    /** Get the theme style for an element. Returns Style.empty if no theme style defined. */
    inline def forElement(elem: UI.Element): Style = lookup(elem)
end TuiResolvedTheme

private[kyo] object TuiResolvedTheme:

    val empty: TuiResolvedTheme = new TuiResolvedTheme(_ => Style.empty)

    def resolve(theme: Theme): TuiResolvedTheme = theme match
        case Theme.Default => defaultTheme
        case Theme.Minimal => minimalTheme
        case Theme.Plain   => empty

    private val defaultTheme: TuiResolvedTheme =
        // Input/Textarea: thin border with 1-cell horizontal padding
        val inputStyle    = Style.border(1.px, BorderStyle.solid, "#666").padding(0.em, 1.em)
        val textareaStyle = Style.border(1.px, BorderStyle.solid, "#666").padding(0.em, 1.em)
        // Button: thin border with 1-cell horizontal padding
        val buttonStyle = Style.border(1.px, BorderStyle.solid, "#888").padding(0.em, 1.em)
        // Headings: bold
        val headingStyle = Style.bold
        // Hr: bottom border for horizontal line
        val hrStyle = Style.borderBottom(1.px, "#666").borderStyle(BorderStyle.solid)
        // Nav: gap between inline children
        val navStyle = Style.gap(1.px)
        new TuiResolvedTheme({
            case _: UI.Textarea                                                  => textareaStyle
            case _: UI.TextInput                                                 => inputStyle
            case _: UI.PickerInput | _: UI.RangeInput | _: UI.FileInput          => inputStyle
            case _: UI.Button                                                    => buttonStyle
            case _: UI.H1 | _: UI.H2 | _: UI.H3 | _: UI.H4 | _: UI.H5 | _: UI.H6 => headingStyle
            case _: UI.Hr                                                        => hrStyle
            case _: UI.Nav                                                       => navStyle
            case _                                                               => Style.empty
        })
    end defaultTheme

    private val minimalTheme: TuiResolvedTheme =
        val headingStyle = Style.bold
        val hrStyle      = Style.borderBottom(1.px, "#666").borderStyle(BorderStyle.solid)
        val navStyle     = Style.gap(1.px)
        new TuiResolvedTheme({
            case _: UI.H1 | _: UI.H2 | _: UI.H3 | _: UI.H4 | _: UI.H5 | _: UI.H6 => headingStyle
            case _: UI.Hr                                                        => hrStyle
            case _: UI.Nav                                                       => navStyle
            case _                                                               => Style.empty
        })
    end minimalTheme

end TuiResolvedTheme
