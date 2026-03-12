package kyo

/** Theme controls default tag styling applied by each backend.
  *
  * Each backend expands these variants into backend-specific defaults. For TUI, Default adds borders on inputs, bold headings, etc. For
  * web/JavaFX, Default is typically empty since the browser handles tag styling.
  */
enum Theme derives CanEqual:
    /** Rich defaults: bordered inputs, bold headings, styled buttons, horizontal rules. */
    case Default

    /** Minimal defaults: bold headings, basic gap, horizontal rules. */
    case Minimal

    /** No defaults: raw unstyled elements. */
    case Plain
end Theme
