package kyo

import kyo.internal.DomBackend

extension (ui: UI.type)

    /** Mount a UI into the page body. */
    def runMount(u: UI)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u)

    /** Mount a UI into a specific DOM element by CSS selector. */
    def runMount(u: UI, selector: String)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u, selector)

end extension
