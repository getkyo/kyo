package kyo

import java.time.Duration as JDuration
import org.apache.kafka.clients.consumer.ConsumerRecords
import scala.jdk.CollectionConverters.*

/** A `Scope`-managed Kafka consumer subscribed to a set of topics.
  *
  * Obtain one with [[KafkaConsumer.init]], which subscribes the underlying client and binds its lifetime to the
  * enclosing `Scope`: the client is closed exactly once on scope exit, whether that exit is normal, an error, or a
  * cancellation.
  *
  * Records are read with [[KafkaConsumer.poll]], which returns one batch, or [[KafkaConsumer.stream]], which polls
  * continuously and emits records as a Kyo `Stream`. Offsets are committed explicitly with
  * [[KafkaConsumer.commitSync]] / [[KafkaConsumer.commitAsync]], or left to the client's `enable.auto.commit` setting.
  *
  * {{{
  *   Scope.run {
  *     for
  *       consumer <- KafkaConsumer.init(KafkaConfig("localhost:9092"), Chunk("events"))
  *       records  <- consumer.stream.take(10).run
  *     yield records
  *   }
  * }}}
  *
  * @see [[KafkaConsumer.init]] to create a consumer
  */
opaque type KafkaConsumer = KafkaConsumer.Unsafe

object KafkaConsumer:

    extension (self: KafkaConsumer)
        /** Exposes the low-level [[KafkaConsumer.Unsafe]] view of this consumer. */
        def unsafe: Unsafe = self

    /** Creates a consumer from `config`, subscribes it to `topics`, and binds its lifetime to the current `Scope`.
      *
      * @param config
      *   connection and client settings; `key.deserializer` / `value.deserializer` default to the byte-array
      *   deserializers unless overridden, and `enable.auto.commit` defaults to `false` so offsets are committed
      *   explicitly
      * @param topics
      *   the topics to subscribe to
      * @return
      *   a `Scope`-managed [[KafkaConsumer]], aborting [[KafkaOperationException]] if the client cannot be created or
      *   subscribed
      */
    def init(config: KafkaConfig, topics: Seq[String])(using Frame): KafkaConsumer < (Scope & Sync & Abort[KafkaException]) =
        Scope.acquireRelease(acquireUnscoped(config, topics))(c => Sync.Unsafe.defer(c.unsafe.close()))

    /** Creates a subscribed consumer whose lifetime the caller owns. The caller MUST close it exactly once via
      * `consumer.unsafe.close()`. Prefer [[init]] whenever a `Scope` can own the lifetime.
      */
    def acquireUnscoped(config: KafkaConfig, topics: Seq[String])(using Frame): KafkaConsumer < (Sync & Abort[KafkaException]) =
        Sync.defer {
            try
                val client = new org.apache.kafka.clients.consumer.KafkaConsumer[Array[Byte], Array[Byte]](withDefaults(config).toProperties)
                try client.subscribe(topics.asJava)
                catch
                    case ex: Throwable =>
                        try client.close()
                        catch case _: Throwable => ()
                        Abort.fail(KafkaOperationException(s"failed to subscribe to topics ${topics.mkString(", ")}", ex))
                Unsafe(client)
            catch
                case ex: Throwable => Abort.fail(KafkaOperationException("failed to create Kafka consumer", ex))
        }

    /** Polls the broker for records, waiting up to `timeout` for at least one.
      *
      * @return
      *   the batch of received records, empty if the timeout elapsed with no records
      */
    extension (self: KafkaConsumer)
        def poll(timeout: Duration)(using Frame): Chunk[KafkaRecord] < (Sync & Abort[KafkaException]) =
            Sync.defer {
                try
                    val records: ConsumerRecords[Array[Byte], Array[Byte]] = self.unsafe.client.poll(JDuration.ofMillis(timeout.toMillis))
                    Chunk.from(records.asScala.map(KafkaRecord.fromConsumerRecord))
                catch
                    case ex: Throwable => Abort.fail(KafkaOperationException("failed to poll Kafka consumer", ex))
            }

    /** A stream of received records, polling continuously with `pollTimeout` per batch.
      *
      * The stream ends only when the consumer is closed or the polling fiber is interrupted; combine with `take` /
      * `takeWhile` to bound it.
      */
    extension (self: KafkaConsumer)
        def stream(pollTimeout: Duration = 500.millis)(using
            Frame
        ): Stream[KafkaRecord, Sync & Abort[KafkaException]] =
            Stream[KafkaRecord, Sync & Abort[KafkaException]]:
                Loop.foreach:
                    self.poll(pollTimeout).map(records => Emit.valueWith(records)(Loop.continue))

    /** Synchronously commits the offsets of all records returned by the last [[poll]] / [[stream]] batch. */
    extension (self: KafkaConsumer)
        def commitSync(using Frame): Unit < (Sync & Abort[KafkaException]) =
            Sync.defer {
                try discard(self.unsafe.client.commitSync())
                catch
                    case ex: Throwable => Abort.fail(KafkaOperationException("failed to commit offsets", ex))
            }

    /** Asynchronously commits the offsets of all records returned by the last [[poll]] / [[stream]] batch. */
    extension (self: KafkaConsumer)
        def commitAsync(using Frame): Unit < (Sync & Abort[KafkaException]) =
            Sync.defer {
                try discard(self.unsafe.client.commitAsync())
                catch
                    case ex: Throwable => Abort.fail(KafkaOperationException("failed to commit offsets", ex))
            }

    private def withDefaults(config: KafkaConfig): KafkaConfig =
        val props = config.properties
        config.withProperties(Dict(
            "key.deserializer"      -> props.getOrElse("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
            "value.deserializer"    -> props.getOrElse("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer"),
            "enable.auto.commit"    -> props.getOrElse("enable.auto.commit", "false"),
            "auto.offset.reset"     -> props.getOrElse("auto.offset.reset", "earliest")
        ))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for
      * more details.
      */
    sealed abstract class Unsafe extends Serializable:
        private[kyo] def client: org.apache.kafka.clients.consumer.KafkaConsumer[Array[Byte], Array[Byte]]

        /** Closes the underlying client. */
        def close()(using AllowUnsafe): Unit = client.close()

        def safe: KafkaConsumer = this
    end Unsafe

    private object Unsafe:
        def apply(client: org.apache.kafka.clients.consumer.KafkaConsumer[Array[Byte], Array[Byte]]): Unsafe =
            new Unsafe:
                private[kyo] def client = client
    end Unsafe
end KafkaConsumer
