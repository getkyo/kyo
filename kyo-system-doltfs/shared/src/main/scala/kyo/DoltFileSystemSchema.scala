package kyo

/** The table a [[DoltFileSystem]] stores a tree in, and the statements that create it.
  *
  * One row per path, content included, which is the shape SQLite's own archive format uses and the one DoltHub
  * proposes for a filesystem over Dolt. It keeps the tree queryable: a file's content is a column rather than a
  * set of rows to reassemble, and a commit's diff names one row per changed file.
  *
  * Content is NOT split into fixed-size rows. The engine already does that: measured on a 1 MiB value, flipping
  * one byte and committing cost 12 KB, because Dolt content-addresses and shares structure inside a large blob
  * across versions.
  *
  * Positioned access goes through the value's HEX TEXT rather than its bytes, which looks indirect and is not.
  * Measured on Dolt 2.3.4, `SUBSTRING`, `SUBSTR`, `MID`, `INSERT` and a `BINARY` cast all fail on a blob holding
  * bytes that are not valid UTF-8, and `LEFT` and `RIGHT` succeed while silently replacing each such byte with
  * U+FFFD. `HEX` is correct, and its output is ASCII, which those same functions handle. So a slice is taken over
  * the hex and decoded back with `UNHEX`.
  *
  * Plain SQL with no engine-specific types, because it has to create identically on a server and on an embedded
  * file. `LONGBLOB` rather than `BLOB`: MySQL's `BLOB` caps at 65535 bytes.
  */
private[kyo] object DoltFileSystemSchema:

    /** What a node is. Stored as text rather than an enum so the schema carries across engines unchanged. */
    object Kind:
        val File: String      = "file"
        val Directory: String = "dir"
        val Symlink: String   = "symlink"
    end Kind

    /** Every statement needed to bring an empty database up to this schema, in order. */
    val create: Chunk[String] = Chunk(
        """CREATE TABLE IF NOT EXISTS vfs_node (
          |  path        VARCHAR(1024) NOT NULL PRIMARY KEY,
          |  parent      VARCHAR(1024) NOT NULL,
          |  kind        VARCHAR(16) NOT NULL,
          |  target      VARCHAR(1024),
          |  size_bytes  BIGINT NOT NULL,
          |  modified_ms BIGINT NOT NULL,
          |  bytes       LONGBLOB
          |)""".stripMargin,
        // Listing a directory is the one read that is not a primary-key lookup, so the parent column is indexed.
        "CREATE INDEX IF NOT EXISTS vfs_node_parent ON vfs_node (parent)"
    )

end DoltFileSystemSchema
