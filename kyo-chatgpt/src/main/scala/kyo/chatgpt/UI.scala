package kyo.chatgpt

import com.formdev.flatlaf.FlatDarkLaf
import javax.swing._
import java.awt._
import java.awt.event._
import scala.jdk.CollectionConverters._
import scala.main
import java.util.concurrent.Executors
import kyo.requests.Requests
import kyo.chatgpt.ais._
import kyo.core._
import kyo.concurrent.fibers._
import kyo.ios.IOs
import kyo.concurrent.channels.Channels
import scala.concurrent.duration.Duration
import kyo.KyoApp
import kyo.chatgpt.mode._
import kyo.aspects.Aspects

object UI extends App {

  FlatDarkLaf.setup()

  val frame = new JFrame("AI Chat")
  frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
  frame.setSize(800, 600)

  val chatHistory = new JTextArea
  chatHistory.setEditable(false)
  chatHistory.setLineWrap(true)
  chatHistory.setWrapStyleWord(true)

  val messageInput = new JTextField

  val aiModesList = new JList[String](Array("Recall", "Reflect"))
  aiModesList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
  aiModesList.setVisibleRowCount(10)
  aiModesList.setFixedCellWidth(150)
  aiModesList.setSelectedIndices(Array(0, 1))

  messageInput.addActionListener(new ActionListener {
    override def actionPerformed(e: ActionEvent): Unit = {
      val message = messageInput.getText.trim
      if (message.nonEmpty) {
        val selectedModes = aiModesList.getSelectedValuesList.asScala.map(_.toString)
        chatHistory.append(s"User: $message\n")
        messageInput.setText("")
        SwingUtilities.invokeLater(new Runnable {
          override def run(): Unit = {
            val response = processMessage(message, selectedModes.toArray)
            chatHistory.append(s"assistant: $response\n")
          }
        })
      }
    }
  })

  val mainPanel = new JPanel(new BorderLayout())
  mainPanel.add(new JScrollPane(chatHistory), BorderLayout.CENTER)
  mainPanel.add(messageInput, BorderLayout.SOUTH)
  mainPanel.add(new JScrollPane(aiModesList), BorderLayout.EAST)

  frame.setContentPane(mainPanel)
  frame.setVisible(true)

  val ai   = IOs.run(AIs.init)
  val chan = IOs.run(Channels.blocking[(String, scala.List[String], Promise[String])](1024))

  def withModes[T, S](ai: AI, modes: Set[String], v: T > S): T > (S | AIs | Aspects) =
    if(modes.contains("Reflect")) {
      AIs.askAspect.let(Reflect(Set(ai)))(withModes(ai, modes - "Reflect", v))
    } else if(modes.contains("Recall")) {
      AIs.askAspect.let(Recall(ai))(withModes(ai, modes - "Recall", v))
    } else {
      v
    }

  IOs.run {
    Fibers.forkFiber {
      val run =
        AIs.iso {
          for {
            (msg, modes, p) <- chan.take
            resp            <- withModes(ai, modes.toSet, ai.ask(msg))
            _               <- p.complete(resp)
          } yield ()
        }
      def loop(): Unit > (Fibers | AIs) =
        run(_ => loop())
      Requests.run {
        AIs.run {
          loop()
        }
      }
    }
  }

  def processMessage(message: String, enabledModes: Array[String]): String = {
    KyoApp.run(Duration.Inf) {
      for {
        p <- Fibers.promise[String]
        _ <- chan.put((message, enabledModes.toList, p))
        r <- p.join
      } yield r
    }
  }
}
