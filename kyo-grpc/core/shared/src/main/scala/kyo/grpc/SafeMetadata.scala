package kyo.grpc

import io.grpc.Metadata
import kyo.*
import java.nio.charset.StandardCharsets

final case class SafeMetadata(
    entries: Map[String, List[Any]] = Map.empty
):
    def add(key: String, value: String): SafeMetadata =
        if key.endsWith(Metadata.BINARY_HEADER_SUFFIX) then
            throw new IllegalArgumentException(s"Binary header key $key must end with ${Metadata.BINARY_HEADER_SUFFIX} and value must be Chunk[Byte]")
        update(key, value)

    def add(key: String, value: Chunk[Byte]): SafeMetadata =
        if !key.endsWith(Metadata.BINARY_HEADER_SUFFIX) then
             throw new IllegalArgumentException(s"Binary header key $key must end with ${Metadata.BINARY_HEADER_SUFFIX}")
        update(key, value)

    private def update(key: String, value: Any): SafeMetadata =
        val newValues = entries.getOrElse(key, Nil) :+ value
        copy(entries = entries.updated(key, newValues))

    def merge(that: SafeMetadata): SafeMetadata =
        val merged = that.entries.foldLeft(entries) { case (acc, (k, v)) =>
            acc.updated(k, acc.getOrElse(k, Nil) ++ v)
        }
        copy(entries = merged)


    def toJava: Metadata =
        val md = new Metadata()
        entries.foreach { case (k, values) =>
            values.foreach {
                case v: String =>
                    md.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v)
                case v: Chunk[Byte] =>
                    md.put(Metadata.Key.of(k, Metadata.BINARY_BYTE_MARSHALLER), v.toArray)
                case _ => // Should not happen
            }
        }
        md

end SafeMetadata

object SafeMetadata:
    val empty: SafeMetadata = SafeMetadata()

    def fromJava(md: Metadata): SafeMetadata =
        var result = empty
        val keys = md.keys()
        if keys != null then
            val iterator = keys.iterator()
            while iterator.hasNext do
                val key = iterator.next()
                if key.endsWith(Metadata.BINARY_HEADER_SUFFIX) then
                    val keyObj = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER)
                    val values = md.getAll(keyObj)
                    if values != null then
                        val it = values.iterator()
                        while it.hasNext do
                            result = result.update(key, Chunk.from(it.next()))
                else
                    val keyObj = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
                    val values = md.getAll(keyObj)
                    if values != null then
                        val it = values.iterator()
                        while it.hasNext do
                            result = result.update(key, it.next())
        result
    end fromJava
end SafeMetadata
