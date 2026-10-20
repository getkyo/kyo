package kyo

import kyo.Browser.*

class DragClientJsTest extends UITest:

    /** Tracks the pagehide listeners registered on the window from here on, dropping each one that is removed again. */
    private val trackPagehideListeners =
        """(function(){
          |  var listening=new Set(),add=window.addEventListener,remove=window.removeEventListener;
          |  window.addEventListener=function(type,fn){if(type==="pagehide")listening.add(fn);return add.apply(window,arguments);};
          |  window.removeEventListener=function(type,fn){if(type==="pagehide")listening.delete(fn);return remove.apply(window,arguments);};
          |  window.__kyoTestPagehide=listening;
          |})()""".stripMargin

    "reconnecting leaves exactly one drag runtime listening for pagehide" in {
        // Every socket that opens installs a drag runtime and every close tears it down, so however many times the page reconnects only the
        // current runtime is listening. Two drops tell a teardown that removes its pagehide listener (one left, the current runtime's) apart
        // from one that leaves it behind (one per socket opened since tracking began).
        val app: UI < Async =
            for clicks <- Signal.initRef(0)
            yield UI.div(
                UI.button("Count").id("b").onClick(clicks.updateAndGet(_ + 1).unit),
                clicks.map(count => UI.span(count.toString).id("v"))
            )
        withUI(app) {
            for
                // Delivered, so the first session is live before tracking starts.
                _ <- Browser.click(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("v"), "1")
                _ <- Browser.evalDiscard(trackPagehideListeners)
                // close() moves the socket out of OPEN at once, so each click is buffered and only the reconnected session can deliver it.
                // The count landing proves that session opened, which is where the new runtime is installed.
                _         <- Browser.eval("window.__kyoWs.close()")
                _         <- Browser.click(Selector.id("b"))
                _         <- Browser.assertText(Selector.id("v"), "2")
                _         <- Browser.eval("window.__kyoWs.close()")
                _         <- Browser.click(Selector.id("b"))
                _         <- Browser.assertText(Selector.id("v"), "3")
                listening <- Browser.evalInt("window.__kyoTestPagehide.size")
            yield assert(listening == 1, s"expected only the current runtime listening for pagehide, got $listening listeners")
        }
    }

    /** Presses the pointer on the element with `id` and moves it far enough to lift it. */
    private def press(id: String): String =
        s"""(function(){
           |function fire(el,t,x,y){el.dispatchEvent(new PointerEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y,pointerId:1}));}
           |var el=document.getElementById("$id");var r=el.getBoundingClientRect();
           |fire(el,"pointerdown",r.left+4,r.top+4);fire(el,"pointermove",r.left+4,r.top+24);fire(el,"pointermove",r.left+4,r.top+44);
           |return true;})()""".stripMargin

    /** The previews on the page while the element with `id` is lifted, or -1 while it is not. */
    private def previews(id: String): String =
        s"""(document.getElementById("$id").getAttribute("data-kyo-dragging")==="true")?
           |document.querySelectorAll("[data-kyo-drag-preview]").length:-1""".stripMargin

    /** Lets the pointer go over the element with `id`. */
    private def release(id: String): String =
        s"""(function(){var el=document.getElementById("$id");var r=el.getBoundingClientRect();
           |el.dispatchEvent(new PointerEvent("pointerup",{bubbles:true,cancelable:true,clientX:r.left+4,clientY:r.top+44,pointerId:1}));
           |return true;})()""".stripMargin

    "a pointer drag draws a clone of its source as the preview, and none for a source whose preview is hidden" in {
        val app: UI < Async = UI.div(
            UI.span("cloned").id("cloned").dragSource(Drag.Source("cloned", Chunk.empty, activation = Drag.Activation.Sensors)),
            UI.span("hidden").id("hidden")
                .dragSource(Drag.Source("hidden", Chunk.empty, preview = Drag.Preview.Hidden, activation = Drag.Activation.Sensors))
        )
        withUI(app) {
            for
                _      <- Browser.evalBoolean(press("cloned"))
                cloned <- Browser.evalInt(previews("cloned"))
                _      <- Browser.evalBoolean(release("cloned"))
                _      <- Browser.evalBoolean(press("hidden"))
                hidden <- Browser.evalInt(previews("hidden"))
                _      <- Browser.evalBoolean(release("hidden"))
            yield
                assert(cloned == 1)
                assert(hidden == 0)
        }
    }

end DragClientJsTest
