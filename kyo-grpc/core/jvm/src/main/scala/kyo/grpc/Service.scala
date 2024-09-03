package kyo.grpc

import io.grpc.ServerServiceDefinition

trait Service:

    def definition: ServerServiceDefinition

end Service

object Service:

    given Conversion[Service, ServerServiceDefinition] = _.definition

end Service

