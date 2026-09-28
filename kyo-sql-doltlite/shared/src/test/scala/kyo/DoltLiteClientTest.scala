package kyo

import kyo.internal.doltlite.DoltLiteEngineProbe

/** What is this engine's own rather than version control's: how a `doltlite://` URL opens, what the embedded statement API refuses, and
  * how a platform without the engine answers. The version-control surface is [[DoltConformanceTest]]'s.
  */
class DoltLiteClientTest extends Test:

    private def withClient[A](f: SqlClient => A < (Async & Abort[SqlException] & Scope & DB))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        // The engine is published for some platforms and not others, and CI runs this leg on one it is
        // absent from. Cancelling names that, where letting the load fail would report a missing native as
        // a broken driver. The unavailability contract itself is asserted by its own leaf below.
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        Scope.run {
            SqlClient.init("doltlite://:memory:", SqlConfig(maxConnections = 1)).map { client =>
                DB.run(client)(f(client))
            }
        }
    end withClient

    "a string holding a second statement is refused" in {
        withClient { client =>
            Abort.run[SqlException](client.query("SELECT 1; SELECT 2")).map { result =>
                assert(result.isFailure, s"a two-statement string was accepted: $result")
            }
        }
    }

    "a doltlite URL opens and narrows to the shared client type" in {
        withClient { client =>
            Dolt.use { dolt =>
                dolt.query("SELECT 1").map { rows =>
                    assert(rows.size == 1, s"expected one row, got ${rows.size}")
                    assert(dolt.dialect.id == kyo.db.Idiom.Id("doltlite"), s"got ${dolt.dialect.id}")
                }
            }
        }
    }

    /** The other side, so no platform is merely skipped: where the engine is absent, opening one fails as the
      * typed unavailability rather than as a panic out of the native loader, and the message names where it looked.
      */
    "a platform without the engine refuses to open one, typed" in {
        assume(!DoltLiteEngineProbe.available, "the DoltLite engine IS published for this platform")
        Abort.run[SqlException](Scope.run(SqlClient.init("doltlite://:memory:", SqlConfig(maxConnections = 1)))).map {
            result =>
                assert(
                    result.failure.exists(_.isInstanceOf[DoltLiteEngineUnavailableException]),
                    s"expected a typed unavailability, got $result"
                )
                // The type alone leaves a caller reading a bare property name where the runtime says nothing more,
                // so the sentence that holds on every runtime is asserted rather than assumed.
                val message = result.failure.fold("")(_.getMessage)
                assert(
                    message.contains("not published for this platform"),
                    s"the failure does not say why: $message"
                )
        }
    }

end DoltLiteClientTest
