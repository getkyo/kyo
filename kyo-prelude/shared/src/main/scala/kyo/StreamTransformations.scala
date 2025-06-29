package kyo

private[kyo] object StreamTransformations:
    inline def handleMap[V1, B, V2, S, S2](chunk: Chunk[V1], cont: => B < S)(f: V1 => V2 < S2)(using
        Tag[Emit[Chunk[V2]]],
        Frame
    ): B < (S & S2 & Emit[Chunk[V2]]) =
        Kyo.foreach(chunk)(f).map: newChunk =>
            if newChunk.isEmpty then cont
            else Emit.valueWith(newChunk)(cont)

    inline def handleMapPure[V1, B, V2, S](chunk: Chunk[V1], cont: => B < S)(f: V1 => V2)(using
        Tag[Emit[Chunk[V2]]],
        Frame
    ): B < (S & Emit[Chunk[V2]]) =
        val newChunk = chunk.map(f)
        if newChunk.isEmpty then cont
        else Emit.valueWith(newChunk)(cont)
    end handleMapPure

    inline def handleFilter[V1, B, S, S2](chunk: Chunk[V1], cont: => B < S)(f: V1 => Boolean < S2)(
        using
        Tag[Emit[Chunk[V1]]],
        Frame
    ): B < (S & S2 & Emit[Chunk[V1]]) =
        Kyo.filter(chunk)(f).map: newChunk =>
            if newChunk.isEmpty then cont
            else Emit.valueWith(newChunk)(cont)

    inline def handleFilterPure[V1, B, S](chunk: Chunk[V1], cont: => B < S)(f: V1 => Boolean)(
        using
        Tag[Emit[Chunk[V1]]],
        Frame
    ): B < (S & Emit[Chunk[V1]]) =
        val newChunk = chunk.filter(f)
        if newChunk.isEmpty then cont
        else Emit.valueWith(newChunk)(cont)
    end handleFilterPure
end StreamTransformations
