package kyo.internal.tui2.pipeline

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
            assert(t.fg == ColorEnc.pack(255, 255, 255))
            assert(t.bg == ColorEnc.Transparent)
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

    "ComputedStyle.fromTheme" - {
        "sets fg and bg from theme" in {
            val t  = ResolvedTheme.resolve(Theme.Default)
            val cs = ComputedStyle.fromTheme(t)
            assert(cs.fg == t.fg)
            assert(cs.bg == t.bg)
        }

        "preserves other defaults" in {
            val cs = ComputedStyle.fromTheme(defaultTheme)
            assert(!cs.bold)
            assert(cs.lineHeight == 1)
            assert(SizeEnc.isAuto(cs.width))
        }
    }

end ScreenStateTest
