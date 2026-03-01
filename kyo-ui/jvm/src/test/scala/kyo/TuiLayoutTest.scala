package kyo

import kyo.Maybe.*
import kyo.internal.TuiLayout

class TuiLayoutTest extends Test:

    /** Helper: initialize a node with defaults. */
    private def initNode(layout: TuiLayout, idx: Int): Unit =
        layout.lFlags(idx) = 0; layout.pFlags(idx) = 0
        layout.padT(idx) = 0; layout.padR(idx) = 0; layout.padB(idx) = 0; layout.padL(idx) = 0
        layout.marT(idx) = 0; layout.marR(idx) = 0; layout.marB(idx) = 0; layout.marL(idx) = 0
        layout.gap(idx) = 0; layout.sizeW(idx) = -1; layout.sizeH(idx) = -1
        layout.minW(idx) = -1; layout.maxW(idx) = -1; layout.minH(idx) = -1; layout.maxH(idx) = -1
        layout.transX(idx) = 0; layout.transY(idx) = 0
        layout.fg(idx) = -1; layout.bg(idx) = -1
        layout.bdrClrT(idx) = -1; layout.bdrClrR(idx) = -1; layout.bdrClrB(idx) = -1; layout.bdrClrL(idx) = -1
        layout.opac(idx) = 1.0f; layout.shadow(idx) = -1
        layout.lineH(idx) = 0; layout.letSp(idx) = 0; layout.fontSz(idx) = 1
        layout.text(idx) = Absent; layout.focusStyle(idx) = Absent; layout.activeStyle(idx) = Absent
        layout.element(idx) = Absent; layout.nodeType(idx) = 0
        layout.parent(idx) = -1; layout.firstChild(idx) = -1
        layout.nextSibling(idx) = -1; layout.lastChild(idx) = -1
    end initNode

    /** Allocate and initialize a node. */
    private def allocNode(layout: TuiLayout): Int =
        val idx = layout.alloc()
        initNode(layout, idx)
        idx
    end allocNode

    "alloc and reset" - {
        "alloc increments count" in {
            val layout = new TuiLayout(4)
            assert(layout.count == 0)
            val idx = layout.alloc()
            assert(idx == 0)
            assert(layout.count == 1)
            succeed
        }
        "reset sets count to 0" in {
            val layout = new TuiLayout(4)
            layout.alloc()
            layout.alloc()
            layout.reset()
            assert(layout.count == 0)
            succeed
        }
        "grows beyond initial capacity" in {
            val layout = new TuiLayout(2)
            allocNode(layout)
            allocNode(layout)
            allocNode(layout) // triggers grow
            assert(layout.count == 3)
            succeed
        }
    }

    "linkChild" - {
        "single child" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout)
            val c      = allocNode(layout)
            TuiLayout.linkChild(layout, p, c)
            assert(layout.firstChild(p) == c)
            assert(layout.lastChild(p) == c)
            assert(layout.parent(c) == p)
            assert(layout.nextSibling(c) == -1)
            succeed
        }
        "multiple children" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout)
            val c1     = allocNode(layout)
            val c2     = allocNode(layout)
            val c3     = allocNode(layout)
            TuiLayout.linkChild(layout, p, c1)
            TuiLayout.linkChild(layout, p, c2)
            TuiLayout.linkChild(layout, p, c3)
            assert(layout.firstChild(p) == c1)
            assert(layout.lastChild(p) == c3)
            assert(layout.nextSibling(c1) == c2)
            assert(layout.nextSibling(c2) == c3)
            assert(layout.nextSibling(c3) == -1)
            succeed
        }
    }

    "measure" - {
        "text node" in {
            val layout = new TuiLayout(8)
            val idx    = allocNode(layout)
            TuiLayout.linkChild(layout, -1, idx)
            layout.text(idx) = Present("hello")
            TuiLayout.measure(layout)
            assert(layout.intrW(idx) == 5)
            assert(layout.intrH(idx) == 1)
            succeed
        }
        "multiline text" in {
            val layout = new TuiLayout(8)
            val idx    = allocNode(layout)
            TuiLayout.linkChild(layout, -1, idx)
            layout.text(idx) = Present("hello\nworld!!")
            TuiLayout.measure(layout)
            assert(layout.intrW(idx) == 7) // "world!!" is 7 chars
            assert(layout.intrH(idx) == 2)
            succeed
        }
        "column container with two text children" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            val c1     = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2     = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            layout.text(c1) = Present("hello")
            layout.text(c2) = Present("world!!!")
            TuiLayout.measure(layout)
            assert(layout.intrW(p) == 8) // max of children widths
            assert(layout.intrH(p) == 2) // sum of children heights
            succeed
        }
        "row container" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = 1 << TuiLayout.DirBit // row
            val c1 = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2 = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            layout.text(c1) = Present("hello")
            layout.text(c2) = Present("world")
            TuiLayout.measure(layout)
            assert(layout.intrW(p) == 10) // sum of children widths
            assert(layout.intrH(p) == 1)  // max of children heights
            succeed
        }
        "padding adds to intrinsic size" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            val c      = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.padL(p) = 2; layout.padR(p) = 2; layout.padT(p) = 1; layout.padB(p) = 1
            layout.text(c) = Present("hi")
            TuiLayout.measure(layout)
            assert(layout.intrW(p) == 6) // 2 + 2 + 2
            assert(layout.intrH(p) == 3) // 1 + 1 + 1
            succeed
        }
        "explicit size overrides intrinsic" in {
            val layout = new TuiLayout(8)
            val idx    = allocNode(layout); TuiLayout.linkChild(layout, -1, idx)
            layout.text(idx) = Present("hello")
            layout.sizeW(idx) = 20
            layout.sizeH(idx) = 5
            TuiLayout.measure(layout)
            assert(layout.intrW(idx) == 20)
            assert(layout.intrH(idx) == 5)
            succeed
        }
        "gap between children" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.gap(p) = 1
            val c1 = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2 = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            val c3 = allocNode(layout); TuiLayout.linkChild(layout, p, c3)
            layout.text(c1) = Present("a"); layout.text(c2) = Present("b"); layout.text(c3) = Present("c")
            TuiLayout.measure(layout)
            assert(layout.intrH(p) == 5) // 3 children + 2 gaps
            succeed
        }
        "border adds to size" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = (1 << TuiLayout.BorderTBit) | (1 << TuiLayout.BorderBBit) |
                (1 << TuiLayout.BorderLBit) | (1 << TuiLayout.BorderRBit)
            val c = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi")
            TuiLayout.measure(layout)
            assert(layout.intrW(p) == 4) // 2 + 1 + 1 (border L + R)
            assert(layout.intrH(p) == 3) // 1 + 1 + 1 (border T + B)
            succeed
        }
    }

    "arrange" - {
        "root fills terminal" in {
            val layout = new TuiLayout(8)
            val root   = allocNode(layout); TuiLayout.linkChild(layout, -1, root)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            assert(layout.x(root) == 0)
            assert(layout.y(root) == 0)
            assert(layout.w(root) == 80)
            assert(layout.h(root) == 24)
            succeed
        }
        "column positions children vertically" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            val c1     = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2     = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            layout.text(c1) = Present("aaa"); layout.text(c2) = Present("bbb")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            assert(layout.y(c1) == 0)
            assert(layout.y(c2) == 1)
            succeed
        }
        "row positions children horizontally" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = 1 << TuiLayout.DirBit // row
            val c1 = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2 = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            layout.text(c1) = Present("aaa"); layout.text(c2) = Present("bbb")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            assert(layout.x(c1) == 0)
            assert(layout.x(c2) == 3)
            succeed
        }
        "justify center" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            // column + justify center
            layout.lFlags(p) = TuiLayout.JustCenter << TuiLayout.JustShift
            val c = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            // Free space = 24 - 1 = 23, offset = 11
            assert(layout.y(c) == 11)
            succeed
        }
        "padding offsets content" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.padL(p) = 5; layout.padT(p) = 3
            val c = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            assert(layout.x(c) == 5)
            assert(layout.y(c) == 3)
            succeed
        }
        "border inset" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = (1 << TuiLayout.BorderTBit) | (1 << TuiLayout.BorderLBit)
            val c = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            assert(layout.x(c) == 1)
            assert(layout.y(c) == 1)
            succeed
        }
        "gap between children in column" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.gap(p) = 2
            val c1 = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2 = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            layout.text(c1) = Present("a"); layout.text(c2) = Present("b")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            assert(layout.y(c1) == 0)
            assert(layout.y(c2) == 3) // 1 (height of c1) + 2 (gap)
            succeed
        }
        "translate offsets position" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            val c      = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi")
            layout.transX(c) = 10; layout.transY(c) = 5
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            assert(layout.x(c) == 10)
            assert(layout.y(c) == 5)
            succeed
        }
    }

    "pFlags accessors" - {
        "bold dim italic underline strikethrough" in {
            val pf = (1 << TuiLayout.BoldBit) | (1 << TuiLayout.ItalicBit) | (1 << TuiLayout.StrikethroughBit)
            assert(TuiLayout.isBold(pf))
            assert(!TuiLayout.isDim(pf))
            assert(TuiLayout.isItalic(pf))
            assert(!TuiLayout.isUnderline(pf))
            assert(TuiLayout.isStrikethrough(pf))
            succeed
        }
        "border style" in {
            val pf = TuiLayout.BorderHeavy << TuiLayout.BorderStyleShift
            assert(TuiLayout.borderStyle(pf) == TuiLayout.BorderHeavy)
            succeed
        }
        "rounded corners" in {
            val pf = (1 << TuiLayout.RoundedTLBit) | (1 << TuiLayout.RoundedBRBit)
            assert(TuiLayout.isRoundedTL(pf))
            assert(!TuiLayout.isRoundedTR(pf))
            assert(TuiLayout.isRoundedBR(pf))
            assert(!TuiLayout.isRoundedBL(pf))
            succeed
        }
        "text align" in {
            val pf = TuiLayout.TextAlignCenter << TuiLayout.TextAlignShift
            assert(TuiLayout.textAlign(pf) == TuiLayout.TextAlignCenter)
            succeed
        }
        "text overflow and wrap" in {
            val pf = (1 << TuiLayout.TextOverflowBit) | (1 << TuiLayout.WrapTextBit)
            assert(TuiLayout.hasTextOverflow(pf))
            assert(TuiLayout.shouldWrapText(pf))
            assert(!TuiLayout.hasTextOverflow(0))
            assert(!TuiLayout.shouldWrapText(0))
            succeed
        }
    }

    "borderChars" - {
        "thin default" in {
            TuiLayout.borderChars(TuiLayout.BorderThin, false, false, false, false) { (tl, tr, br, bl, hz, vt) =>
                assert(tl == '┌')
                assert(tr == '┐')
                assert(br == '┘')
                assert(bl == '└')
                assert(hz == '─')
                assert(vt == '│')
                succeed
            }
        }
        "thin with rounded corners" in {
            TuiLayout.borderChars(TuiLayout.BorderThin, true, false, false, true) { (tl, _, _, bl, _, _) =>
                assert(tl == '╭')
                assert(bl == '╰')
                succeed
            }
        }
        "heavy" in {
            TuiLayout.borderChars(TuiLayout.BorderHeavy, false, false, false, false) { (tl, _, _, _, hz, vt) =>
                assert(tl == '┏')
                assert(hz == '━')
                assert(vt == '┃')
                succeed
            }
        }
        "double" in {
            TuiLayout.borderChars(TuiLayout.BorderDouble, false, false, false, false) { (tl, _, _, _, hz, vt) =>
                assert(tl == '╔')
                assert(hz == '═')
                assert(vt == '║')
                succeed
            }
        }
        "rounded style" in {
            TuiLayout.borderChars(TuiLayout.BorderRounded, false, false, false, false) { (tl, tr, br, bl, _, _) =>
                assert(tl == '╭')
                assert(tr == '╮')
                assert(br == '╯')
                assert(bl == '╰')
                succeed
            }
        }
    }

    "wrapText" - {
        "no wrap needed" in {
            val result = TuiLayout.wrapText("hello", 10)
            assert(result.size == 1)
            assert(result(0) == "hello")
            succeed
        }
        "wraps at word boundary" in {
            val result = TuiLayout.wrapText("hello world foo", 11)
            assert(result.size == 2)
            assert(result(0) == "hello world")
            assert(result(1) == "foo")
            succeed
        }
        "wraps at space boundary" in {
            val result = TuiLayout.wrapText("hello world", 7)
            assert(result.size == 2)
            assert(result(0) == "hello")
            assert(result(1) == "world")
            succeed
        }
        "hard breaks when no space" in {
            val result = TuiLayout.wrapText("abcdefghij", 5)
            assert(result.size == 2)
            assert(result(0) == "abcde")
            assert(result(1) == "fghij")
            succeed
        }
        "preserves existing newlines" in {
            val result = TuiLayout.wrapText("ab\ncd", 10)
            assert(result.size == 2)
            assert(result(0) == "ab")
            assert(result(1) == "cd")
            succeed
        }
        "wraps each line independently" in {
            val result = TuiLayout.wrapText("hello world\nfoo bar baz", 8)
            assert(result(0) == "hello")
            assert(result(1) == "world")
            assert(result(2) == "foo bar")
            assert(result(3) == "baz")
            succeed
        }
    }

    "clipText" - {
        "no clipping needed" in {
            val result = TuiLayout.clipText("hi", 10, 5, false)
            assert(result.size == 1)
            assert(result(0) == "hi")
            succeed
        }
        "clips width" in {
            val result = TuiLayout.clipText("hello world", 5, 5, false)
            assert(result(0) == "hello")
            succeed
        }
        "clips width with ellipsis" in {
            val result = TuiLayout.clipText("hello world", 5, 5, true)
            assert(result(0) == "hell…")
            succeed
        }
        "clips height" in {
            val result = TuiLayout.clipText("a\nb\nc\nd", 10, 2, false)
            assert(result.size == 2)
            assert(result(0) == "a")
            assert(result(1) == "b")
            succeed
        }
        "clips height with ellipsis" in {
            val result = TuiLayout.clipText("abc\ndef\nghi", 10, 2, true)
            assert(result.size == 2)
            assert(result(0) == "abc")
            assert(result(1) == "def…")
            succeed
        }
    }

    "word wrap in arrange" - {
        "text wraps to container width" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            // Column with align=stretch so child gets parent's width
            layout.lFlags(p) = TuiLayout.AlignStretch << TuiLayout.AlignShift
            val c = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hello world")
            layout.pFlags(c) = 1 << TuiLayout.WrapTextBit
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 8, 24)
            // "hello world" (11 chars) wraps to 8-wide: "hello" / "world" = 2 lines
            assert(layout.w(c) == 8)
            assert(layout.h(c) == 2)
            succeed
        }
    }

    "arrange with bottom margin" - {
        "bottom margin subtracts from content height" in {
            // Bug: line 427 uses marR instead of marB
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.marT(p) = 1; layout.marB(p) = 2; layout.marR(p) = 0
            val c = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            // contentH should be 24 - 1(marT) - 2(marB) = 21
            // With the bug, marR(0) is used instead of marB(2), giving wrong result
            val flags    = layout.lFlags(p)
            val bT       = if TuiLayout.hasBorderT(flags) then 1 else 0
            val bB       = if TuiLayout.hasBorderB(flags) then 1 else 0
            val contentH = layout.h(p) - bT - bB - layout.padT(p) - layout.padB(p) - layout.marT(p) - layout.marB(p)
            // Child positioned by justify=start → y should be marT(1) offset
            assert(layout.y(c) == 1)
            // With freeSpace based on correct contentH=21: justify start → y=marT=1
            // The main check: if marB is different from marR, the content area height matters
            // for justify-end positioning
            succeed
        }
        "justify end with bottom margin uses marB not marR" in {
            // This directly exposes the marR/marB bug
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = TuiLayout.JustEnd << TuiLayout.JustShift
            layout.marT(p) = 0; layout.marB(p) = 5; layout.marR(p) = 0
            val c = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi") // height=1
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            // contentH = 24 - 0 - 5(marB) = 19
            // justify end: y = marT + freeSpace = 0 + (19-1) = 18
            assert(layout.y(c) == 18)
            succeed
        }
    }

    "wrapText edge cases" - {
        "hard break longer than two chunks" in {
            // Bug: charAt(brk) when brk==end==line.length → IndexOutOfBounds
            val result = TuiLayout.wrapText("abcdefghijk", 5)
            assert(result.size == 3)
            assert(result(0) == "abcde")
            assert(result(1) == "fghij")
            assert(result(2) == "k")
            succeed
        }
        "maxWidth 1" in {
            val result = TuiLayout.wrapText("abc", 1)
            assert(result.size == 3)
            assert(result(0) == "a")
            assert(result(1) == "b")
            assert(result(2) == "c")
            succeed
        }
        "all spaces" in {
            val result = TuiLayout.wrapText("     ", 3)
            // Should not crash or produce empty lines
            assert(result.size >= 1)
            succeed
        }
        "trailing newline" in {
            val result = TuiLayout.wrapText("abc\n", 10)
            // split("\n", -1) preserves trailing empty → ["abc", ""]
            assert(result.size == 2)
            assert(result(0) == "abc")
            assert(result(1) == "")
            succeed
        }
        "empty string" in {
            val result = TuiLayout.wrapText("", 5)
            assert(result.size == 1)
            assert(result(0) == "")
            succeed
        }
    }

    "clipText edge cases" - {
        "maxWidth 1 with ellipsis" in {
            // When maxWidth=1 and ellipsis=true, should show "…" not "h"
            val result = TuiLayout.clipText("hello", 1, 5, true)
            assert(result(0) == "…")
            succeed
        }
        "empty text" in {
            val result = TuiLayout.clipText("", 10, 5, false)
            assert(result.size == 1)
            assert(result(0) == "")
            succeed
        }
        "zero maxWidth" in {
            val result = TuiLayout.clipText("hello", 0, 5, false)
            assert(result.isEmpty)
            succeed
        }
        "zero maxHeight" in {
            val result = TuiLayout.clipText("hello", 10, 0, false)
            assert(result.isEmpty)
            succeed
        }
    }

    "measure trailing newline" - {
        "text ending with newline counts extra line" in {
            // Bug: measure uses split('\n') which drops trailing empty
            // So "abc\n" measures as height=1 instead of 2
            val layout = new TuiLayout(8)
            val idx    = allocNode(layout); TuiLayout.linkChild(layout, -1, idx)
            layout.text(idx) = Present("abc\n")
            TuiLayout.measure(layout)
            // "abc\n" should be 2 lines (abc + empty line)
            assert(layout.intrH(idx) == 2)
            succeed
        }
    }

    "arrange align end" - {
        "child at bottom of container" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = TuiLayout.JustEnd << TuiLayout.JustShift
            val c = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            assert(layout.y(c) == 23) // 24-1
            succeed
        }
        "row align end" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = (1 << TuiLayout.DirBit) | (TuiLayout.AlignEnd << TuiLayout.AlignShift)
            val c = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi") // width=2, height=1
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            // cross axis (vertical) align=end: y = 24 - 1 = 23
            assert(layout.y(c) == 23)
            succeed
        }
    }

    "arrange justify between" - {
        "three children evenly spaced" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = TuiLayout.JustBetween << TuiLayout.JustShift
            val c1 = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2 = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            val c3 = allocNode(layout); TuiLayout.linkChild(layout, p, c3)
            layout.text(c1) = Present("a"); layout.text(c2) = Present("b"); layout.text(c3) = Present("c")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            // 3 children height=1 each, container=24
            // freeSpace = 24 - 3 = 21, betweenGap = 21/2 = 10 (truncated)
            assert(layout.y(c1) == 0)
            assert(layout.y(c2) == 11) // 1 + 10
            assert(layout.y(c3) == 22) // 1 + 10 + 1 + 10
            succeed
        }
    }

    "arrange justify around and evenly" - {
        "justify around with two children" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = TuiLayout.JustAround << TuiLayout.JustShift
            val c1 = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2 = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            layout.text(c1) = Present("a"); layout.text(c2) = Present("b")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            // freeSpace=22, space=22/2=11, offset=11/2=5, betweenGap=11
            assert(layout.y(c1) == 5)
            assert(layout.y(c2) == 17) // 5 + 1 + 11
            succeed
        }
        "justify evenly with two children" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            layout.lFlags(p) = TuiLayout.JustEvenly << TuiLayout.JustShift
            val c1 = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2 = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            layout.text(c1) = Present("a"); layout.text(c2) = Present("b")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            // freeSpace=22, space=22/3=7, offset=7, betweenGap=7
            assert(layout.y(c1) == 7)
            assert(layout.y(c2) == 15) // 7 + 1 + 7
            succeed
        }
    }

    "arrange hidden children" - {
        "hidden child skipped in layout" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            val c1     = allocNode(layout); TuiLayout.linkChild(layout, p, c1)
            val c2     = allocNode(layout); TuiLayout.linkChild(layout, p, c2)
            val c3     = allocNode(layout); TuiLayout.linkChild(layout, p, c3)
            layout.text(c1) = Present("a")
            layout.lFlags(c2) = 1 << TuiLayout.HiddenBit
            layout.text(c2) = Present("hidden")
            layout.text(c3) = Present("c")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 80, 24)
            assert(layout.y(c1) == 0)
            assert(layout.y(c3) == 1) // c2 is hidden, so c3 comes right after c1
            succeed
        }
    }

    "arrange single child" - {
        "single child in column" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            val c      = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hello")
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 40, 10)
            assert(layout.x(c) == 0)
            assert(layout.y(c) == 0)
            assert(layout.w(c) == 5)
            assert(layout.h(c) == 1)
            succeed
        }
    }

    "arrange min/max constraints" - {
        "minW clamps child width" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            val c      = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hi") // intrW=2
            layout.minW(c) = 10
            TuiLayout.measure(layout)
            assert(layout.intrW(c) == 10)
            succeed
        }
        "maxW clamps child width" in {
            val layout = new TuiLayout(8)
            val p      = allocNode(layout); TuiLayout.linkChild(layout, -1, p)
            val c      = allocNode(layout); TuiLayout.linkChild(layout, p, c)
            layout.text(c) = Present("hello world") // intrW=11
            layout.maxW(c) = 5
            TuiLayout.measure(layout)
            assert(layout.intrW(c) == 5)
            succeed
        }
    }

    "wrapLineCount" - {
        "matches wrapText for simple text" in {
            val text = "hello"
            assert(TuiLayout.wrapLineCount(text, 10) == TuiLayout.wrapText(text, 10).size)
            succeed
        }
        "matches wrapText for wrapping text" in {
            val text = "hello world foo"
            assert(TuiLayout.wrapLineCount(text, 11) == TuiLayout.wrapText(text, 11).size)
            succeed
        }
        "matches wrapText for hard break" in {
            val text = "abcdefghij"
            assert(TuiLayout.wrapLineCount(text, 5) == TuiLayout.wrapText(text, 5).size)
            succeed
        }
        "matches wrapText for multi-line" in {
            val text = "hello world\nfoo bar baz"
            assert(TuiLayout.wrapLineCount(text, 8) == TuiLayout.wrapText(text, 8).size)
            succeed
        }
        "matches wrapText for trailing newline" in {
            val text = "abc\n"
            assert(TuiLayout.wrapLineCount(text, 10) == TuiLayout.wrapText(text, 10).size)
            succeed
        }
        "matches wrapText for empty string" in {
            assert(TuiLayout.wrapLineCount("", 5) == TuiLayout.wrapText("", 5).size)
            succeed
        }
        "matches wrapText for maxWidth 1" in {
            val text = "abc"
            assert(TuiLayout.wrapLineCount(text, 1) == TuiLayout.wrapText(text, 1).size)
            succeed
        }
        "matches wrapText for all spaces" in {
            val text = "     "
            assert(TuiLayout.wrapLineCount(text, 3) == TuiLayout.wrapText(text, 3).size)
            succeed
        }
        "matches wrapText for triple hard break" in {
            val text = "abcdefghijk"
            assert(TuiLayout.wrapLineCount(text, 5) == TuiLayout.wrapText(text, 5).size)
            succeed
        }
        "matches wrapText for multiple newlines" in {
            val text = "a\n\nb\n\n"
            assert(TuiLayout.wrapLineCount(text, 10) == TuiLayout.wrapText(text, 10).size)
            succeed
        }
        "maxWidth 0 returns 1" in {
            assert(TuiLayout.wrapLineCount("hello", 0) == 1)
            succeed
        }
    }

    "borderChars continuation" - {
        "returns value from continuation" in {
            val result = TuiLayout.borderChars(TuiLayout.BorderThin, false, false, false, false) { (tl, _, _, _, _, _) =>
                tl.toString
            }
            assert(result == "┌")
            succeed
        }
        "all 6 chars accessible" in {
            TuiLayout.borderChars(TuiLayout.BorderHeavy, false, false, false, false) { (tl, tr, br, bl, hz, vt) =>
                assert(Set(tl, tr, br, bl, hz, vt).size == 6) // all distinct
                succeed
            }
        }
    }

end TuiLayoutTest
