package kyo

import kyo.internal.DomBackend

extension (ui: UI.type)

    /** Mount a UI into the page body. */
    def runMount(u: UI)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u)

    /** Mount a UI into a specific DOM element by CSS selector. */
    def runMount(u: UI, selector: String)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u, selector)

    /** Hydrate an already-SSR'd page's backend nodes client-side: walk `u` (rebuilt from the same
      * builder the server rendered, so `data-kyo-path` matches by construction) and attach each
      * `Three.embed` canvas to its live mount, wiring the reactive bridge so server-pushed prop and
      * structural ops reach the live scene. Scope-bound like `runMount`: teardown runs each backend's
      * finalizers when the ambient scope closes. Unlike `runMount`, which renders a fresh tree into the
      * DOM, this attaches to the DOM the server already SSR'd, touching no `innerHTML`.
      */
    def runHydrate(u: UI)(using Frame): Unit < (Async & Scope) =
        DomBackend.hydrateBackendNodes(u)

    /** Injects a [[kyo.Stylesheet]] into the live document (`<head>` `<style>`), client-side.
      * JS-only. Idempotent: a second call with the same rendered CSS text is a no-op and does
      * not append duplicate rules. Reuses the existing kyo-ui document stylesheet (the same
      * `<style>` element `runMount` appends per-element rules to), so a mounted app's authored
      * stylesheet and its per-element styles share one sheet.
      */
    def runStylesheet(sheet: Stylesheet)(using Frame): Unit < Sync =
        DomBackend.injectStylesheet(sheet)

end extension
