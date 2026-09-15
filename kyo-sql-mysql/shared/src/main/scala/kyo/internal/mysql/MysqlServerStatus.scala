package kyo.internal.mysql

/** The server status flags every OK and EOF packet carries.
  *
  * Two decide protocol correctness rather than reporting a detail: [[MoreResultsExists]] says the exchange is not finished, and
  * [[SessionStateChanged]] says the packet carries session variables the server just changed underneath the driver.
  */
private[mysql] object MysqlServerStatus:

    /** A transaction is open on this session. */
    val InTransaction: Int = 0x0001

    /** Autocommit is on. */
    val Autocommit: Int = 0x0002

    /** The statement produced more result sets, so the packet just read is NOT the end of the exchange.
      *
      * A client that stops reading here leaves the rest on the wire, and the connection is then permanently one response ahead of every
      * later borrower.
      */
    val MoreResultsExists: Int = 0x0008

    /** The packet carries a session-state-change block naming variables the server changed. */
    val SessionStateChanged: Int = 0x4000

    def has(statusFlags: Int, flag: Int): Boolean = (statusFlags & flag) != 0

end MysqlServerStatus
