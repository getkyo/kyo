package kyo

import kyo.internal.SqlTestBackend.ColumnType

/** What an IDENTIFIER means at the length where the engines part company, run against every registered backend.
  *
  * Identifier length is a limit a caller never writes down and a generator crosses without trying: a name assembled from a table, a column,
  * and a suffix reaches sixty-odd characters on its own. The engines differ there, and in the dangerous direction. One rejects an over-long
  * name; the other keeps a 63-byte prefix of it, so two names the program treats as distinct become one table the server treats as the same,
  * announced only in a notice nobody reads.
  *
  * That difference is NOT the driver's to fix, and this suite does not pretend to. kyo-sql generates no DDL, so the name a table is created
  * under is the caller's own statement text, and truncating it is the server's answer to the caller. It belongs with collation and column
  * precision under what the driver cannot fix, and `kyo-sql/CONTRIBUTING.md` records it there for callers.
  *
  * What IS worth guarding is the portable ceiling itself, because it is the number a caller needs and neither engine states: 63 bytes is the
  * longest identifier both engines carry whole. The leaf below pins that two names at exactly that length stay two tables on every backend.
  */
class SqlIdentifierConformanceTest extends SqlBackendTest:

    /** The longest identifier both engines carry whole: one engine's ceiling is 64 bytes and the other's is 63, so the lower of the two is the
      * limit a caller can hold to on either deployment.
      */
    private val portableLength: Int = 63

    private val first: String  = "t" * (portableLength - 1) + "a"
    private val second: String = "t" * (portableLength - 1) + "b"

    /** Two identifiers at the portable length ceiling, differing in their last byte, name two distinct tables on every backend.
      *
      * The names share every byte but the last, which makes this the boundary case rather than a formality: they are as close to colliding as
      * two distinct names of this length can be, so an engine truncating even one byte early fails here.
      *
      * Distinctness is checked by writing to one name and reading through the other, because comparing the two CREATEs cannot see it. An
      * engine that aliased the second onto the first without refusing it would report two successful creates and hold one table, which is
      * exactly what the over-long case does on one engine and the reason the portable ceiling is worth a leaf at all.
      */
    "two identifiers at the portable length ceiling name two distinct tables" - {
        forEachBackend() { (backend, client, _) =>
            val column = backend.columnType(ColumnType.Int)
            for
                _    <- client.executeRaw(s"CREATE TABLE ${backend.quoteIdent(first)} (v $column)")
                _    <- client.executeRaw(s"CREATE TABLE ${backend.quoteIdent(second)} (v $column)")
                _    <- client.executeRaw(s"INSERT INTO ${backend.quoteIdent(first)} VALUES (1)")
                here <- client.query(s"SELECT v FROM ${backend.quoteIdent(first)}")
                over <- client.query(s"SELECT v FROM ${backend.quoteIdent(second)}")
            yield
                assert(here.size == 1, s"${backend.label}: the row must be in the table it was written to, got ${here.size}")
                assert(
                    over.isEmpty,
                    s"${backend.label}: two names differing inside $portableLength bytes must be two tables, and the row appeared in both"
                )
            end for
        }
    }

end SqlIdentifierConformanceTest
