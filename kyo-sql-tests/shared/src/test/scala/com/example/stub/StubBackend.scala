package com.example.stub

import kyo.*
import kyo.db.Backend
import kyo.db.Idiom
import kyo.db.Runtime

/** An out-of-tree [[kyo.db.Backend]], the entry point the SPI resolves a `stub://` URL to.
  *
  * It claims the `stub` scheme, answers its own [[dialect]], and assembles a client through the public [[kyo.db.Runtime.init]], wrapping the
  * carrier in a [[StubClient]] exactly as the shipping backends do. A zero-argument class so `ServiceLoader` (JVM and Native) and
  * `Backend.register` (JS and Wasm) can both instantiate it.
  */
class StubBackend extends Backend:

    val scheme: String       = "stub"
    val aliases: Set[String] = Set.empty
    val dialect: Idiom       = StubDialect

    def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) =
        Runtime.init(url, config, StubConnection.factory(url.options)).map(rt => new StubClient(rt))

end StubBackend
