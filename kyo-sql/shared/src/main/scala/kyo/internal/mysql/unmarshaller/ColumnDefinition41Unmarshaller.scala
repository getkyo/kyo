package kyo.internal.mysql.unmarshaller

import kyo.*
import kyo.SqlDecodeException
import kyo.internal.mysql.ColumnDefinition41
import kyo.internal.mysql.MysqlBufferReader
import kyo.internal.mysql.Unmarshaller

/** Unmarshaller for [[ColumnDefinition41]].
  *
  * Wire (protocol 4.1):
  *   lenenc-string(catalog) | lenenc-string(schema) | lenenc-string(table) | lenenc-string(orgTable)
  *   | lenenc-string(name) | lenenc-string(orgName) | 0x0C (fixed-length field length)
  *   | LE uint16(charset) | LE uint32(columnLength) | uint8(columnType) | LE uint16(flags)
  *   | uint8(decimals) | 0x00 0x00 (filler)
  *
  * The 0x0C byte indicates the fixed-length field that follows is always 12 bytes.
  *
  * Reference: MySQL Internals, Protocol::ColumnDefinition41
  */
object ColumnDefinition41Unmarshaller extends Unmarshaller[ColumnDefinition41]:

    def read(buf: MysqlBufferReader)(using Frame): ColumnDefinition41 < Abort[SqlDecodeException] =
        buf.readLenencString().flatMap { catalog =>
            buf.readLenencString().flatMap { schema =>
                buf.readLenencString().flatMap { table =>
                    buf.readLenencString().flatMap { orgTable =>
                        buf.readLenencString().flatMap { name =>
                            buf.readLenencString().flatMap { orgName =>
                                // Fixed-length field marker (should be 0x0C = 12); value is discarded
                                buf.readLenencInt().flatMap { _ =>
                                    buf.readUInt16LE().flatMap { charset =>
                                        buf.readUInt32LE().flatMap { columnLength =>
                                            buf.readUInt8().flatMap { columnType =>
                                                buf.readUInt16LE().flatMap { flags =>
                                                    buf.readUInt8().flatMap { decimals =>
                                                        // filler: 2 bytes (discard)
                                                        buf.readBytes(2).map { _ =>
                                                            ColumnDefinition41(
                                                                catalog,
                                                                schema,
                                                                table,
                                                                orgTable,
                                                                name,
                                                                orgName,
                                                                charset,
                                                                columnLength,
                                                                columnType,
                                                                flags,
                                                                decimals
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end read

end ColumnDefinition41Unmarshaller
