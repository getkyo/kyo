package kyo

import kyo.Browser.*

/** Pointer/drag sessions over the server-push transport in real Chrome. A pointer-down starts a capture session; moves
  * are rAF-coalesced to at most one dispatch per animation frame (latest coordinates win); pointer-up ends it. Events are
  * synthesized via `new PointerEvent(...)` at coordinates offset from the element's live `getBoundingClientRect`, so the
  * handler's local `x`/`y` are deterministic regardless of page layout.
  */
class PointerScenarioItTest extends UITest:

    private def dragApp: UI < Async =
        for
            x     <- Signal.initRef(0.0)
            moves <- Signal.initRef(0)
            phase <- Signal.initRef("idle")
        yield UI.div(
            UI.div.id("drag").style(Style.width(200.px) ++ Style.height(200.px))
                .onPointerDown(_ => phase.set("down"))
                .onPointerMove(p => x.set(p.x).andThen(moves.getAndUpdate(_ + 1).unit))
                .onPointerUp(_ => phase.set("up"))("drag me"),
            x.map(v => UI.span(f"$v%.0f").id("x")),
            moves.map(n => UI.span(n.toString).id("moves")),
            phase.map(s => UI.span(s).id("phase"))
        )

    "pointer down/move/up delivers local coordinates and coalesces the move stream" in {
        withUI(dragApp) {
            for
                // Down + three synchronous moves in one turn: the three moves coalesce to a single rAF-scheduled
                // PointerMove carrying the LAST coordinates (x = clientX - rect.left = 90).
                _ <- Browser.evalDiscard(
                    "var el=document.getElementById('drag');var r=el.getBoundingClientRect();" +
                        "el.dispatchEvent(new PointerEvent('pointerdown',{pointerId:1,clientX:r.left+10,clientY:r.top+10,buttons:1,bubbles:true}));" +
                        "el.dispatchEvent(new PointerEvent('pointermove',{pointerId:1,clientX:r.left+20,clientY:r.top+20,buttons:1,bubbles:true}));" +
                        "el.dispatchEvent(new PointerEvent('pointermove',{pointerId:1,clientX:r.left+55,clientY:r.top+40,buttons:1,bubbles:true}));" +
                        "el.dispatchEvent(new PointerEvent('pointermove',{pointerId:1,clientX:r.left+90,clientY:r.top+60,buttons:1,bubbles:true}));"
                )
                _ <- Browser.assertText(Selector.id("phase"), "down")
                // Coalesced: three raw moves -> exactly one handler invocation.
                _ <- Browser.assertText(Selector.id("moves"), "1")
                // Latest coordinates win.
                _ <- Browser.assertText(Selector.id("x"), "90")
                _ <- Browser.evalDiscard(
                    "var el2=document.getElementById('drag');var r2=el2.getBoundingClientRect();" +
                        "el2.dispatchEvent(new PointerEvent('pointerup',{pointerId:1,clientX:r2.left+90,clientY:r2.top+60,buttons:0,bubbles:true}));"
                )
                _ <- Browser.assertText(Selector.id("phase"), "up")
            yield ()
        }
    }

    "pointer move outside an active session posts nothing" in {
        withUI(dragApp) {
            for
                _ <- Browser.evalDiscard(
                    "var el=document.getElementById('drag');var r=el.getBoundingClientRect();" +
                        "el.dispatchEvent(new PointerEvent('pointermove',{pointerId:1,clientX:r.left+30,clientY:r.top+30,buttons:0,bubbles:true}));"
                )
                _ <- Browser.assertText(Selector.id("moves"), "0")
                _ <- Browser.assertText(Selector.id("phase"), "idle")
            yield ()
        }
    }

end PointerScenarioItTest
