package kyo

import kyo.Maybe.*
import kyo.internal.*

class TuiFocusTest extends Test with UIScope:

    // ──────────────────────── Helpers ────────────────────────

    private def mkLayout(cap: Int = 64): TuiLayout = new TuiLayout(cap)

    private def addNode(
        layout: TuiLayout,
        parentIdx: Int,
        nodeType: Int = TuiLayout.NodeDiv,
        hidden: Boolean = false,
        disabled: Boolean = false
    ): Int =
        val idx = layout.alloc()
        TuiLayout.linkChild(layout, parentIdx, idx)
        TuiStyle.setDefaults(layout, idx)
        layout.nodeType(idx) = nodeType.toByte
        if hidden then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.HiddenBit)
        if disabled then layout.lFlags(idx) = layout.lFlags(idx) | (1 << TuiLayout.DisabledBit)
        layout.x(idx) = 0; layout.y(idx) = 0; layout.w(idx) = 10; layout.h(idx) = 1
        idx
    end addNode

    // ──────────────────────── Initial State ────────────────────────

    "initial state" - {
        "focusedIndex is -1" in {
            val f = new TuiFocus
            assert(f.focusedIndex == -1)
        }
    }

    // ──────────────────────── Scan ────────────────────────

    "scan" - {
        "finds button" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn  = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == btn)
        }

        "finds input" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val inp  = addNode(l, root, nodeType = TuiLayout.NodeInput)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == inp)
        }

        "finds textarea" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val ta   = addNode(l, root, nodeType = TuiLayout.NodeTextarea)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == ta)
        }

        "finds select" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val sel  = addNode(l, root, nodeType = TuiLayout.NodeSelect)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == sel)
        }

        "finds anchor" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val a    = addNode(l, root, nodeType = TuiLayout.NodeAnchor)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == a)
        }

        "skips hidden elements" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn  = addNode(l, root, nodeType = TuiLayout.NodeButton, hidden = true)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == -1)
        }

        "skips disabled elements" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn  = addNode(l, root, nodeType = TuiLayout.NodeButton, disabled = true)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == -1)
        }

        "skips non-focusable elements" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val div  = addNode(l, root, nodeType = TuiLayout.NodeDiv)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == -1)
        }

        "finds multiple focusable elements" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val inp  = addNode(l, root, nodeType = TuiLayout.NodeInput)
            val f    = new TuiFocus
            f.scan(l)
            // First focusable element gets focus
            assert(f.focusedIndex == btn1)
        }

        "mixed visible and hidden" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val _    = addNode(l, root, nodeType = TuiLayout.NodeButton, hidden = true)
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val _    = addNode(l, root, nodeType = TuiLayout.NodeInput, disabled = true)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == btn2)
        }

        "empty layout" in {
            val l = mkLayout()
            val f = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == -1)
        }

        "no focusable elements" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val _    = addNode(l, root, nodeType = TuiLayout.NodeDiv)
            val _    = addNode(l, root, nodeType = TuiLayout.NodeText)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == -1)
        }
    }

    // ──────────────────────── Navigation ────────────────────────

    "next" - {
        "advances to next element" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == btn1)
            f.next()
            assert(f.focusedIndex == btn2)
        }

        "wraps around" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            f.next() // btn2
            f.next() // wrap to btn1
            assert(f.focusedIndex == btn1)
        }

        "no-op when no focusable elements" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val f    = new TuiFocus
            f.scan(l)
            f.next()
            assert(f.focusedIndex == -1)
        }

        "single element stays focused" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn  = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            f.next()
            assert(f.focusedIndex == btn)
        }
    }

    "prev" - {
        "goes to previous element" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            f.next() // btn2
            f.prev() // btn1
            assert(f.focusedIndex == btn1)
        }

        "wraps around backwards" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            f.prev() // wrap to btn2
            assert(f.focusedIndex == btn2)
        }

        "no-op when no focusable elements" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val f    = new TuiFocus
            f.scan(l)
            f.prev()
            assert(f.focusedIndex == -1)
        }
    }

    "cycle through 3 elements" in {
        val l    = mkLayout()
        val root = addNode(l, -1)
        val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
        val btn2 = addNode(l, root, nodeType = TuiLayout.NodeInput)
        val btn3 = addNode(l, root, nodeType = TuiLayout.NodeTextarea)
        val f    = new TuiFocus
        f.scan(l)
        assert(f.focusedIndex == btn1)
        f.next(); assert(f.focusedIndex == btn2)
        f.next(); assert(f.focusedIndex == btn3)
        f.next(); assert(f.focusedIndex == btn1) // wrap
        f.prev(); assert(f.focusedIndex == btn3) // wrap back
        f.prev(); assert(f.focusedIndex == btn2)
        f.prev(); assert(f.focusedIndex == btn1)
    }

    // ──────────────────────── Focus Style ────────────────────────

    "applyFocusStyle" - {
        "default highlight sets border bits" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn  = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            f.applyFocusStyle(l)
            val lf = l.lFlags(btn)
            assert(TuiLayout.hasBorderT(lf))
            assert(TuiLayout.hasBorderR(lf))
            assert(TuiLayout.hasBorderB(lf))
            assert(TuiLayout.hasBorderL(lf))
        }

        "default highlight sets blue border color" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn  = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            f.applyFocusStyle(l)
            val blue = TuiColor.pack(122, 162, 247)
            assert(l.bdrClrT(btn) == blue)
            assert(l.bdrClrR(btn) == blue)
            assert(l.bdrClrB(btn) == blue)
            assert(l.bdrClrL(btn) == blue)
        }

        "default highlight sets border style to thin" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn  = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            f.applyFocusStyle(l)
            assert(TuiLayout.borderStyle(l.pFlags(btn)) == TuiLayout.BorderThin)
        }

        "preserves existing border style if not none" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn  = addNode(l, root, nodeType = TuiLayout.NodeButton)
            // Set dashed border style
            l.pFlags(btn) = (l.pFlags(btn) & ~(TuiLayout.BorderStyleMask << TuiLayout.BorderStyleShift)) |
                (TuiLayout.BorderDashed << TuiLayout.BorderStyleShift)
            val f = new TuiFocus
            f.scan(l)
            f.applyFocusStyle(l)
            assert(TuiLayout.borderStyle(l.pFlags(btn)) == TuiLayout.BorderDashed)
        }

        "no-op when no focused element" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val f    = new TuiFocus
            f.scan(l)
            f.applyFocusStyle(l) // should not crash
            succeed
        }

        "applies to correct element after next()" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            f.next()
            f.applyFocusStyle(l)
            // btn2 should have border, btn1 should not
            assert(TuiLayout.hasBorderT(l.lFlags(btn2)))
            assert(!TuiLayout.hasBorderT(l.lFlags(btn1)))
        }
    }

    // ──────────────────────── Dispatch ────────────────────────

    "dispatch" - {
        "Tab advances focus" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == btn1)
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            Sync.Unsafe.evalOrThrow(f.dispatch(InputEvent.Key("Tab"), l))
            assert(f.focusedIndex == btn2)
        }

        "Shift-Tab goes back" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            val f    = new TuiFocus
            f.scan(l)
            f.next() // btn2
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            Sync.Unsafe.evalOrThrow(f.dispatch(InputEvent.Key("Tab", shift = true), l))
            assert(f.focusedIndex == btn1)
        }

        "non-Tab key on no focus is no-op" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val f    = new TuiFocus
            f.scan(l)
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            Sync.Unsafe.evalOrThrow(f.dispatch(InputEvent.Key("a"), l))
            succeed
        }

        "mouse event on no focus is no-op" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val f    = new TuiFocus
            f.scan(l)
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            Sync.Unsafe.evalOrThrow(f.dispatch(InputEvent.Mouse(InputEvent.MouseKind.LeftPress, 0, 0), l))
            succeed
        }
    }

    // ──────────────────────── Mouse Click ────────────────────────

    "mouse click" - {
        "left click on button focuses it" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn1 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            l.x(btn1) = 0; l.y(btn1) = 0; l.w(btn1) = 10; l.h(btn1) = 3
            val btn2 = addNode(l, root, nodeType = TuiLayout.NodeButton)
            l.x(btn2) = 0; l.y(btn2) = 5; l.w(btn2) = 10; l.h(btn2) = 3
            val f = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == btn1) // initially focuses first
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            // Click on btn2's area
            Sync.Unsafe.evalOrThrow(f.dispatch(InputEvent.Mouse(InputEvent.MouseKind.LeftPress, 5, 6), l))
            assert(f.focusedIndex == btn2)
        }

        "left click outside buttons does nothing" in {
            val l    = mkLayout()
            val root = addNode(l, -1)
            val btn  = addNode(l, root, nodeType = TuiLayout.NodeButton)
            l.x(btn) = 10; l.y(btn) = 10; l.w(btn) = 5; l.h(btn) = 3
            val f = new TuiFocus
            f.scan(l)
            assert(f.focusedIndex == btn)
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            Sync.Unsafe.evalOrThrow(f.dispatch(InputEvent.Mouse(InputEvent.MouseKind.LeftPress, 0, 0), l))
            // Focus should stay on btn (no other focusable hit)
            assert(f.focusedIndex == btn)
        }

        "left click fires button onClick" in {
            import AllowUnsafe.embrace.danger
            given Frame = Frame.internal
            var clicked = false
            val btn = UI.AST.Button(
                common = UI.AST.CommonAttrs(onClick = Maybe(Sync.defer { clicked = true }))
            )("Click me")
            val ui: UI = div(btn)
            // Use full pipeline to get real layout
            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 40, 10)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 40, 10)
            val focus = new TuiFocus
            focus.scan(layout)
            // Find the button's position
            val btnIdx = focus.focusedIndex
            assert(btnIdx >= 0, "Button should be focusable")
            val bx = layout.x(btnIdx)
            val by = layout.y(btnIdx)
            // Click on the button
            Sync.Unsafe.evalOrThrow(focus.dispatch(InputEvent.Mouse(InputEvent.MouseKind.LeftPress, bx, by), layout))
            // Give async fiber a moment
            Thread.sleep(50)
            assert(clicked, "onClick should have been fired")
        }

        "left click on second button fires its onClick not first" in {
            import AllowUnsafe.embrace.danger
            given Frame  = Frame.internal
            var clicked1 = false
            var clicked2 = false
            val btn1 = UI.AST.Button(
                common = UI.AST.CommonAttrs(onClick = Maybe(Sync.defer { clicked1 = true }))
            )("First")
            val btn2 = UI.AST.Button(
                common = UI.AST.CommonAttrs(onClick = Maybe(Sync.defer { clicked2 = true }))
            )("Second")
            val ui: UI  = div(btn1, btn2)
            val layout  = new TuiLayout(64)
            val signals = new TuiSignalCollector(16)
            TuiFlatten.flatten(ui, layout, signals, 40, 20)
            TuiLayout.measure(layout)
            TuiLayout.arrange(layout, 40, 20)
            val focus = new TuiFocus
            focus.scan(layout)
            // Find second button position — it should be below the first
            // Scan focusable indices
            val btn1Idx = focus.focusedIndex
            focus.next()
            val btn2Idx = focus.focusedIndex
            assert(btn2Idx != btn1Idx, "Should have two distinct buttons")
            val bx = layout.x(btn2Idx)
            val by = layout.y(btn2Idx)
            // Reset focus to first
            focus.prev()
            assert(focus.focusedIndex == btn1Idx)
            // Click on second button
            Sync.Unsafe.evalOrThrow(focus.dispatch(InputEvent.Mouse(InputEvent.MouseKind.LeftPress, bx, by), layout))
            Thread.sleep(50)
            assert(!clicked1, "First button onClick should NOT have been fired")
            assert(clicked2, "Second button onClick should have been fired")
            assert(focus.focusedIndex == btn2Idx, "Focus should move to clicked button")
        }
    }

end TuiFocusTest
