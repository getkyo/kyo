package kyo.internal

/** Internal marker types identifying the database engine at render / execute time.
  *
  * Not user-facing: the backend for a live [[kyo.SqlClient]] is derived from the URL scheme (`postgres://` vs `mysql://`) and never
  * configured by callers. Users pick their engine by choosing [[kyo.PgSqlClient]] vs [[kyo.MySqlSqlClient]]; the render layer reaches for
  * one of these tags internally to select placeholder syntax (`?` vs `$N`) and other per-engine details.
  */
sealed abstract class SqlBackend

object SqlBackend:
    /** Internal tag for PostgreSQL. Companion `object Postgres` is a singleton inhabiting this type. */
    sealed abstract class Postgres extends SqlBackend

    /** Internal tag for MySQL, carrying an optional server version for feature gating.
      *
      * The `serverVersion` triple `(major, minor, patch)` gates SQL features that MySQL introduced in specific releases. The default
      * singleton (`SqlBackend.Mysql`) assumes the latest broadly-supported version `(8, 4, 0)`.
      *
      * ==Feature gates derived from serverVersion==
      *   - [[supportsLateral]]: `LATERAL` subquery support, available from MySQL 8.0.14.
      *   - [[supportsRecursiveCte]]: `WITH RECURSIVE` CTE support, available from MySQL 8.0.0.
      *   - [[supportsIntersectExcept]]: `INTERSECT` / `EXCEPT` set operators, available from MySQL 8.0.31.
      */
    sealed abstract class Mysql extends SqlBackend:
        /** MySQL server version as a `(major, minor, patch)` triple. */
        def serverVersion: (Int, Int, Int)

        /** Returns `true` when the server version is MySQL 8.0.14 or newer, which is when `LATERAL` subquery support was introduced. */
        def supportsLateral: Boolean =
            import scala.math.Ordering.Implicits.infixOrderingOps
            serverVersion >= (8, 0, 14)

        /** Returns `true` when the server version is MySQL 8.0.0 or newer, which is when `WITH RECURSIVE` CTE support was introduced. */
        def supportsRecursiveCte: Boolean =
            import scala.math.Ordering.Implicits.infixOrderingOps
            serverVersion >= (8, 0, 0)

        /** Returns `true` when the server version is MySQL 8.0.31 or newer, which is when `INTERSECT` and `EXCEPT` set operators were
          * introduced.
          */
        def supportsIntersectExcept: Boolean =
            import scala.math.Ordering.Implicits.infixOrderingOps
            serverVersion >= (8, 0, 31)
    end Mysql

    /** Runtime singleton tag for PostgreSQL. */
    case object Postgres extends Postgres

    /** Runtime singleton tag for MySQL, assumes server version `(8, 4, 0)`.
      *
      * This object also acts as a factory: use `SqlBackend.Mysql.versioned(major, minor, patch)` to obtain a backend targeting a specific
      * MySQL server version for feature gating (used by render-layer tests; there is currently no runtime path that hands a
      * user-configured server version to a live [[kyo.MySqlSqlClient]]).
      */
    object Mysql extends SqlBackend.Mysql:
        def serverVersion: (Int, Int, Int) = (8, 4, 0)

        /** Creates a [[SqlBackend.Mysql]] backend targeting the given server version.
          *
          * @param version
          *   server version as `(major, minor, patch)`, e.g. `(8, 0, 14)` or `(5, 7, 44)`.
          */
        def versioned(version: (Int, Int, Int)): SqlBackend.Mysql =
            val v = version
            new SqlBackend.Mysql:
                def serverVersion: (Int, Int, Int) = v
        end versioned
    end Mysql
end SqlBackend
