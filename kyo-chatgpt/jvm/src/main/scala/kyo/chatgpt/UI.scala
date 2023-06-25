package kyo.chatgpt

import com.formdev.flatlaf.FlatDarkLaf
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.MutableDataSet
import kyo.KyoApp
import kyo.aspects._
import kyo.chatgpt.ais._
import kyo.chatgpt.mode.Reflect
import kyo.chatgpt.mode._
import kyo.concurrent.channels._
import kyo.concurrent.fibers._
import kyo.consoles._
import kyo._
import kyo.ios._
import kyo.requests._
import kyo.tries._

import java.awt._
import java.awt.event._
import javax.swing._
import javax.swing.text.html.HTMLDocument
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._
import scala.util.Failure

case class ModeInfo(name: String, prompt: String = "") {
  override def toString: String = name + (if (prompt.nonEmpty) ": " + prompt else "")
}

object UI extends App {
  FlatDarkLaf.setup()

  val predefinedModes = scala.List(
      // ModeInfo("Recall"),
      // ModeInfo("Reflect")
  )

  val frame = new JFrame("AI Chat")
  frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
  frame.setSize(800, 600)

  val chatHistory = new JTextPane
  chatHistory.setEditable(false)
  chatHistory.setContentType("text/html")

  val parser   = Parser.builder().build()
  val renderer = HtmlRenderer.builder().build()

  def appendChat(text: String): Unit = {
    val document = parser.parse(text)
    val html     = renderer.render(document) + " <br /><br />"
    val doc      = chatHistory.getDocument.asInstanceOf[HTMLDocument]
    doc.insertAfterEnd(doc.getCharacterElement(doc.getLength), html)
  }

  val messageInput = new JTextField

  val aiModesListModel = new DefaultListModel[ModeInfo]
  predefinedModes.foreach(aiModesListModel.addElement)
  val aiModesList = new JList[ModeInfo](aiModesListModel)
  aiModesList.setVisibleRowCount(10)
  aiModesList.setFixedCellWidth(150)

  val addButton      = new JButton("+")
  val removeButton   = new JButton("-")
  val moveUpButton   = new JButton("↑")
  val moveDownButton = new JButton("↓")

  addButton.addActionListener((_: ActionEvent) => {
    val modeForm = new ModeForm(frame, predefinedModes)
    val mode     = modeForm.showAndGetResult()
    mode.foreach(aiModesListModel.addElement)
  })

  removeButton.addActionListener((_: ActionEvent) => {
    val selectedIndex = aiModesList.getSelectedIndex
    if (selectedIndex != -1) {
      aiModesListModel.remove(selectedIndex)
    }
  })

  moveUpButton.addActionListener((_: ActionEvent) => {
    val selectedIndex = aiModesList.getSelectedIndex
    if (selectedIndex > 0) {
      val mode = aiModesListModel.getElementAt(selectedIndex)
      aiModesListModel.remove(selectedIndex)
      aiModesListModel.insertElementAt(mode, selectedIndex - 1)
      aiModesList.setSelectedIndex(selectedIndex - 1)
    }
  })

  moveDownButton.addActionListener((_: ActionEvent) => {
    val selectedIndex = aiModesList.getSelectedIndex
    if (selectedIndex != -1 && selectedIndex < aiModesListModel.size() - 1) {
      val mode = aiModesListModel.getElementAt(selectedIndex)
      aiModesListModel.remove(selectedIndex)
      aiModesListModel.insertElementAt(mode, selectedIndex + 1)
      aiModesList.setSelectedIndex(selectedIndex + 1)
    }
  })

  val modesPanel  = new JPanel(new BorderLayout())
  val buttonPanel = new JPanel(new FlowLayout())
  buttonPanel.add(addButton)
  buttonPanel.add(removeButton)
  buttonPanel.add(moveUpButton)
  buttonPanel.add(moveDownButton)
  modesPanel.add(buttonPanel, BorderLayout.NORTH)
  modesPanel.add(new JScrollPane(aiModesList), BorderLayout.CENTER)

  messageInput.addActionListener((e: ActionEvent) => {
    val message = messageInput.getText.trim
    if (message.nonEmpty) {
      val selectedModes = aiModesListModel.elements().asScala.toList
      appendChat(s"*User*: $message")
      messageInput.setText("")
      SwingUtilities.invokeLater(() => {
        val response = processMessage(message, selectedModes)
        appendChat(s"*AI*: $response")
      })
    }
  })

  val mainPanel = new JPanel(new BorderLayout())
  mainPanel.add(new JScrollPane(chatHistory), BorderLayout.CENTER)
  mainPanel.add(messageInput, BorderLayout.SOUTH)
  mainPanel.add(modesPanel, BorderLayout.EAST)

  frame.setContentPane(mainPanel)
  frame.setVisible(true)

  val ai   = IOs.run(AIs.init)
  val chan = IOs.run(Channels.blocking[(String, scala.List[ModeInfo], Promise[String])](1024))

  def withModes[T, S](
      ai: AI,
      modes: scala.List[ModeInfo],
      v: T > S
  ): T > (S with AIs with Aspects) =
    modes match {
      case Nil => v
      case h :: t =>
        h.name match {
          case "Reflect" =>
            AIs.askAspect.let(Reflect(h.prompt, Set(ai)))(withModes(ai, t, v))
          case "Recall" =>
            AIs.askAspect.let(Recall(h.prompt, ai))(withModes(ai, t, v))
          case _ =>
            withModes(ai, t, v)
        }
    }

  IOs.run {
    Fibers.forkFiber {
      val run =
        for {
          (msg, modes, p) <- chan.take
          resp            <- withModes(ai, modes.reverse, ai.ask(msg))
          _               <- p.complete(resp)
        } yield ()
      def loop(): Unit > (Fibers with AIs) =
        run.map(_ => loop())
      Consoles.run {
        Tries.run {
          Requests.run {
            AIs.run {
              loop()
            }
          }
        }.map {
          case Failure(exception) =>
            Consoles.println(exception.toString())
          case _ =>
        }
      }
    }
  }

  def processMessage(message: String, enabledModes: scala.List[ModeInfo]): String =
    IOs.run {
      KyoApp.runFiber(Duration.Inf) {
        for {
          p <- Fibers.promise[String]
          _ <- chan.put((message, enabledModes.toList, p))
          r <- p.get
        } yield r
      }.block
    }
}

class ModeForm(parent: JFrame, predefinedModes: scala.List[ModeInfo]) {
  private val dialog = new JDialog(parent, "Add Mode", true)
  dialog.setSize(300, 150)
  dialog.setLocationRelativeTo(parent)

  private val modeComboBox   = new JComboBox[ModeInfo](predefinedModes.toArray)
  private val parameterField = new JTextField(20)
  private val addButton      = new JButton("Add")
  private val cancelButton   = new JButton("Cancel")

  modeComboBox.addActionListener((_: ActionEvent) => {
    val selectedMode = modeComboBox.getSelectedItem.asInstanceOf[ModeInfo]
    parameterField.setText(selectedMode.prompt)
  })

  addButton.addActionListener((_: ActionEvent) => {
    val selectedMode = modeComboBox.getSelectedItem.asInstanceOf[ModeInfo]
    val mode         = selectedMode.copy(prompt = parameterField.getText)
    dialog.setVisible(false)
    result = Some(mode)
  })

  cancelButton.addActionListener((_: ActionEvent) => {
    dialog.setVisible(false)
    result = None
  })

  val formPanel = new JPanel(new GridLayout(0, 2))
  formPanel.add(new JLabel("Mode:"))
  formPanel.add(modeComboBox)
  formPanel.add(new JLabel("Parameter:"))
  formPanel.add(parameterField)

  val buttonPanel = new JPanel(new FlowLayout())
  buttonPanel.add(addButton)
  buttonPanel.add(cancelButton)

  val contentPanel = new JPanel(new BorderLayout())
  contentPanel.add(formPanel, BorderLayout.CENTER)
  contentPanel.add(buttonPanel, BorderLayout.SOUTH)

  dialog.setContentPane(contentPanel)
  dialog.setModal(true)

  private var result: Option[ModeInfo] = None

  def showAndGetResult(): Option[ModeInfo] = {
    dialog.setVisible(true)
    result
  }
}
