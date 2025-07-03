package kyo.grpc.compiler.internal

private[compiler] object Types {

    val any = "Any"

    val int = "Int"

    val string = "String"

    def pending(t: String, s: String) = s"_root_.kyo.<[$t, $s]"

    def pendingGrpcRequest(t: String) = pending(t, grpcRequest)

    def pendingGrpcResponse(t: String) = pending(t, grpcResponse)

    val duration = "_root_.kyo.Duration"

    val frame = "_root_.kyo.Frame"

    val sync = "_root_.kyo.Sync"

    val resource = "_root_.kyo.Resource"

    def streamGrpcResponse(t: String) = s"_root_.kyo.Stream[$t, $grpcResponse]"

    def streamGrpcRequest(t: String) = s"_root_.kyo.Stream[$t, $grpcRequest]"

    val client = "_root_.kyo.grpc.Client"

    val clientCall = "_root_.kyo.grpc.ClientCall"

    val grpcResponse = "_root_.kyo.grpc.Grpc"

    val grpcRequest = "_root_.kyo.grpc.Grpc"

    val serverHandler = "_root_.kyo.grpc.ServerHandler"

    val service = "_root_.kyo.grpc.Service"

    val callOptions = "_root_.io.grpc.CallOptions"

    val channel = "_root_.io.grpc.Channel"

    val managedChannel = "_root_.io.grpc.ManagedChannel"

    def managedChannelBuilder(t: String) = s"_root_.io.grpc.ManagedChannelBuilder[$t]"

    val serverServiceDefinition = "_root_.io.grpc.ServerServiceDefinition"

}
