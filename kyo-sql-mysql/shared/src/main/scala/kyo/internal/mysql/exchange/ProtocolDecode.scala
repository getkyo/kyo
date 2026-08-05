package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlConnectionProtocolDecodeException
import kyo.SqlDecodeException
import kyo.SqlException
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.Unmarshaller

/** Decodes one wire value and relabels a failed decode as a [[SqlConnectionProtocolDecodeException]].
  *
  * Every exchange that reads a raw MySQL payload off the wire decodes it with an [[Unmarshaller]] (or a raw [[MysqlBufferReader]] read
  * such as `readLenencInt`), then folds the same three-way [[Result]] outcome, a successful value, a [[SqlDecodeException]] failure, or a
  * panic, into the same [[SqlConnectionProtocolDecodeException]] leaf. Stating that fold once keeps the packet-type label ("OK", "ERR",
  * "ColumnDefinition41", "BinaryResultsetRow", "column count", ...) as the only thing that varies across the six exchange files that read
  * raw payloads.
  */
private[mysql] object ProtocolDecode:

    /** Runs `decoded`, relabeling a [[SqlDecodeException]] failure or panic as a [[SqlConnectionProtocolDecodeException]] tagged `label`. */
    def decode[T](label: String, decoded: T < Abort[SqlDecodeException])(using Frame): T < Abort[SqlException] =
        Abort.run[SqlDecodeException](decoded).flatMap {
            case Result.Success(value) => value
            case Result.Failure(e)     => Abort.fail(SqlConnectionProtocolDecodeException(label, e))
            case Result.Panic(t)       => Abort.fail(SqlConnectionProtocolDecodeException(label, t))
        }
    end decode

    /** Reads `payload` with `unmarshaller`, relabeling a failed decode as a [[SqlConnectionProtocolDecodeException]] tagged `label`. */
    def decode[T](label: String, payload: Span[Byte], unmarshaller: Unmarshaller[T])(using Frame): T < Abort[SqlException] =
        decode(label, unmarshaller.read(MysqlBufferReader(payload)))

end ProtocolDecode
