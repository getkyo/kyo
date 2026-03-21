package kyo

/** Theme provides color tokens for styling. Each backend applies its own default element styles (borders, padding, etc.) using these
  * tokens. The theme does NOT contain element-specific styles — those are backend-specific.
  *
  * For TUI, the "user agent stylesheet" is in Lower.themeStyle. For web, the browser provides defaults. The theme only controls
  * colors/branding that are shared across backends.
  */
enum Theme derives CanEqual:
    /** Standard colors: white on black, grey borders. */
    case Default

    /** Same colors, reduced element styling (bold headings, hr only). */
    case Minimal

    /** Same colors, no element styling at all. */
    case Plain
end Theme
