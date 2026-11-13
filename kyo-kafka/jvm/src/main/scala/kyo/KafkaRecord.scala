package kyo

import scala.jdk.CollectionConverters.*

/** A record to publish, or one received from a subscription.
  *
  * Keys and values are raw bytes; pair this with a serializer/deserializer configuration (e.g. `ByteArraySerializer` /
  * `StringSerializer` via `KafkaConfig.withProperty`) to control the wire format.
  *
  * @param topic
  *   the target topic when publishing, or the source topic when received
  * @param key
  *   the optional record key, used for partitioning
  * @param value
  *   the record payload
  * @param partition
  *   the target partition when publishing; `Absent` lets the producer pick one (by key hash, or round-robin)
  * @param headers
  *   record headers; empty by default
  */
final case class KafkaRecord(
    topic: String,
    value: Array[Byte],
    key: Maybe[Array[Byte]] = Maybe.Absent,
    partition: Maybe[Int] = Maybe.Absent,
    headers: Seq[(String, Array[Byte])] = Seq.empty
) derives CanEqual:

    /** A copy of this record with the given key. */
    def withKey(key: Array[Byte]): KafkaRecord = copy(key = Maybe.Present(key))

    /** A copy of this record with the given partition. */
    def withPartition(partition: Int): KafkaRecord = copy(partition = Maybe.Present(partition))

    /** A copy of this record with one header appended. */
    def withHeader(name: String, value: Array[Byte]): KafkaRecord =
        copy(headers = headers :+ (name -> value))

object KafkaRecord:

    /** A record with only a topic and payload. */
    def apply(topic: String, value: Array[Byte]): KafkaRecord =
        KafkaRecord(topic, value, Maybe.Absent, Maybe.Absent, Seq.empty)

    /** A record with a topic, string payload, and optional string key, UTF-8 encoded. */
    def text(topic: String, value: String, key: Maybe[String] = Maybe.Absent): KafkaRecord =
        KafkaRecord(
            topic,
            value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            key.map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            Maybe.Absent,
            Seq.empty
        )

    /** Adapts a Kafka client's `ConsumerRecord` to a [[KafkaRecord]] plus its offset metadata. */
    private[kyo] def fromConsumerRecord(record: org.apache.kafka.clients.consumer.ConsumerRecord[Array[Byte], Array[Byte]]): KafkaRecord =
        KafkaRecord(
            topic = record.topic(),
            value = record.value(),
            key = Maybe.fromOption(Option(record.key())),
            partition = Maybe.Present(record.partition()),
            headers = record.headers().asScala.toSeq.map(h => h.key() -> h.value())
        )
end KafkaRecord

/** Offset metadata of a received record: the topic, partition, and offset it was read at. */
final case class KafkaOffset(topic: String, partition: Int, offset: Long) derives CanEqual

/** Metadata of a successfully published record, as reported by the broker's acknowledgement. */
final case class KafkaRecordMetadata(topic: String, partition: Int, offset: Long, timestamp: Long) derives CanEqual
