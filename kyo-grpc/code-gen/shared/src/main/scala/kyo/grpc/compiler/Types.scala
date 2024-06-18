package kyo.grpc.compiler

object Types {

    val unit = "_root_.scala.Unit"

    val executionContext = "_root_.scala.concurrent.ExecutionContext"

    def future(t: String) = s"_root_.scala.concurrent.Future[$t]"

    def pending(t: String, s: String) = s"_root_.kyo.<[$t, $s]"

    val grpcResponses = "_root_.kyo.grpc.GrpcResponses"

    def pendingGrpcResponses(t: String) = s"_root_.kyo.<[$t, $grpcResponses]"

    val javaServiceDescriptor = "_root_.com.google.protobuf.Descriptors.ServiceDescriptor"

    val serverServiceDefinition = "_root_.io.grpc.ServerServiceDefinition"

    def streamObserver(v: String) = s"_root_.io.grpc.stub.StreamObserver[$v]"

    val serverCalls = "_root_.io.grpc.stub.ServerCalls"

    val serviceDescriptor = "_root_.scalapb.descriptors.ServiceDescriptor"

    val abstractService = "_root_.scalapb.grpc.AbstractService"

    def serviceCompanion(a: String) = s"_root_.scalapb.grpc.ServiceCompanion[$a]"

}
