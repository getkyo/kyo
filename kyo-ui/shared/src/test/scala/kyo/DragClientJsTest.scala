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

end DragClientJsTest
