package kyo.internal.postgres.unmarshaller

import kyo.internal.postgres.CopyDone
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[CopyDone]] (server → client direction).
  *
  * Wire body (after type byte 'c' and Int32(4)): empty (no payload).
  *
  * Reference: PostgreSQL §55.7 "CopyDone"
  */
object CopyDoneUnmarshaller extends Unmarshaller.Const(CopyDone)
