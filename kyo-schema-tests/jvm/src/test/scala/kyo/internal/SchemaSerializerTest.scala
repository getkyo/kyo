package kyo.internal

import kyo.*
import kyo.schema.rename

// Allocation is observable only through the JVM's per-thread allocation counter, which Scala.js and Scala Native do not have.
class SchemaSerializerTest extends kyo.test.Test[Any]:

    private def allocatedPerRecord[A: Schema](json: String, records: Int): Long =
        val threads = java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
        discard(Json.decode[Chunk[A]](json).getOrThrow)
        val before = threads.getCurrentThreadAllocatedBytes
        discard(Json.decode[Chunk[A]](json).getOrThrow)
        (threads.getCurrentThreadAllocatedBytes - before) / records
    end allocatedPerRecord

    "decoding a record with a renamed field and an absent defaulted field allocates within twice a plain record's bytes" in {
        val records   = 2000
        val plain     = allocatedPerRecord[SSPlain]("[" + Seq.fill(records)("""{"id":"a","s":"b"}""").mkString(",") + "]", records)
        val renamed   = allocatedPerRecord[SSRenamedDefault]("[" + Seq.fill(records)("""{"id":"a","w":"b"}""").mkString(",") + "]", records)
        val defaulted = allocatedPerRecord[SSRenamedDefault]("[" + Seq.fill(records)("""{"id":"a"}""").mkString(",") + "]", records)
        assert(
            renamed <= 2 * plain && defaulted <= 2 * plain,
            s"bytes per record: plain $plain, renamed $renamed, renamed with the default $defaulted"
        )
    }

end SchemaSerializerTest

case class SSPlain(id: String, s: String) derives CanEqual, Schema
case class SSRenamedDefault(id: String, @rename("w") s: Maybe[String] = Absent, @rename("v") t: Maybe[String] = Absent)
    derives CanEqual, Schema
