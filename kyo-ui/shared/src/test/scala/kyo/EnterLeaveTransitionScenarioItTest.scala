package kyo

import kyo.Browser.*

/** Declarative enter/leave CSS transition lifecycle over the server-push transport in real Chrome. The DomBackend
  * SPA mirror has no browser harness (same convention as FocusAutoScenarioItTest) and is kept in sync by construction.
  *
  * The transition is on a plain CSS class ("boxbase"), not the element id, because the leave ghost is a clone with
  * all `data-kyo-*`/`id` attributes stripped; a regular class survives the strip so the ghost actually transitions.
  */
class EnterLeaveTransitionScenarioItTest extends UITest:

    private val styleTag: String =
        """<style>.boxbase{opacity:1;transition:opacity 0.15s linear;} .efrom{opacity:0;} .lto{opacity:0;}</style>"""

    "enterTransition emits data-kyo-enter" in {
        withUI(UI.div(UI.div("hi").id("box").enterTransition("efrom"))) {
            Browser.assertAttribute(Selector.id("box"), "data-kyo-enter", "efrom").unit
        }
    }

    "leaveTransition emits data-kyo-leave" in {
        withUI(UI.div(UI.div("bye").id("box").leaveTransition("lto"))) {
            Browser.assertAttribute(Selector.id("box"), "data-kyo-leave", "lto").unit
        }
    }

    "toggling an enter element visible runs its enter transition then clears the enter class" in {
        val app: UI < Async =
            for visible <- Signal.initRef(false)
            yield UI.div(
                UI.rawHtml(styleTag),
                UI.button("show").id("show").onClick(visible.set(true)),
                UI.when(visible)(
                    UI.div("hi").id("box").cssClass("boxbase").enterTransition("efrom")
                )
            )
        withUI(app) {
            for
                // A transitionend on #box fires only if the enter class was applied (opacity 0) then removed
                // (opacity 1), i.e. the enter lifecycle actually ran.
                _ <- Browser.evalDiscard(
                    "window.__enterDone=false;document.body.addEventListener('transitionend',function(e){if(e.target&&e.target.id==='box')window.__enterDone=true;},true);"
                )
                _ <- Browser.click(Selector.id("show"))
                _ <- Browser.assertExists(Selector.id("box"))
                _ <- Browser.waitFor("window.__enterDone")
                _ <- Browser.waitFor("!document.getElementById('box').classList.contains('efrom')")
            yield ()
        }
    }

    "toggling a leave element hidden spawns a body-level ghost carrying the leave class, then removes it" in {
        val app: UI < Async =
            for visible <- Signal.initRef(true)
            yield UI.div(
                UI.rawHtml(styleTag),
                UI.button("hide").id("hide").onClick(visible.set(false)),
                UI.when(visible)(
                    UI.div("bye").id("box").cssClass("boxbase").leaveTransition("lto")
                )
            )
        withUI(app) {
            for
                _ <- Browser.assertExists(Selector.id("box"))
                // Observe ghost insertion into <body> and whether it later carries the leave class.
                _ <- Browser.evalDiscard(
                    "window.__ghostSeen=false;window.__ghostHadClass=false;new MutationObserver(function(ms){ms.forEach(function(m){for(var i=0;i<m.addedNodes.length;i++){var n=m.addedNodes[i];if(n.getAttribute&&n.getAttribute('data-kyo-ghost')){window.__ghostSeen=true;(function(nd){requestAnimationFrame(function(){requestAnimationFrame(function(){if(nd.classList&&nd.classList.contains('lto'))window.__ghostHadClass=true;});});})(n);}}});}).observe(document.body,{childList:true});"
                )
                _ <- Browser.click(Selector.id("hide"))
                _ <- Browser.assertNotExists(Selector.id("box"))
                _ <- Browser.waitFor("window.__ghostSeen")
                _ <- Browser.waitFor("window.__ghostHadClass")
                _ <- Browser.waitFor("document.querySelectorAll('[data-kyo-ghost]').length===0")
            yield ()
        }
    }

end EnterLeaveTransitionScenarioItTest
