package kyo.internal.tui.pipeline

import kyo.*
import kyo.Test

class ScreenStateTest extends Test:

    val defaultTheme = ResolvedTheme.resolve(Theme.Default)

    "ScreenState" - {
        "refs initialize to Absent" in run {
            import AllowUnsafe.embrace.danger
            val state = new ScreenState(defaultTheme)
            assert(state.focusedId.get() == Absent)
            assert(state.hoveredId.get() == Absent)
            assert(state.activeId.get() == Absent)
        }

        "focusableIds starts empty" in run {
            import AllowUnsafe.embrace.danger
            val state = new ScreenState(defaultTheme)
            assert(state.focusableIds.isEmpty)
        }

        "prevLayout and prevGrid start as Absent" in run {
            import AllowUnsafe.embrace.danger
            val state = new ScreenState(defaultTheme)
            assert(state.prevLayout == Absent)
            assert(state.prevGrid == Absent)
        }
    }

    "ResolvedTheme" - {
        "resolve Default" in {
            val t = ResolvedTheme.resolve(Theme.Default)
            assert(t.variant == Theme.Default)
            assert(t.fg == RGB.Transparent)
            assert(t.bg == RGB.Transparent)
        }

        "resolve Minimal" in {
            val t = ResolvedTheme.resolve(Theme.Minimal)
            assert(t.variant == Theme.Minimal)
        }

        "resolve Plain" in {
            val t = ResolvedTheme.resolve(Theme.Plain)
            assert(t.variant == Theme.Plain)
        }
    }

    "FlatStyle.fromTheme" - {
        "sets fg and bg from theme" in {
            val t  = ResolvedTheme.resolve(Theme.Default)
            val cs = FlatStyle.fromTheme(t)
            assert(cs.fg == t.fg)
            assert(cs.bg == t.bg)
        }

        "preserves other defaults" in {
            val cs = FlatStyle.fromTheme(defaultTheme)
            assert(!cs.bold)
            assert(cs.lineHeight == 1)
            assert(cs.width == Length.Auto)
        }
    }

end ScreenStateTest
