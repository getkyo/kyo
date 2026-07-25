package kyo

import kyo.internal.DomBackend

extension (ui: UI.type)

    /** Mount a UI into the page body. */
    def runMount(u: UI)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u)

    /** Mount a UI into a specific DOM element by CSS selector. */
    def runMount(u: UI, selector: String)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u, selector)

    /** Mount into the page body with a session HANDLER-ERROR POLICY: the app's one central decision what
      * happens with an event-handler failure/panic nothing below consumed (e.g. surface it as a toast).
      * The engine logs the error out-of-band either way and never stops the bubble chain. Handlers keep
      * their local options: consume early (`Abort.recover`) or observe-and-bubble (`Abort.tap`).
      */
    def runMount(u: UI, onHandlerError: Throwable => Unit < Async)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u, onHandlerError)

    /** [[runMount]] into a CSS selector with a session handler-error policy — see the overload above. */
    def runMount(u: UI, selector: String, onHandlerError: Throwable => Unit < Async)(using
        Frame
    ): Unit < (Async & Scope) =
        DomBackend.mount(u, selector, Present(onHandlerError))

    /** Injects a [[kyo.Stylesheet]] into the live document (`<head>` `<style>`), client-side.
      * JS-only. Idempotent: a second call with the same rendered CSS text is a no-op and does
      * not append duplicate rules. Reuses the existing kyo-ui document stylesheet (the same
      * `<style>` element `runMount` appends per-element rules to), so a mounted app's authored
      * stylesheet and its per-element styles share one sheet.
      */
    def runStylesheet(sheet: Stylesheet)(using Frame): Unit < Sync =
        DomBackend.injectStylesheet(sheet)

end extension
