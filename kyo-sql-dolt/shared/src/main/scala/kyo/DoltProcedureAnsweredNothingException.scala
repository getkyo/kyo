package kyo

/** A Dolt procedure returned no row where its contract says it returns exactly one.
  *
  * @param procedure
  *   the procedure or system table that answered nothing
  */
final case class DoltProcedureAnsweredNothingException(procedure: String)(using Frame)
    extends SqlRequestBackendException(
        s"$procedure returned no rows, and it is documented to return exactly one. This driver is measured against Dolt 2.3.4, so a " +
            s"server that answers differently is either older or newer than what it expects."
    )
