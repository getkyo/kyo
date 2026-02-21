package kyo.grpc

import io.grpc.Metadata
import java.util.Base64
import kyo.*

final case class SafeMetadata(
    entries: Map[String, Seq[String]] = Map.empty
):
    def add(key: String, value: String): SafeMetadata =
        if key.endsWith(Metadata.BINARY_HEADER_SUFFIX) then
            throw new IllegalArgumentException(
                s"Binary header key $key must end with ${Metadata.BINARY_HEADER_SUFFIX} and value must be Chunk[Byte]"
            )
        end if
        update(key, value)
    end add

    def add(key: String, value: Chunk[Byte]): SafeMetadata =
        if !key.endsWith(Metadata.BINARY_HEADER_SUFFIX) then
            throw new IllegalArgumentException(s"Binary header key $key must end with ${Metadata.BINARY_HEADER_SUFFIX}")
        update(key, Base64.getEncoder.encodeToString(value.toArray))
    end add

    private def update(key: String, value: String): SafeMetadata =
        val newValues = entries.getOrElse(key, Seq.empty) :+ value
        copy(entries = entries.updated(key, newValues))

    def merge(that: SafeMetadata): SafeMetadata =
        val merged = that.entries.foldLeft(entries) { case (acc, (k, v)) =>
            acc.updated(k, acc.getOrElse(k, Seq.empty) ++ v)
        }
        copy(entries = merged)
    end merge

    def toJava: Metadata =
        val md = new Metadata()
        entries.foreach { case (k, values) =>
            values.foreach { v =>
                if k.endsWith(Metadata.BINARY_HEADER_SUFFIX) then
                    val decoded = Base64.getDecoder.decode(v)
                    md.put(Metadata.Key.of(k, Metadata.BINARY_BYTE_MARSHALLER), decoded)
                else
                    md.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v)
            }
        }
        md
    end toJava

end SafeMetadata

object SafeMetadata:
    val empty: SafeMetadata = SafeMetadata()

    def fromJava(md: Metadata): SafeMetadata =
        var result = empty
        val keys   = md.keys()
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
                            result = result.update(key, Base64.getEncoder.encodeToString(it.next()))
                    end if
                else
                    val keyObj = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)
                    val values = md.getAll(keyObj)
                    if values != null then
                        val it = values.iterator()
                        while it.hasNext do
                            result = result.update(key, it.next())
                    end if
                end if
            end while
        end if
        result
    end fromJava
end SafeMetadata

extension (metadata: SafeMetadata)

    def getStrings(key: String): Seq[String] =
        metadata.entries.getOrElse(key, Seq.empty)

    def getBinary(key: String): Seq[Chunk[Byte]] =
        metadata.entries.getOrElse(key, Seq.empty).map(s => Chunk.from(Base64.getDecoder.decode(s)))

end extension
