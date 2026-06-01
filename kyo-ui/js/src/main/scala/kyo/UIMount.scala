package kyo

import kyo.internal.DomBackend

extension (ui: UI.type)

    /** Mount a UI into the page body. */
    def runMount(u: UI)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u)

    /** Mount a UI into a specific DOM element by CSS selector. */
    def runMount(u: UI, selector: String)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u, selector)

    /** Injects a [[kyo.Stylesheet]] into the live document (`<head>` `<style>`), client-side.
      * JS-only. Idempotent per identical CSS text. Reuses the existing kyo-ui document stylesheet
      * (the same `<style>` element `runMount` appends per-element rules to), so a mounted app's
      * authored stylesheet and its per-element styles share one sheet.
      */
    def runStylesheet(sheet: Stylesheet)(using Frame): Unit < Sync =
        DomBackend.injectStylesheet(sheet)

end extension
