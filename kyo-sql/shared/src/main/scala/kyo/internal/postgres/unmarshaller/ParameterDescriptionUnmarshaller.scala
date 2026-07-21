package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.ParameterDescription
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller

/** Unmarshaller for [[ParameterDescription]].
  *
  * Wire: 't' | Int32(len) | Int16(numParams) | Int32(OID)*
  *
  * The reader covers the message body only (type byte and length already consumed).
  *
  * Reference: PostgreSQL §55.7 "ParameterDescription"
  */
object ParameterDescriptionUnmarshaller extends Unmarshaller[ParameterDescription]:
    def read(buf: PostgresBufferReader)(using Frame): ParameterDescription < Abort[SqlException.Decode] =
        buf.readInt16().flatMap { numParamsShort =>
            readOids(buf, numParamsShort.toInt & 0xffff, Chunk.empty).map { oids =>
                ParameterDescription(oids)
            }
        }
    end read

    private def readOids(buf: PostgresBufferReader, remaining: Int, acc: Chunk[Int])(using
        Frame
    ): Chunk[Int] < Abort[SqlException.Decode] =
        if remaining == 0 then acc
        else buf.readInt32().flatMap { oid => readOids(buf, remaining - 1, acc.appended(oid)) }

end ParameterDescriptionUnmarshaller
