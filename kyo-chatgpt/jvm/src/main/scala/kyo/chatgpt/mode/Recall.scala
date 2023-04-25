package kyo.chatgpt.mode

import kyo.aspects._
import kyo.chatgpt.ais._
import kyo.chatgpt.embeddings._
import kyo.clocks.Clocks
import kyo.concurrent.fibers.Fibers
import kyo.concurrent.timers.Timers
import kyo.consoles.Consoles
import kyo.core._
import kyo.ios.IOs
import kyo.loggers.Loggers
import kyo.randoms.Randoms
import kyo.requests._
import kyo.resources.Resources
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document._
import org.apache.lucene.index._
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search._
import org.apache.lucene.store._

import java.lang.reflect.Modifier
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import scala.jdk.CollectionConverters._

class Recall(prompt: String, ai: AI) extends Mode(Set(ai)) {
  def this(ai: AI) = this("", ai)

  private val analyzer = new StandardAnalyzer()
  private val indexPath =
    Paths.get(System.getProperty("user.home", ".") + "/.kyo/kyo-chatgpt/index")
  private val index = new MMapDirectory(indexPath)

  indexMessage("system", "init")

  private def indexMessage(role: String, content: String) =
    Embeddings(s"$role: $content").map { v =>
      IOs {
        val now       = LocalDateTime.now();
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);
        val timestamp = now.format(formatter);
        val doc       = new Document()
        doc.add(new StringField("timestamp", timestamp, Field.Store.YES))
        doc.add(new StringField("role", role, Field.Store.YES))
        doc.add(new TextField("content", content, Field.Store.YES))
        doc.add(new KnnFloatVectorField("embedding", v.vector.take(1024).toArray));

        val writerConfig = new IndexWriterConfig(analyzer)
        val writer       = new IndexWriter(index, writerConfig)
        writer.addDocument(doc)
        writer.close()
      }
    }

  private def search(query: String) =
    Embeddings(query).map { v =>
      IOs {
        val reader   = DirectoryReader.open(index)
        val searcher = new IndexSearcher(reader)
        val query    = new KnnFloatVectorQuery("embedding", v.vector.take(1024).toArray, 15)

        val hits    = searcher.search(query, 15).scoreDocs
        val results = hits.toList.map(hit => searcher.storedFields().document(hit.doc))

        reader.close()
        format(results)
      }
    }

  private def format(docs: List[Document]) =
    docs.reverse.zipWithIndex.map {
      case (doc, index) =>
        val timestampField = doc.getField("timestamp")
        val timestamp      = if (timestampField != null) timestampField.stringValue() else "N/A"
        val contentField   = doc.getField("content")
        val content        = if (contentField != null) contentField.stringValue() else "N/A"
        val roleField      = doc.getField("role")
        val role           = if (roleField != null) roleField.stringValue() else "N/A"

        s"$timestamp $role: $content"
    }.mkString("\n")

  def apply[S](
      ai: AI,
      msg: String
  )(next: String => String > (S | Aspects)) = {
    for {
      recall <- search(msg)
      _ <-
        if (recall.isEmpty()) ()
        else ai.user(
            "Consider these previous messages I exchanged with you:\n```\n" + recall + "\n```\n\n" + prompt
        )
      r <- next(msg + " (feel free to use the info in the previous messages I " +
        "shared with you but don't mention that I shared them with you)")
      _ <- indexMessage("user", msg)
      _ <- indexMessage("assistant", r)
    } yield r
  }
}
