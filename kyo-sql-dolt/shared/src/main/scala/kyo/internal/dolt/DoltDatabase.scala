package kyo.internal.dolt

import kyo.*

/** Splits a Dolt database name into the database and the revision it may carry.
  *
  * Dolt names a branch as part of the database: `app/feature` is the `feature` branch of `app`, and the server accepts that form in the
  * handshake and in `USE`. A revision is a branch, a tag, or a commit hash, and this does not try to tell them apart, since the server
  * resolves the name against its own namespaces.
  */
private[kyo] object DoltDatabase:

    /** The database, and the revision named after the slash when there is one.
      *
      * The FIRST slash separates them: neither a database name nor a revision can contain one, so a name carrying more is malformed and the
      * server says so with its own message.
      */
    def split(database: String): (String, Maybe[String]) =
        database.indexOf('/') match
            case -1  => (database, Absent)
            case idx => (database.substring(0, idx), Present(database.substring(idx + 1)))

    /** The name that selects `revision` of `database`, which is what `USE` and the handshake both read. */
    def qualify(database: String, revision: String): String = s"$database/$revision"

    /** The `USE` statement that moves a session onto `revision` of `database`.
      *
      * Backtick-quoted because the name contains a slash, which is not a bare identifier character: unquoted, `USE app/feature` is a syntax
      * error. An embedded backtick is doubled, so a name carrying one cannot end the quoting early.
      */
    def useStatement(database: String, revision: String): String =
        useName(qualify(database, revision))

    /** The `USE` statement that moves a session back to the database's own default branch, naming no revision. */
    def useDefaultStatement(database: String): String = useName(database)

    private def useName(name: String): String =
        s"USE `${name.replace("`", "``")}`"

end DoltDatabase
