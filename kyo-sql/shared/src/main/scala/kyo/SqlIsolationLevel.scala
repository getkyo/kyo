package kyo
// TODO move to a companion
/** Standard SQL transaction isolation levels.
  *
  * Controls the visibility of uncommitted data written by concurrent transactions. Higher isolation reduces anomalies at the cost of
  * concurrency.
  *
  * PostgreSQL default: ReadCommitted. MySQL InnoDB default: RepeatableRead.
  */
enum SqlIsolationLevel derives CanEqual:
    /** Allows reading uncommitted data from other transactions (dirty reads). Lowest isolation; rarely used in practice. */
    case ReadUncommitted

    /** Only committed data is visible. Prevents dirty reads; phantom reads and non-repeatable reads may occur. */
    case ReadCommitted

    /** Snapshot of the database at transaction start. Prevents dirty and non-repeatable reads; phantom reads may occur in some engines. */
    case RepeatableRead

    /** Strictest level: transactions execute as if they were serial. Prevents all read anomalies; lowest concurrency. */
    case Serializable
end SqlIsolationLevel
