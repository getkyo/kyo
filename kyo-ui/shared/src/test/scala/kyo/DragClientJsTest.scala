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

    private def previewPage(clicks: SignalRef[Int], activation: Drag.Activation)(using Frame): UI =
        def source(id: String, preview: Drag.Preview) =
            UI.span(id).id(id).dragSource(Drag.Source(id, Chunk.empty, preview = preview, activation = activation))
        UI.div(
            source("native", Drag.Preview.Native),
            source("clone", Drag.Preview.Clone),
            source("hidden", Drag.Preview.Hidden),
            source("label", Drag.Preview.Label("Badge")),
            UI.button("ready").id("ready").onClick(clicks.updateAndGet(_ + 1).unit),
            clicks.map(count => UI.span(count.toString).id("ready-count"))
        )
    end previewPage

    private def sensorPreviewSnapshot(id: String): String =
        s"""(function(){
           |var el=document.getElementById("$id"),r=el.getBoundingClientRect();
           |function fire(type,y,buttons){el.dispatchEvent(new PointerEvent(type,{bubbles:true,cancelable:true,clientX:r.left+4,clientY:y,pointerId:1,pointerType:"mouse",button:0,buttons:buttons}));}
           |fire("pointerdown",r.top+4,1);fire("pointermove",r.top+24,1);fire("pointermove",r.top+44,1);
           |var nodes=document.querySelectorAll("[data-kyo-drag-preview]"),count=nodes.length,text=count?nodes[0].textContent:"";
           |var dragging=el.getAttribute("data-kyo-dragging");fire("pointerup",r.top+44,0);
           |return ["$id",dragging,count,text,document.querySelectorAll("[data-kyo-drag-preview]").length].join("|");
           |})()""".stripMargin

    private def nativePreviewSnapshot(id: String): String =
        s"""(function(){
           |var el=document.getElementById("$id"),transfer=new DataTransfer(),images=[];
           |transfer.setDragImage=function(node){images.push(node);};
           |el.dispatchEvent(new DragEvent("dragstart",{bubbles:true,cancelable:true,dataTransfer:transfer}));
           |var nodes=document.querySelectorAll("[data-kyo-drag-preview]"),count=nodes.length,text=count?nodes[0].textContent:"",opacity=count?nodes[0].style.opacity:"";
           |el.dispatchEvent(new DragEvent("dragend",{bubbles:true,cancelable:true,dataTransfer:transfer}));
           |return ["$id",images.length,count,text,opacity,document.querySelectorAll("[data-kyo-drag-preview]").length].join("|");
           |})()""".stripMargin

    "sensor previews honor every declaration and are removed when the pointer is released" in {
        for
            clicks <- Signal.initRef(0)
            states <- withUI(previewPage(clicks, Drag.Activation.Sensors)) {
                for
                    _ <- Browser.click(Selector.id("ready"))
                    // The server's answer establishes that the session and its drag runtime are installed.
                    _      <- Browser.assertText(Selector.id("ready-count"), "1")
                    values <- Kyo.foreach(Chunk("native", "clone", "hidden", "label")) { id =>
                        Browser.eval(sensorPreviewSnapshot(id))
                    }
                yield values
            }
        yield assert(
            states == Chunk("native|true|1|native|0", "clone|true|1|clone|0", "hidden|true|0||0", "label|true|1|Badge|0"),
            s"$states"
        )
    }

    "native previews honor every declaration and are removed when the drag ends" in {
        for
            clicks <- Signal.initRef(0)
            states <- withUI(previewPage(clicks, Drag.Activation.Native)) {
                for
                    _      <- Browser.click(Selector.id("ready"))
                    _      <- Browser.assertText(Selector.id("ready-count"), "1")
                    values <- Kyo.foreach(Chunk("native", "clone", "hidden", "label")) { id =>
                        Browser.eval(nativePreviewSnapshot(id))
                    }
                yield values
            }
        yield assert(states == Chunk("native|0|0|||0", "clone|1|1|clone||0", "hidden|1|1||0|0", "label|1|1|Badge||0"), s"$states")
    }

    "a failing native image installation releases its node and cancels the drag" in {
        for
            clicks <- Signal.initRef(0)
            state  <- withUI(previewPage(clicks, Drag.Activation.Native)) {
                for
                    _     <- Browser.click(Selector.id("ready"))
                    _     <- Browser.assertText(Selector.id("ready-count"), "1")
                    value <- Browser.eval(
                        """(function(){var errors=[],record=function(e){errors.push(e.error.name);e.preventDefault();};
                          |window.addEventListener('error',record);
                          |try{var el=document.getElementById('clone'),transfer=new DataTransfer(),calls=0;
                          |transfer.setDragImage=function(){calls++;JSON.parse('');};
                          |var start=new DragEvent('dragstart',{bubbles:true,cancelable:true,dataTransfer:transfer});el.dispatchEvent(start);
                          |return [errors.join(','),calls,document.querySelectorAll('[data-kyo-drag-preview]').length,start.defaultPrevented].join('|');
                          |}finally{window.removeEventListener('error',record);}})()""".stripMargin
                    )
                yield value
            }
        yield assert(state == "SyntaxError|1|0|true", state)
    }

    "closing the page removes an active native preview" in {
        for
            clicks <- Signal.initRef(0)
            state  <- withUI(previewPage(clicks, Drag.Activation.Native)) {
                for
                    _     <- Browser.click(Selector.id("ready"))
                    _     <- Browser.assertText(Selector.id("ready-count"), "1")
                    value <- Browser.eval(
                        """(function(){var el=document.getElementById('clone'),transfer=new DataTransfer();
                          |transfer.setDragImage=function(){};
                          |el.dispatchEvent(new DragEvent('dragstart',{bubbles:true,cancelable:true,dataTransfer:transfer}));
                          |var before=document.querySelectorAll('[data-kyo-drag-preview]').length;
                          |window.dispatchEvent(new Event('pagehide'));
                          |return before+'|'+document.querySelectorAll('[data-kyo-drag-preview]').length;
                          |})()""".stripMargin
                    )
                yield value
            }
        yield assert(state == "1|0", state)
    }

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
