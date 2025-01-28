package kyo

import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.client.EgressListener
import io.aeron.cluster.codecs.AdminRequestType
import io.aeron.cluster.codecs.AdminResponseCode
import io.aeron.cluster.codecs.EventCode
import io.aeron.driver.MediaDriver
import io.aeron.logbuffer.Header
import java.net.InetAddress
import java.net.InetSocketAddress
import org.agrona.DirectBuffer

final case class Broker private (resolveUri: () => String):

    def publish[A: Topic.AsMessage, S](
        stream: Stream[A, S],
        retrySchedule: Schedule = Broker.defaultRetrySchedule
    )(using Frame, Tag[A]): Unit < (Topic & S & Abort[Topic.Backpressured | Closed]) =
        IO(Topic.publish(resolveUri(), retrySchedule)(stream))

    def stream[A: Topic.AsMessage](
        retrySchedule: Schedule = Broker.defaultRetrySchedule
    )(using Frame, Tag[A]): Stream[A, Topic & Abort[Topic.Backpressured]] =
        Stream(IO(Topic.stream[A](resolveUri(), retrySchedule).emit))
end Broker

object Broker:
    case class NodeConfig(id: Int, host: String, port: Int)

    val defaultRetrySchedule = Topic.defaultRetrySchedule

    def init(nodes: Set[NodeConfig])(using Frame): Broker < (IO & Resource) =
        IO {
            val driver          = MediaDriver.launchEmbedded()
            val localHost       = InetAddress.getLocalHost.getHostName
            val memberEndpoints = nodes.map(n => s"${n.id}=${n.host}:${n.port}").mkString(",")
            val nodeId          = Maybe.fromOption(nodes.find(_.host == localHost)).map(_.id)
            val stateListener   = new ClusterStateListener(nodeId)

            // Create a free port for egress
            val egressPort =
                val socket = new java.net.DatagramSocket(null)
                try
                    socket.bind(new InetSocketAddress("localhost", 0))
                    socket.getLocalPort()
                finally
                    socket.close()
                end try
            end egressPort

            val ctx = new AeronCluster.Context()
                .aeronDirectoryName(driver.aeronDirectoryName())
                .ingressChannel("aeron:udp") // Base channel for ingress
                .ingressEndpoints(memberEndpoints)
                .egressChannel(s"aeron:udp?endpoint=localhost:$egressPort") // Unique egress channel per client
                .egressListener(stateListener)

            val cluster = AeronCluster.connect(ctx)

            Resource.acquireRelease(Broker(() => stateListener.resolveUri)) { _ =>
                IO {
                    cluster.close()
                    driver.close()
                }
            }
        }

    final private class ClusterStateListener(nodeId: Maybe[Int]) extends EgressListener:
        @volatile private var currentEndpoint = Maybe.empty[String]

        def resolveUri: String =
            currentEndpoint.getOrElse(bug("a"))

        override def onNewLeader(
            clusterSessionId: Long,
            leadershipTermId: Long,
            leaderMemberId: Int,
            ingressEndpoints: String
        ): Unit =
            if nodeId.contains(leaderMemberId) then
                currentEndpoint = Present("aeron:ipc")
            else
                val endpoints = ingressEndpoints.split(",")
                currentEndpoint =
                    Maybe.fromOption(endpoints.find(_.startsWith(s"$leaderMemberId=")))
                        .map(_.split("=")(1))
                        .map(e => s"aeron:udp?endpoint=$e")
        end onNewLeader

        override def onMessage(
            clusterSessionId: Long,
            timestamp: Long,
            buffer: DirectBuffer,
            offset: Int,
            length: Int,
            header: Header
        ): Unit = ()

        override def onSessionEvent(
            correlationId: Long,
            clusterSessionId: Long,
            leadershipTermId: Long,
            leaderMemberId: Int,
            code: EventCode,
            detail: String
        ): Unit = ()

        override def onAdminResponse(
            clusterSessionId: Long,
            correlationId: Long,
            requestType: AdminRequestType,
            responseCode: AdminResponseCode,
            message: String,
            buffer: DirectBuffer,
            offset: Int,
            length: Int
        ): Unit = ()
    end ClusterStateListener
end Broker
