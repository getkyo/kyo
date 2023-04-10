package kyo

import core._
import aspects._
import ais._
import vectors._
import requests._

import java.nio.file.Paths
import io.pinecone.PineconeClient
import io.pinecone.PineconeClientConfig
import io.pinecone.PineconeConnectionConfig
import io.pinecone.PineconeConnection
import io.pinecone.proto.Vector
import com.google.common.primitives.Floats
import scala.jdk.CollectionConverters._
import com.google.protobuf.Struct
import com.google.protobuf.Value
import java.util.Date
import io.pinecone.proto.UpsertRequest
import io.pinecone.proto.UpsertResponse
import io.pinecone.proto.QueryVector
import io.pinecone.proto.QueryRequest
import kyo.clocks.Clocks
import kyo.consoles.Consoles
import kyo.concurrent.fibers.Fibers
import kyo.ios.IOs
import kyo.randoms.Randoms
import kyo.resources.Resources
import kyo.concurrent.timers.Timers
import kyo.loggers.Loggers

object jkljlk extends KyoApp {
  import recalls._
  def run(args: List[String]) =
    Consoles.println {
      Requests.run {
        AIs.run {
          AIs.init { ai =>
            Recalls(ai) {
              for {
                _ <- ai.ask("my name is flavio")
                r <- ai.ask("what's my name?")
              } yield r
            }
          }
        }
      }
    }
}

object recalls {

  private val clientConfig = {
    val apiKeyProp = "PINECONE_API_KEY"
    Option(System.getenv(apiKeyProp))
      .orElse(Option(System.getProperty(apiKeyProp))).map { apiKey =>
        PineconeClientConfig()
          .withApiKey(apiKey)
          .withEnvironment("us-east4-gcp")
          .withProjectName("kyo-chatgpt")
          .withServerSideTimeoutSec(10)
      }
  }

  private val connectionConfig =
    new PineconeConnectionConfig()
      .withIndexName("kyo-chatgpt")

  private class Recall(ai: AI, conn: PineconeConnection) extends Trait(Set(ai)) {

    private def index(role: String, content: String): Unit > AIs =
      Vectors.embed(s"$role: $content").map { v =>
        val vector =
          Vector.newBuilder()
            .addAllValues(v.values.map(v => (v: java.lang.Float)).asJava)
            .setMetadata(Struct.newBuilder()
              .putFields(
                  "timestamp",
                  Value.newBuilder().setStringValue((new Date).toString()).build()
              )
              .putFields("role", Value.newBuilder().setStringValue(role).build())
              .putFields("content", Value.newBuilder().setStringValue(content).build())
              .build())
            .build()
        val request = UpsertRequest.newBuilder()
          .addVectors(vector)
          .build();
        conn.getBlockingStub().upsert(request)
      }

    private def search(query: String) =
      Vectors.embed(query) { v =>

        val queryVector = QueryVector.newBuilder()
          .addAllValues(v.values.map(v => (v: java.lang.Float)).asJava)
          .build()

        val queryRequest = QueryRequest.newBuilder()
          .addQueries(queryVector)
          .setTopK(10)
          .setIncludeMetadata(true)
          .build()

        val queryResponse = conn.getBlockingStub().query(queryRequest)
        queryResponse.getResultsList().asScala.flatMap { result =>
          result.getMatchesList().asScala.map(_.getMetadata()).map { metadata =>
            val timestamp = metadata.getFieldsOrThrow("timestamp").getStringValue()
            val role      = metadata.getFieldsOrThrow("role").getStringValue()
            val content   = metadata.getFieldsOrThrow("content").getStringValue()
            s"$timestamp $role: $content"
          }
        }.mkString("\n")
      }

    def apply[S](
        ai: AI,
        msg: String
    )(next: String => String > (S | Aspects)): String > (S | Aspects | AIs) = {
      AIs.iso {
        AIs.ephemeral {
          for {
            recall <- search(msg)
            _ <-
              if (recall.isEmpty()) ()
              else ai.system("I've recovered the following previous conversations:\n\n" + recall)
            r <- next(msg)
            _ <- index("user", msg)
            _ <- index("assistant", r)
          } yield r
        }
      }
    }
  }

  object Recalls {

    private val logger = Loggers.init(getClass)

    def apply[T, S](ai: AI)(v: T > (S | AIs)): T > (S | AIs) =
      clientConfig.map { config =>
        val client = new PineconeClient(config)
        val conn   = client.connect(connectionConfig)
        AIs.iso(AIs.askAspect.let(new Recall(ai, conn))(v))
      }.getOrElse {
        AIs.iso {
          logger.warn("Pinecone API key not found, skipping recall") { _ =>
            v
          }
        }
      }
  }

}
