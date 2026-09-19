package kyo.internal.doltlite

import kyo.db.Idiom
import kyo.internal.sqlite.SqliteDialect

/** The SQL flavor DoltLite renders: SQLite's, since the fork keeps its SQL surface, under a separate identity.
  *
  * The id must NOT be `sqlite`: it is what a statically rendered query and an extension type dispatch on, so sharing it
  * would make a `sqlite` extension payload silently acceptable here and give the static renderer one entry reaching two
  * engines.
  *
  * A class with a companion object, so the static-render macro can reach it through a public zero-argument constructor.
  */
class DoltLiteDialect extends SqliteDialect:

    override val id: Idiom.Id = Idiom.Id("doltlite")

end DoltLiteDialect

/** The shared [[DoltLiteDialect]] instance. */
object DoltLiteDialect extends DoltLiteDialect
