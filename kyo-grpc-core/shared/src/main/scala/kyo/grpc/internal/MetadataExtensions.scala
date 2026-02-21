package kyo.grpc.internal

import kyo.*
import kyo.grpc.SafeMetadata

extension (maybeMetadata: Maybe[SafeMetadata])

    inline def mergeIfDefined(maybeOther: Maybe[SafeMetadata])(using Frame): Maybe[SafeMetadata] < Sync =
        maybeMetadata match
            case Maybe.Present(metadata) =>
                Sync.defer(Maybe.Present(metadata.merge(maybeOther.getOrElse(SafeMetadata.empty))))
            case Maybe.Absent =>
                maybeOther

end extension
