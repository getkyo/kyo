package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.postgres.CopyOutResponse
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[CopyOutResponse]] (server → client).
  *
  * Wire body (after type byte 'H' and Int32 length): Int8(overallFormat) | Int16(numCols) | Int16(colFormat)*
  *
  * Reference: PostgreSQL §55.7 "CopyOutResponse"
  */
object CopyOutResponseUnmarshaller extends Unmarshaller[CopyOutResponse]:
    def read(buf: PostgresBufferReader)(using Frame): CopyOutResponse < Abort[SqlDecodeException] =
        buf.readByte().flatMap { overallFormat =>
            buf.readInt16().flatMap { numCols =>
                readFormats(buf, numCols.toInt & 0xffff, Chunk.empty).map { columnFormats =>
                    CopyOutResponse(overallFormat, columnFormats)
                }
            }
        }
    end read

    private def readFormats(buf: PostgresBufferReader, remaining: Int, acc: Chunk[Short])(using
        Frame
    ): Chunk[Short] < Abort[SqlDecodeException] =
        if remaining == 0 then acc
        else buf.readInt16().flatMap { fmt => readFormats(buf, remaining - 1, acc.appended(fmt)) }

end CopyOutResponseUnmarshaller
