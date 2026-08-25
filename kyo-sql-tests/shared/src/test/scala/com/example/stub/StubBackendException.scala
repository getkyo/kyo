package com.example.stub

import kyo.*

/** The stub's one extended exception leaf, proving an out-of-tree backend can declare a leaf below a category bridge and mix in a recovery
  * marker.
  *
  * It extends [[kyo.SqlConnectionBackendException]], the open bridge below the sealed connection category, and carries [[kyo.SqlRetryable]], so
  * a caller's `case _: SqlRetryable` arm recovers it exactly as it recovers an in-tree transient fault. Category matching stays exhaustive
  * because the bridge is itself a [[kyo.SqlConnectionException]].
  */
final case class StubUnavailableException(reason: String)(using Frame)
    extends SqlConnectionBackendException(s"stub backend unavailable: $reason") with SqlRetryable
