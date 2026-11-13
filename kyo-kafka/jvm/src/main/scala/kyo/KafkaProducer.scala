package kyo

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeaders

/** A `Scope`-managed Kafka producer.
  *
  * Obtain one with [[KafkaProducer.init]], which binds the underlying client's lifetime to the enclosing `Scope`: the
  * client is closed exactly once on scope exit, whether that exit is normal, an error, or a cancellation.
  *
  * {{{
  *   Scope.run {
  *     for
  *       producer <- KafkaProducer.init(KafkaConfig("localhost:9092"))
  *       metadata <- producer.send(KafkaRecord.text("events", "hello"))
  *     yield metadata
  *   }
  * }}}
  *
  * @see [[KafkaProducer.init]] to create a producer
  */
opaque type KafkaProducer = KafkaProducer.Unsafe

object KafkaProducer:

    extension (self: KafkaProducer)
        /** Exposes the low-level [[KafkaProducer.Unsafe]] view of this producer. */
        def unsafe: Unsafe = self

    /** Creates a producer from `config` and binds its lifetime to the current `Scope`.
      *
      * @param config
      *   connection and client settings; `key.serializer` / `value.serializer` default to the byte-array serializers
      *   unless overridden
      * @return
      *   a `Scope`-managed [[KafkaProducer]], aborting [[KafkaOperationException]] if the client cannot be created
      */
    def init(config: KafkaConfig)(using Frame): KafkaProducer < (Scope & Sync & Abort[KafkaException]) =
        Scope.acquireRelease(acquireUnscoped(config))(p => Sync.Unsafe.defer(p.unsafe.close()))

    /** Creates a producer whose lifetime the caller owns. The caller MUST close it exactly once via
      * `producer.unsafe.close()`. Prefer [[init]] whenever a `Scope` can own the lifetime.
      */
    def acquireUnscoped(config: KafkaConfig)(using Frame): KafkaProducer < (Sync & Abort[KafkaException]) =
        Sync.defer {
            try Unsafe(new org.apache.kafka.clients.producer.KafkaProducer[Array[Byte], Array[Byte]](withDefaults(config).toProperties))
            catch
                case ex: Throwable => Abort.fail(KafkaOperationException("failed to create Kafka producer", ex))
        }

    /** Sends `record`, completing with the broker-acknowledged metadata.
      *
      * The send is asynchronous: the effect suspends until the broker acknowledges (per `acks`) or the callback reports
      * an error, so it never blocks a carrier thread on the network round trip.
      *
      * @return
      *   the [[KafkaRecordMetadata]] of the published record, aborting [[KafkaOperationException]] on send failure
      */
    extension (self: KafkaProducer)
        def send(record: KafkaRecord)(using Frame): KafkaRecordMetadata < (Async & Sync & Abort[KafkaException]) =
            Fiber.Promise.initWith[KafkaRecordMetadata, Abort[KafkaException]] { promise =>
                Sync.defer {
                    val headers = new RecordHeaders()
                    record.headers.foreach { case (name, value) => discard(headers.add(name, value)) }
                    val jRecord = new ProducerRecord[Array[Byte], Array[Byte]](
                        record.topic,
                        record.partition.toOption.map(java.lang.Integer.valueOf).orNull,
                        record.key.toOption.orNull,
                        record.value,
                        headers
                    )
                    val callback = new org.apache.kafka.clients.producer.Callback:
                        def onCompletion(metadata: org.apache.kafka.clients.producer.RecordMetadata, exception: Exception): Unit =
                            import AllowUnsafe.embrace.danger
                            if exception != null then
                                discard(promise.completeDiscard(Result.fail[KafkaException, KafkaRecordMetadata](
                                    KafkaOperationException(s"failed to send record to topic '${record.topic}'", exception))))
                            else
                                discard(promise.completeDiscard(Result.succeed[KafkaException, KafkaRecordMetadata](
                                    KafkaRecordMetadata(metadata.topic(), metadata.partition(), metadata.offset(), metadata.timestamp()))))
                    try
                        discard(self.unsafe.client.send(jRecord, callback))
                    catch
                        case ex: Throwable => Abort.fail(KafkaOperationException(s"failed to send record to topic '${record.topic}'", ex))
                    promise.get
                }
            }

    /** Flushes all buffered records, blocking until every pending send completes. */
    extension (self: KafkaProducer)
        def flush(using Frame): Unit < (Sync & Abort[KafkaException]) =
            Sync.defer {
                try discard(self.unsafe.client.flush())
                catch
                    case ex: Throwable => Abort.fail(KafkaOperationException("failed to flush Kafka producer", ex))
            }

    private def withDefaults(config: KafkaConfig): KafkaConfig =
        val props = config.properties
        config.withProperties(Dict(
            "key.serializer"   -> props.getOrElse("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer"),
            "value.serializer" -> props.getOrElse("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer")
        ))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for
      * more details.
      */
    sealed abstract class Unsafe extends Serializable:
        private[kyo] def client: org.apache.kafka.clients.producer.KafkaProducer[Array[Byte], Array[Byte]]

        /** Closes the underlying client, waiting up to `timeout` for pending sends to complete. */
        def close(timeout: Duration = 30.seconds)(using AllowUnsafe): Unit =
            client.close(java.time.Duration.ofMillis(timeout.toMillis))

        def safe: KafkaProducer = this
    end Unsafe

    private object Unsafe:
        def apply(client: org.apache.kafka.clients.producer.KafkaProducer[Array[Byte], Array[Byte]]): Unsafe =
            new Unsafe:
                private[kyo] def client = client
    end Unsafe
end KafkaProducer
