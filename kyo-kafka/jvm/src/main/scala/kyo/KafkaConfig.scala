package kyo

import java.util.Properties
import scala.jdk.CollectionConverters.*

/** Connection and client settings shared by [[KafkaProducer]] and [[KafkaConsumer]].
  *
  * Wraps the Kafka client's `Properties` with an immutable, builder-style API. Start from [[KafkaConfig.apply]] with the
  * broker list, then refine with `withProperty` / `withProperties` for any `org.apache.kafka.clients` setting (security
  * protocol, SASL, serializers, and so on). Keys follow the Kafka convention, e.g. `"security.protocol"`.
  *
  * {{{
  *   val config = KafkaConfig("localhost:9092")
  *       .withProperty("client.id", "my-service")
  *       .withProperty("security.protocol", "SASL_SSL")
  * }}}
  *
  * @param bootstrapServers
  *   comma-separated `host:port` list of brokers used for the initial metadata fetch
  * @param properties
  *   additional Kafka client properties
  */
final case class KafkaConfig(bootstrapServers: String, properties: Dict[String, String] = Dict.empty) derives CanEqual:

    /** Returns a copy with one additional Kafka client property. */
    def withProperty(key: String, value: String): KafkaConfig =
        copy(properties = properties.update(key, value))

    /** Returns a copy with the given Kafka client properties merged in; existing keys are overwritten. */
    def withProperties(entries: Dict[String, String]): KafkaConfig =
        copy(properties = properties ++ entries)

    /** The Kafka client's `Properties`, with `bootstrap.servers` set from [[bootstrapServers]]. */
    def toProperties: Properties =
        val props = new Properties()
        props.put("bootstrap.servers", bootstrapServers)
        properties.foreach { case (k, v) => discard(props.put(k, v)) }
        props

object KafkaConfig:

    /** A config pointing at the given broker list with no extra properties. */
    def apply(bootstrapServers: String)(using Frame): KafkaConfig =
        KafkaConfig(bootstrapServers, Dict.empty)
end KafkaConfig
