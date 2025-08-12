package kyo.grpc

import io.grpc.Metadata
import kyo.*

extension (maybeMetadata: Maybe[Metadata])

    inline def mergeIfDefined(maybeOther: Maybe[Metadata])(using Frame): Maybe[Metadata] < Sync =
        maybeMetadata match
            case Maybe.Present(metadata) =>
                metadata.mergeIfDefined(maybeOther).map(Maybe.Present(_))
            case Maybe.Absent =>
                maybeOther

end extension

extension (metadata: Metadata)

    // TODO: Rename
    inline def mergeSafe(other: Metadata)(using Frame): Metadata < Sync =
        Sync.defer:
            // This is a mutation.
            metadata.merge(other)
            metadata

    inline def mergeIfDefined(maybeOther: Maybe[Metadata])(using Frame): Metadata < Sync =
        maybeOther match
            case Maybe.Present(other) => mergeSafe(other)
            case Maybe.Absent         => metadata

end extension
