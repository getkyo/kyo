package kyo

/** The DoltLite engine could not be loaded here.
  *
  * Its own leaf rather than a generic connect failure, because the cause is the artifact rather than the database: no
  * host was unreachable and no file was missing. DoltLite ships as a compiled library and is not published for every
  * platform this build otherwise supports, so a caller needs to be told the engine is absent HERE rather than that
  * their URL was wrong. `kyo-sql-sqlite` is the embedded engine that does run everywhere.
  *
  * @param reason
  *   what the native loader reported, which names the platform it looked under and every place it looked
  */
final case class DoltLiteEngineUnavailableException(reason: String)(using Frame)
    extends SqlConnectionBackendException(
        s"The DoltLite engine could not be loaded: $reason"
    )
