package kyo

import io.aeron.Aeron
import io.aeron.ExclusivePublication
import io.aeron.Image
import io.aeron.Publication
import io.aeron.archive.Archive
import io.aeron.cluster.ClusteredMediaDriver
import io.aeron.cluster.client.AeronCluster
import io.aeron.cluster.client.EgressListener
import io.aeron.cluster.codecs.AdminRequestType
import io.aeron.cluster.codecs.AdminResponseCode
import io.aeron.cluster.codecs.CloseReason
import io.aeron.cluster.codecs.EventCode
import io.aeron.cluster.service.ClientSession
import io.aeron.cluster.service.Cluster
import io.aeron.cluster.service.ClusteredService
import io.aeron.cluster.service.ClusteredServiceContainer
import io.aeron.driver.MediaDriver
import io.aeron.logbuffer.Header
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.agrona.DirectBuffer
import scala.annotation.tailrec

final case class Broker private (resolveUri: () => String):

    def publish[A: Topic.AsMessage, S](
        stream: Stream[A, S],
        retrySchedule: Schedule = Broker.defaultRetrySchedule
    )(using Frame, Tag[A]): Unit < (Topic & S & Abort[Topic.Backpressured | Closed] & Async) =
        IO(Topic.publish(resolveUri(), retrySchedule)(stream))

    def stream[A: Topic.AsMessage](
        retrySchedule: Schedule = Broker.defaultRetrySchedule
    )(using Frame, Tag[A]): Stream[A, Topic & Abort[Topic.Backpressured] & Async] =
        Stream(IO(Topic.stream[A](resolveUri(), retrySchedule).emit))

end Broker

object Broker:
    case class NodeConfig(id: Int, host: String, port: Int)

    val defaultRetrySchedule = Topic.defaultRetrySchedule

    def initClient(
        basePath: Path,
        nodes: Set[NodeConfig]
    )(using Frame): Broker < (IO & Resource) =
        if nodes.isEmpty then
            Abort.panic(new IllegalArgumentException("At least one node must be specified"))
        else if nodes.size == 1 then
            // For single-node clusters, connect directly to the node
            val node     = nodes.head
            val endpoint = s"${node.host}:${node.port}"
            val uri      = s"aeron:udp?endpoint=$endpoint"
            initWithDriver(basePath, Absent) { (driver, aeronDir) =>
                Resource.acquireRelease(Broker(() => uri)) { _ =>
                    IO(driver.close())
                }
            }
        else
            // For multi-node clusters, use cluster state listener
            initClusterClient(basePath, nodes)

    /** Initialize a broker that's also a cluster node */
    def initNode(
        basePath: Path,
        nodes: Set[NodeConfig],
        nodeId: Int
    )(using Frame): Broker < (IO & Resource) =
        // Validate that the node ID exists in the provided nodes
        if !nodes.exists(_.id == nodeId) then
            Abort.panic(new IllegalArgumentException(s"Node ID $nodeId not found in provided nodes configuration"))
        else if nodes.size == 1 then
            // For single-node clusters, use simplified approach
            initWithDriver(basePath, Present(nodeId)) { (driver, aeronDir) =>
                Resource.acquireRelease(Broker(() => "aeron:ipc")) { _ =>
                    IO(driver.close())
                }
            }
        else
            // For multi-node, initialize server then client
            for
                _      <- initClusterServer(basePath, nodes, nodeId)
                client <- initClusterClient(basePath, nodes)
            yield client

    /** Initialize the cluster server component */
    private def initClusterServer(
        basePath: Path,
        nodes: Set[NodeConfig],
        nodeId: Int,
        retrySchedule: Schedule = Topic.defaultRetrySchedule
    )(using Frame): Unit < (IO & Resource) =
        println(s"[DEBUG] Initializing cluster server node $nodeId")

        // Create the directory for this node
        val dirName      = s"node-$nodeId"
        val aeronDirPath = Path(basePath, "broker", dirName)

        for
            _ <- Path(aeronDirPath).mkDir
        yield IO {
            // Node configuration
            val nodeConfig = nodes.find(_.id == nodeId).get

            // Prepare the cluster members string (format: id,host,port)
            val clusterMembers = nodes.map(n => s"${n.id},${n.host},${n.port}").mkString(",")

            // Configure media driver
            val mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirPath.toJava.toString)

            // Configure cluster service
            val serviceContext = new ClusteredServiceContainer.Context()
                .aeronDirectoryName(aeronDirPath.toJava.toString)
                .clusteredService(new BrokerClusterService(retrySchedule))

            // Launch the clustered media driver with default archive
            println(s"[DEBUG] Launching clustered media driver for node $nodeId")

            // Use the simplified version of ClusteredMediaDriver.launch that doesn't require Archive
            val clusteredDriver =
                ClusteredMediaDriver.launch(
                    mediaDriverContext,
                    new Archive.Context,
                    new io.aeron.cluster.ConsensusModule.Context()
                        .clusterMemberId(nodeId)
                        .clusterMembers(clusterMembers)
                        .aeronDirectoryName(aeronDirPath.toJava.toString)
                )

            // Register cleanup
            Resource.acquireRelease(()) { _ =>
                IO(clusteredDriver.close())
            }
        }
    end initClusterServer

    /** Initialize a client connection to the cluster */
    private def initClusterClient(
        basePath: Path,
        nodes: Set[NodeConfig]
    )(using Frame): Broker < (IO & Resource) =
        println("[DEBUG] Initializing cluster client")

        // Set up client directory
        for
            now <- Clock.now
            dirName      = s"client-${now.toDuration.toMillis}"
            aeronDirPath = Path(basePath, "broker", dirName)
            _ <- Path(aeronDirPath).mkDir
        yield IO {
            // Configure media driver for client
            val mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirPath.toJava.toString)

            // Launch embedded media driver for client
            val driver = MediaDriver.launchEmbedded(mediaDriverContext)

            // Set up cluster client connection
            val memberEndpoints = nodes.map(n => s"${n.id}=${n.host}:${n.port}").mkString(",")

            // Find a free port for egress
            val egressPort =
                val socket = new java.net.DatagramSocket(null)
                try
                    socket.bind(new InetSocketAddress("localhost", 0))
                    socket.getLocalPort()
                finally
                    socket.close()
                end try
            end egressPort

            // Set up egress listener
            val egressListener = new ClusterStateListener(Maybe.empty, false)

            // Configure cluster client
            val ctx = new AeronCluster.Context()
                .aeronDirectoryName(driver.aeronDirectoryName())
                .ingressChannel("aeron:udp")
                .ingressEndpoints(memberEndpoints)
                .egressChannel(s"aeron:udp?endpoint=localhost:$egressPort")
                .egressListener(egressListener)
                .messageTimeoutNs(10000000000L) // 10 seconds

            // Give the server nodes time to start
            Thread.sleep(1000)

            // Connect to the cluster
            println("[DEBUG] Connecting to cluster")
            val cluster = AeronCluster.connect(ctx)
            println("[DEBUG] Connected to cluster successfully")

            // Create broker with cleanup
            Resource.acquireRelease(Broker(() => egressListener.resolveUri)) { _ =>
                IO {
                    cluster.close()
                    driver.close()
                }
            }
        }
        end for
    end initClusterClient

    private def initWithDriver[S](
        basePath: Path,
        nodeId: Maybe[Int]
    )(
        buildBroker: (MediaDriver, Path) => Broker < (IO & Resource & S)
    )(using Frame): Broker < (IO & Resource & S) =
        for
            now <- Clock.now
            dirName = nodeId match
                case Present(id) => s"node-$id"
                case Absent      => s"client-${now.toDuration.toMillis}"
            aeronDirPath = Path(basePath, "broker", dirName)
            _ <- Path(aeronDirPath).mkDir
        yield IO {
            val mediaDriverContext = new MediaDriver.Context()
                .aeronDirectoryName(aeronDirPath.toJava.toString)

            val driver = MediaDriver.launchEmbedded(mediaDriverContext)
            buildBroker(driver, aeronDirPath)
        }

    private class BrokerClusterService(retrySchedule: Schedule) extends ClusteredService:

        private val sessions = new CopyOnWriteArrayList[ClientSession]

        def onStart(cluster: Cluster, image: Image): Unit =
            println(s"[DEBUG] ClusterService: Starting as member ${cluster.memberId()}")

        def onSessionMessage(
            clusterSession: ClientSession,
            timestamp: Long,
            buffer: DirectBuffer,
            offset: Int,
            length: Int,
            header: Header
        ): Unit =
            // Forward the message to all connected clients
            sessions.forEach(session =>
                @tailrec def loop(retrySchedule: Schedule): Unit =
                    val result = session.offer(buffer, offset, length)
                    if result == Publication.BACK_PRESSURED then
                        retrySchedule.next(Instant.fromJava(java.time.Instant.now())) match
                            case Present((sleep, nextSchedule)) =>
                                Thread.sleep(sleep.toMillis)
                                loop(nextSchedule)
                            case Absent =>
                                throw IllegalStateException("BrokerClusterService retry budget exceeded")
                        end match
                    end if
                end loop
                loop(retrySchedule)
            )

        def onSessionOpen(session: ClientSession, timestamp: Long): Unit =
            discard(sessions.add(session))
        def onSessionClose(session: ClientSession, timestamp: Long, closeReason: CloseReason): Unit =
            discard(sessions.remove(session))

        def onTimerEvent(correlationId: Long, timestamp: Long): Unit        = {}
        def onServiceAction(correlationId: Long, timestamp: Long): Boolean  = false
        def onRoleChange(newRole: Cluster.Role): Unit                       = {}
        def onTakeSnapshot(snapshotPublication: ExclusivePublication): Unit = {}
        def onTerminate(cluster: Cluster): Unit                             = {}
    end BrokerClusterService

    final private class ClusterStateListener(nodeId: Maybe[Int], isSingleNode: Boolean = false) extends EgressListener:
        // If it's a single node cluster and we are that node, assume we're the leader immediately
        @volatile private var currentEndpoint =
            if isSingleNode && nodeId.nonEmpty then Present("aeron:ipc")
            else Maybe.empty[String]

        def resolveUri: String =
            currentEndpoint.getOrElse(bug("Cluster endpoint not yet resolved"))

        override def onNewLeader(
            clusterSessionId: Long,
            leadershipTermId: Long,
            leaderMemberId: Int,
            ingressEndpoints: String
        ): Unit =
            println(s"[DEBUG] New cluster leader: $leaderMemberId")
            if nodeId.contains(leaderMemberId) then
                // If we are the leader, use IPC
                currentEndpoint = Present("aeron:ipc")
            else
                // Otherwise, find the leader's endpoint
                val endpoints = ingressEndpoints.split(",")
                currentEndpoint =
                    Maybe.fromOption(endpoints.find(_.startsWith(s"$leaderMemberId=")))
                        .map(_.split("=")(1))
                        .map(e => s"aeron:udp?endpoint=$e")
            end if
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
