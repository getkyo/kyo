package kyo.internal

import kyo.*

/** The engines whose conformance runs in this module, one descriptor each.
  *
  * DoltLite is not here: its engine is a SQLite fork exporting the same `sqlite3_*` symbols, so a Native binary cannot link it beside
  * SQLite, and its battery runs in its own module.
  */
object SqlConformanceBackends:

    val dolt: SqlConformanceBackend = new DoltConformanceBackend

    val all: Seq[SqlConformanceBackend] =
        Seq(new PostgresConformanceBackend, new MysqlConformanceBackend, new SqliteConformanceBackend, dolt)

end SqlConformanceBackends
