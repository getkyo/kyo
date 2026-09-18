package kyo

/** A DoltLite version-control function returned no row where its contract says it returns one. Distinct from a decode
  * failure, which means a column was there and held something unexpected.
  *
  * @param function
  *   the function or system table that answered nothing
  */
final case class DoltLiteFunctionAnsweredNothingException(function: String)(using Frame)
    extends SqlRequestBackendException(
        s"$function returned no rows, and it is documented to return one. This driver is measured against DoltLite " +
            s"0.50.10, so an engine that answers differently is either older or newer than what it expects."
    )
