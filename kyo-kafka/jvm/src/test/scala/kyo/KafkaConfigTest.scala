package kyo

class KafkaConfigTest extends Test:

    "bootstrap servers land in properties" in {
        val props = KafkaConfig("localhost:9092").toProperties
        assert(props.get("bootstrap.servers") == "localhost:9092")
    }

    "withProperty adds a single entry" in {
        val config = KafkaConfig("localhost:9092").withProperty("client.id", "svc")
        assert(config.properties.get("client.id") == Maybe.Present("svc"))
        assert(config.toProperties.get("client.id") == "svc")
    }

    "withProperty overwrites an existing key" in {
        val config = KafkaConfig("localhost:9092")
            .withProperty("acks", "1")
            .withProperty("acks", "all")
        assert(config.toProperties.get("acks") == "all")
    }

    "withProperties merges entries" in {
        val config = KafkaConfig("localhost:9092")
            .withProperty("client.id", "svc")
            .withProperties(Dict("acks" -> "all", "retries" -> "3"))
        assert(config.toProperties.get("client.id") == "svc")
        assert(config.toProperties.get("acks") == "all")
        assert(config.toProperties.get("retries") == "3")
    }

    "config is immutable" in {
        val base    = KafkaConfig("localhost:9092")
        val derived = base.withProperty("client.id", "svc")
        assert(base.properties.isEmpty)
        assert(derived.properties.size == 1)
    }
end KafkaConfigTest
