package kyo.internal

import kyo.*
import sttp.capabilities.StreamMaxLengthExceededException
import sttp.capabilities.Streams

trait KyoStreams extends Streams[KyoStreams]:
    override type BinaryStream = Stream[Byte, Async]
    override type Pipe[A, B]   = Stream[A, Async] => Stream[B, Async]

object KyoStreams extends KyoStreams
