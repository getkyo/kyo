package kyo.capabilities

import kyo.Abort
import kyo.Async
import kyo.IO
import kyo.Resource
import kyo.Stream
import sttp.capabilities.Streams

trait KyoStreams extends Streams[KyoStreams]:
    override type BinaryStream = Stream[Byte, Async & Resource]
    override type Pipe[A, B]   = Stream[A, Async & Resource] => Stream[B, Async & Resource]

object KyoStreams extends KyoStreams
