package kyo.internal

import kyo.*

/** The engines whose conformance runs in this module, one descriptor each.
  *
  * DoltLite is not here: its engine is a SQLite fork exporting the same `sqlite3_*` symbols, so a Native binary cannot link it beside
  * SQLite, and its battery runs in its own module.
  */
object SqlConformanceBackends:

    val all: Seq[SqlConformanceBackend] =
        Seq(new PostgresConformanceBackend, new MysqlConformanceBackend, new SqliteConformanceBackend, new DoltConformanceBackend)

    /** The descriptor answering to `id`, failing loudly when none does: a suite reaching for an engine that is not listed would assert
      * nothing.
      */
    def byId(id: String): SqlConformanceBackend =
        all.find(_.id == id).getOrElse(throw new IllegalArgumentException(s"no conformance descriptor has the id '$id'"))

end SqlConformanceBackends
