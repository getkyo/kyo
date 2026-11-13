package kyo

import java.nio.charset.StandardCharsets

class KafkaRecordTest extends Test:

    "text encodes topic, value, and key as UTF-8" in {
        val record = KafkaRecord.text("events", "hello", Maybe.Present("key-1"))
        assert(record.topic == "events")
        assert(new String(record.value, StandardCharsets.UTF_8) == "hello")
        assert(record.key.map(k => new String(k, StandardCharsets.UTF_8)) == Maybe.Present("key-1"))
        assert(record.partition == Maybe.Absent)
        assert(record.headers.isEmpty)
    }

    "text without a key leaves it absent" in {
        val record = KafkaRecord.text("events", "hello")
        assert(record.key == Maybe.Absent)
    }

    "withKey sets the key" in {
        val record = KafkaRecord("events", Array(1, 2)).withKey(Array(3))
        assert(record.key == Maybe.Present(Array(3)))
    }

    "withPartition sets the partition" in {
        val record = KafkaRecord("events", Array(1)).withPartition(2)
        assert(record.partition == Maybe.Present(2))
    }

    "withHeader appends headers" in {
        val record = KafkaRecord("events", Array(1))
            .withHeader("h1", Array(1))
            .withHeader("h2", Array(2))
        assert(record.headers.map(_._1) == Seq("h1", "h2"))
    }
end KafkaRecordTest
