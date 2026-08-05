package com.example.stub

import kyo.*
import kyo.db.Idiom
import kyo.db.Runtime

/** A [[kyo.SqlClient]] for the stub engine, built from the public assembly SPI alone.
  *
  * It supplies the one abstract member a client subclass owns, [[dialect]], and inherits the entire portable surface and the routing tier from
  * [[kyo.SqlClient]]. The carrier it holds is assembled by [[kyo.db.Runtime.init]] in [[StubBackend.open]]; nothing here reaches a socket.
  */
final class StubClient(runtime: Runtime[StubConnection]) extends SqlClient(runtime):
    def dialect: Idiom = StubDialect
end StubClient
