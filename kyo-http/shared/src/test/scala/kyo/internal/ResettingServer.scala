package kyo.internal

import kyo.*

/** A raw TCP server that ends its one connection with a reset instead of a FIN, which kyo-net's public API cannot produce: the reset needs
  * SO_LINGER {1, 0} on the socket, so each platform supplies its own socket in [[ResettingServerImpl]].
  */
private[kyo] trait ResettingServer:

    /** Listens on 127.0.0.1 for one connection, reads the request head, writes `response`, and resets the connection once `reset` is
      * released. Returns the port; the listener closes with the scope.
      */
    def resettingServer(response: Array[Byte], reset: Latch)(using Frame): Int < (Async & Scope)

end ResettingServer
