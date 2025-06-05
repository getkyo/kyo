package io.aeron

class Aeron private[aeron] (): // Changed private[kyo] to private[aeron]
    // Minimal stubs based on Topic.scala usage
    def addPublication(channel: String, streamId: Int): Publication   = ???
    def addSubscription(channel: String, streamId: Int): Subscription = ???
    def close(): Unit                                                 = ???
end Aeron

object Aeron:
    // Inner Context class stub, matching usage `new Aeron.Context().aeronDirectoryName(...)`
    class Context():
        def aeronDirectoryName(name: String): Context = this // common pattern for fluent builders
    end Context

    def connect(ctx: Aeron.Context): Aeron = ???
end Aeron
