package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.Test
import kyo.internal.mysql.MysqlBufferReader

/** Unit tests for [[OkPacketUnmarshaller]], the MySQL OK-packet wire decoder.
  *
  * This file lives under `kyo/internal/` because it directly exercises the unmarshaller and buffer-reader internals: it constructs a
  * `MysqlBufferReader` from a hand-written fixture byte sequence and asserts the decoded [[OkPacket]] field values. The public `SqlClient`
  * surface never exposes these types, so the test legitimately belongs to the internal layer per the LHS-rule
  * (`kyo-sql/TEST-AUDIT-FIX/STEERING.md`). The public-API path is `SqlClient.execute` returning `InsertResult` (covered in
  * `SqlInsertResultTest`'s container-gated leaves).
  *
  * The fixture (synthetic OK packet) is identical to the one that previously lived in `kyo.SqlInsertResultTest` lines 103-110 prior to the
  * Phase 4 split:
  *   - header byte (0x00 / 0xFE) ALREADY consumed by the dispatcher; the unmarshaller reads from the body
  *   - lenenc(affectedRows = 1) → 0x01
  *   - lenenc(lastInsertId = 42) → 0x2a
  *   - uint16 LE statusFlags = 0 → 0x00 0x00
  *   - uint16 LE warnings = 0 → 0x00 0x00
  *   - (no trailing info bytes)
  */
class OkPacketUnmarshallerTest extends Test:

    "OkPacketUnmarshaller surfaces lastInsertId" in {
        val payload = Array[Byte](0x01, 0x2a, 0x00, 0x00, 0x00, 0x00)
        val reader  = MysqlBufferReader(payload)
        // OkPacketUnmarshaller.read returns a Kyo computation in Abort[SqlException.Decode]; for a
        // well-formed packet it succeeds synchronously.
        import AllowUnsafe.embrace.danger
        val ok = Sync.Unsafe.evalOrThrow(Abort.run(OkPacketUnmarshaller.read(reader)).map(_.getOrThrow))
        assert(ok.affectedRows == 1L)
        assert(ok.lastInsertId == 42L)
    }

end OkPacketUnmarshallerTest
