package io.aeron

import scala.collection.mutable

object Aeron:
    private[aeron] val subscriptions = mutable.Map[(String, Int), mutable.ListBuffer[Subscription]]()

    def connect(ctx: Aeron.Context): Aeron =
        new Aeron

    class Context:
        def aeronDirectoryName(s: String): Aeron.Context = this
end Aeron

class Aeron:
    def addPublication(uri: String, streamId: Int): Publication =
        new Publication(uri, streamId)

    def addSubscription(uri: String, streamId: Int): Subscription =
        val sub = new Subscription()
        Aeron.subscriptions.getOrElseUpdate((uri, streamId), mutable.ListBuffer()) += sub
        sub
    end addSubscription

    def close(): Unit =
        Aeron.subscriptions.clear()
end Aeron
