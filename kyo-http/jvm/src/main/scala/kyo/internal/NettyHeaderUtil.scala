package kyo.internal

import io.netty.handler.codec.http.HttpHeaders as NettyHeaders
import kyo.*
import scala.annotation.tailrec

/** Extracts Netty HTTP headers into kyo's flat-array HttpHeaders representation. */
private[kyo] object NettyHeaderUtil:

    def extract(nettyHeaders: NettyHeaders): HttpHeaders =
        val headerCount = nettyHeaders.size()
        val arr         = new Array[String](headerCount * 2)
        val iter        = nettyHeaders.iteratorAsString()
        @tailrec def fill(i: Int): Unit =
            if i < headerCount && iter.hasNext then
                val entry = iter.next()
                arr(i * 2) = entry.getKey
                arr(i * 2 + 1) = entry.getValue
                fill(i + 1)
        fill(0)
        HttpHeaders.fromFlatArrayNoCopy(arr)
    end extract

end NettyHeaderUtil
