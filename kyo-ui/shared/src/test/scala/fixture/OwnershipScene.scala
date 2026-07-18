package fixture

import kyo.*

/** The page that proves who OWNS what on a hydrated, server-rendered page.
  *
  * `Element.clientOwned` splits the page in two: the WS session drives everything outside the boundary and
  * the hydrating browser drives everything inside it. The hydrate half of that split lives in
  * `DomBackend.hydrateBackendNodes`, and it is only reachable through `UI.runHydrate`, which needs a real
  * SSR'd page and a real client bundle. That is what this scene and its hydrate entry exist to provide.
  *
  * Both halves of the split are made VISIBLE, and each is visible in a way the other cannot fake:
  *
  *   - The client-owned region reads `clientRef`, which the browser's own builder writes AFTER hydrating
  *     ([[fromBrowser]]). The server never writes that value, so seeing it means the browser subscribed the
  *     region it owns. If the hydrate subscribed nothing, this region would sit at its SSR'd value forever.
  *
  *   - The server-owned region reads `serverRef`, which the browser's builder deliberately writes
  *     [[clientClobber]] into. The server never writes that value either, so seeing it means the browser
  *     subscribed a region it does NOT own, and is a second writer against the session. If the hydrate
  *     subscribed everything, this region would read the clobber.
  *
  * The two buttons do the same job for the EVENT half. The client-owned button writes `clientRef`, so the
  * browser must run it. The server-owned button writes BOTH refs, so if the browser wrongly ran its local
  * copy of a handler it does not own, the client-owned region would change, which is the only way that
  * mistake can be seen from outside: the browser and the session hold separate signal instances, so a
  * handler the browser runs against its own tree is otherwise invisible.
  */
object OwnershipScene:

    /** What the browser's builder writes into `clientRef` after hydrating. The server never writes it. */
    val fromBrowser: String = "from-browser"

    /** What the browser's builder writes into `serverRef`, a region it must NOT drive. The server never
      * writes it, so its appearance in the page is proof of a second writer.
      */
    val clientClobber: String = "client-clobbered-a-server-region"

    /** What the SERVER's handler writes when the server-owned button is clicked. */
    val serverHandlerRan: String = "server-handler-ran"

    /** What the server-owned button's handler writes into `clientRef`. Only the browser running a handler it
      * does not own can make this reach the page, because the session never pushes a client-owned region.
      */
    val serverHandlerTouchedClient: String = "server-handler-touched-client"

    /** What the CLIENT-owned button's handler writes. The browser must run it; the session must not. */
    val clientHandlerRan: String = "client-handler-ran"

    def ui(serverRef: SignalRef[String], clientRef: SignalRef[String])(using Frame): UI =
        UI.div(
            serverRef.map(v => UI.span(v).id("server-region")),
            UI.button("server").id("server-btn").onClick(
                serverRef.set(serverHandlerRan).andThen(clientRef.set(serverHandlerTouchedClient))
            ),
            UI.div(
                clientRef.map(v => UI.span(v).id("client-region")),
                UI.button("client").id("client-btn").onClick(clientRef.set(clientHandlerRan))
            ).id("owned").clientOwned
        )
    end ui

end OwnershipScene
