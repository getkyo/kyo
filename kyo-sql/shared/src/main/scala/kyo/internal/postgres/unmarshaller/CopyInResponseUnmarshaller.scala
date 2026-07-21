package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.CopyInResponse
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[CopyInResponse]] (server → client).
  *
  * Wire body (after type byte 'G' and Int32 length): Int8(overallFormat) | Int16(numCols) | Int16(colFormat)*
  *
  * Reference: PostgreSQL §55.7 "CopyInResponse"
  */
object CopyInResponseUnmarshaller extends Unmarshaller[CopyInResponse]:
    def read(buf: PostgresBufferReader)(using Frame): CopyInResponse < Abort[SqlException.Decode] =
        buf.readByte().flatMap { overallFormat =>
            buf.readInt16().flatMap { numCols =>
                readFormats(buf, numCols.toInt & 0xffff, Chunk.empty).map { columnFormats =>
                    CopyInResponse(overallFormat, columnFormats)
                }
            }
        }
    end read

    private def readFormats(buf: PostgresBufferReader, remaining: Int, acc: Chunk[Short])(using
        Frame
    ): Chunk[Short] < Abort[SqlException.Decode] =
        if remaining == 0 then acc
        else buf.readInt16().flatMap { fmt => readFormats(buf, remaining - 1, acc.appended(fmt)) }

end CopyInResponseUnmarshaller
