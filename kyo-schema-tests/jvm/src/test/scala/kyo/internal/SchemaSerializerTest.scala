package kyo.internal

import kyo.*
import kyo.schema.rename

// Allocation is observable only through the JVM's per-thread allocation counter, which Scala.js and Scala Native do not have.
class SchemaSerializerTest extends kyo.test.Test[Any]:

    private val records = 2000

    // Measured once the decode is compiled: before that, allocation varies by about 150 bytes per record with the JIT tier.
    private def allocatedPerRecord(decode: () => Any): Long =
        val threads = java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
        (1 to 40).foreach(_ => discard(decode()))
        val before = threads.getCurrentThreadAllocatedBytes
        (1 to 10).foreach(_ => discard(decode()))
        (threads.getCurrentThreadAllocatedBytes - before) / records / 10
    end allocatedPerRecord

    private def jsonArray(record: String): String = "[" + Seq.fill(records)(record).mkString(",") + "]"

    "decoding a record with a renamed field and an absent defaulted field allocates within twice a plain record's bytes" - {
        "Json" in {
            val plainJson     = jsonArray("""{"id":"a","s":"b"}""")
            val renamedJson   = jsonArray("""{"id":"a","w":"b"}""")
            val defaultedJson = jsonArray("""{"id":"a"}""")
            val plain         = allocatedPerRecord(() => Json.decode[Chunk[SSPlain]](plainJson).getOrThrow)
            val renamed       = allocatedPerRecord(() => Json.decode[Chunk[SSRenamedDefault]](renamedJson).getOrThrow)
            val defaulted     = allocatedPerRecord(() => Json.decode[Chunk[SSRenamedDefault]](defaultedJson).getOrThrow)
            assert(
                renamed <= 2 * plain && defaulted <= 2 * plain,
                s"bytes per record: plain $plain, renamed $renamed, renamed with the default $defaulted"
            )
        }
        "Protobuf" in {
            val plainBytes     = Protobuf.encode(SSPlainBatch(Chunk.fill(records)(SSPlain("a", "b"))))
            val renamedBytes   = Protobuf.encode(SSRenamedBatch(Chunk.fill(records)(SSRenamedDefault("a", Present("b")))))
            val defaultedBytes = Protobuf.encode(SSRenamedBatch(Chunk.fill(records)(SSRenamedDefault("a"))))
            val plain          = allocatedPerRecord(() => Protobuf.decode[SSPlainBatch](plainBytes).getOrThrow)
            val renamed        = allocatedPerRecord(() => Protobuf.decode[SSRenamedBatch](renamedBytes).getOrThrow)
            val defaulted      = allocatedPerRecord(() => Protobuf.decode[SSRenamedBatch](defaultedBytes).getOrThrow)
            assert(Protobuf.decode[SSRenamedBatch](renamedBytes).getOrThrow.records.head == SSRenamedDefault("a", Present("b")))
            assert(
                renamed <= 2 * plain && defaulted <= 2 * plain,
                s"bytes per record: plain $plain, renamed $renamed, renamed with the default $defaulted"
            )
        }
    }

end SchemaSerializerTest

case class SSPlain(id: String, s: String) derives CanEqual, Schema
case class SSRenamedDefault(id: String, @rename("w") s: Maybe[String] = Absent, @rename("v") t: Maybe[String] = Absent)
    derives CanEqual, Schema
case class SSPlainBatch(records: Chunk[SSPlain]) derives CanEqual, Schema
case class SSRenamedBatch(records: Chunk[SSRenamedDefault]) derives CanEqual, Schema
