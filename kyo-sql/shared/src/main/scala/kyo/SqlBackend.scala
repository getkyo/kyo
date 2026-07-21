package kyo

/** Phantom marker types that identify the database backend.
  *
  * Used as a type parameter on [[SqlClient]] so the compiler can select the correct encoder/decoder instances for each backend
  * unambiguously. The companion `object Postgres` / `object Mysql` are also concrete singleton instances of their respective traits, so
  * they can be threaded through runtime code paths (e.g. SQL rendering needs to know whether to emit `?` or `$N` placeholders).
  */
sealed abstract class SqlBackend

object SqlBackend:
    /** Phantom type for PostgreSQL connections. The companion `object Postgres` is a runtime singleton inhabiting this type. */
    sealed abstract class Postgres extends SqlBackend

    /** Phantom type for MySQL connections, carrying an optional server version for feature gating.
      *
      * The `serverVersion` triple `(major, minor, patch)` is used to gate SQL features that MySQL introduced in specific releases. The
      * default singleton (`SqlBackend.Mysql`) assumes the latest broadly-supported version `(8, 4, 0)`.
      *
      * ==Feature gates derived from serverVersion==
      *   - [[supportsLateral]]: `LATERAL` subquery support, available from MySQL 8.0.14.
      *   - [[supportsRecursiveCte]]: `WITH RECURSIVE` CTE support, available from MySQL 8.0.0.
      *   - [[supportsIntersectExcept]]: `INTERSECT` / `EXCEPT` set operators, available from MySQL 8.0.31.
      */
    sealed abstract class Mysql extends SqlBackend:
        /** MySQL server version as a `(major, minor, patch)` triple. */
        def serverVersion: (Int, Int, Int)

        /** Returns `true` when the server version is MySQL 8.0.14 or newer, which is when `LATERAL` subquery support was introduced.
          *
          * @note
          *   MySQL versions older than 8.0.14 will raise [[SqlException.Unsupported]] if a [[Sql.lateral]] join is rendered against this
          *   backend.
          */
        def supportsLateral: Boolean =
            import scala.math.Ordering.Implicits.infixOrderingOps
            serverVersion >= (8, 0, 14)

        /** Returns `true` when the server version is MySQL 8.0.0 or newer, which is when `WITH RECURSIVE` CTE support was introduced.
          *
          * @note
          *   MySQL versions older than 8.0.0 (e.g. MySQL 5.7.x) will raise [[SqlException.Unsupported]] if a recursive CTE is rendered
          *   against this backend.
          */
        def supportsRecursiveCte: Boolean =
            import scala.math.Ordering.Implicits.infixOrderingOps
            serverVersion >= (8, 0, 0)

        /** Returns `true` when the server version is MySQL 8.0.31 or newer, which is when `INTERSECT` and `EXCEPT` set operators were
          * introduced.
          *
          * @note
          *   MySQL versions older than 8.0.31 will raise [[SqlException.Unsupported]] if an `INTERSECT` or `EXCEPT` set operation is
          *   rendered against this backend.
          */
        def supportsIntersectExcept: Boolean =
            import scala.math.Ordering.Implicits.infixOrderingOps
            serverVersion >= (8, 0, 31)
    end Mysql

    /** Runtime singleton tag for PostgreSQL — used in non-phantom positions. */
    case object Postgres extends Postgres

    /** Runtime singleton tag for MySQL — assumes server version `(8, 4, 0)`.
      *
      * This object also acts as a factory: use `SqlBackend.Mysql.versioned(major, minor, patch)` to obtain a backend targeting a specific
      * MySQL server version for feature gating (e.g. disabling `LATERAL` on MySQL 5.7, or disabling `WITH RECURSIVE` on MySQL 5.7).
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
