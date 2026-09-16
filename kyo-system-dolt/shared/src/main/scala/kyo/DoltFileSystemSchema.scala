package kyo

/** The tables a [[DoltFileSystem]] stores a tree in, and the statements that create them.
  *
  * A node row is metadata; content lives in fixed-size blocks keyed by path and index, so a seek is a primary-key
  * lookup and rewriting part of a large file touches only the blocks that changed. Plain SQL with no engine-specific
  * types, because it has to create identically on a server and on an embedded file.
  */
private[kyo] object DoltFileSystemSchema:

    /** Bytes per content block. A constant rather than a setting: changing it changes the layout of existing data, so
      * two clients against one database cannot be allowed to disagree about it.
      */
    val BlockBytes: Int = 64 * 1024

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
          |  modified_ms BIGINT NOT NULL
          |)""".stripMargin,
        // Listing a directory is the one read that is not a primary-key lookup, so the parent column is indexed.
        "CREATE INDEX IF NOT EXISTS vfs_node_parent ON vfs_node (parent)",
        """CREATE TABLE IF NOT EXISTS vfs_block (
          |  path  VARCHAR(1024) NOT NULL,
          |  idx   INT NOT NULL,
          |  bytes BLOB NOT NULL,
          |  PRIMARY KEY (path, idx)
          |)""".stripMargin
    )

end DoltFileSystemSchema
