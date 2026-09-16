package kyo

/** One table with uncommitted changes, as `dolt_status` reports it.
  *
  * A row per table and staging flag rather than per table: a table appears twice when part of its changes were staged and more were made
  * after. `state` is the server's own word (`modified`, `new table`) carried through as text rather than mapped to an enum, because Dolt
  * adds states and an unmatched enum case would be a decode failure.
  */
final case class DoltChange(table: String, staged: Boolean, state: String) derives CanEqual
