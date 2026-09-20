package kyo.internal.dolt

import kyo.*
import kyo.db.Connection
import kyo.internal.mysql.MysqlSqlConnection

/** Opens Dolt sessions for the pool by opening MySQL ones and recording which revision each landed on.
  *
  * A Dolt database name may carry a revision after a slash, so `app/feature` in the URL means the handshake already put this session on
  * `feature`, and [[DoltConnection]] has to know that or its first statement would issue a `USE` that changes nothing.
  *
  * @param options
  *   the URL's options, which reach the MySQL layer for the TLS mode it resolves from them
  */
final private[kyo] class DoltConnectionFactory(options: SqlConfig.Url.Options) extends Connection.Factory[DoltConnection]:

    private val mysql = MysqlSqlConnection.factory(options)

    def open(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
        Frame
    ): DoltConnection < (Async & Abort[SqlException]) =
        SqlConfig.Address.requireNetwork(address).flatMap { network =>
            val (database, revision) = DoltDatabase.split(network.database)
            mysql.open(address, password, config).flatMap { conn =>
                // A conflicted merge must be able to COMMIT, leaving its conflicts in the working set for the caller to
                // resolve. Without this the server refuses the commit and rolls the whole merge back.
                conn.simpleExecute("SET @@dolt_allow_commit_conflicts = 1").andThen {
                    // Unsafe: one reference holding where this session is pointed, initialised before the connection is
                    // visible to any caller and only ever read and written through the session that owns it.
                    Sync.Unsafe.defer(new DoltConnection(conn, database, revision, AtomicRef.Unsafe.init(revision)))
                }
            }
        }

end DoltConnectionFactory
