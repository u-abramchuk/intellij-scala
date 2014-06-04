package org.jetbrains.plugins.scala
package util.macroDebug

import _root_.scala.util.Random
import com.intellij.openapi.editor.{LogicalPosition, EditorFactory, Editor}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import java.awt.{BorderLayout, Dimension}
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.ex.{EditorEx, EditorGutterComponentEx}
import javax.swing.{JComponent, JLayeredPane}
import com.intellij.psi._
import com.intellij.openapi.vfs.newvfs.FileAttribute
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import com.intellij.openapi.editor.event.{CaretEvent, CaretListener}
import java.util
import com.intellij.lang.Language
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.plugins.scala.worksheet.runconfiguration.WorksheetViewerInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.LanguageFileType
import org.jetbrains.plugins.scala.worksheet.ui.WorksheetEditorPrinter

/**
 *  Sheet implementation for macro debugging based on Scala worksheet
 */
object MacrosheetEditorPrinter {
  val END_MESSAGE = "Output exceeds cutoff limit.\n"
  val BULK_COUNT = 15
  val IDLE_TIME_MLS = 1000

  private val LAST_WORKSHEET_RUN_RESULT = new FileAttribute("LastMacrosheetRunResult", 1, false)

  private val patched = new util.WeakHashMap[Editor, String]()

  def getPatched = patched

  def synch(originalEditor: Editor, worksheetViewer: Editor) {
    def createListener(recipient: Editor) = new CaretListener {
      override def caretAdded(e: CaretEvent) {}

      override def caretRemoved(e: CaretEvent) {}

      override def caretPositionChanged(e: CaretEvent) {
        if (!e.getEditor.asInstanceOf[EditorImpl].getContentComponent.hasFocus) return
        val actualLine = Math.min(e.getNewPosition.line, recipient.getDocument.getLineCount)
        recipient.getCaretModel.moveToLogicalPosition(new LogicalPosition(actualLine, 0))
      }
    }

    def checkAndAdd(don: Editor, recipient: Editor) {
      if (patched.get(don) == null) {
        don.getCaretModel.addCaretListener(createListener(recipient))
        patched.put(don, "")
      }
    }


    (originalEditor, worksheetViewer) match {
      case (originalImpl: EditorImpl, viewerImpl: EditorImpl) =>
        ApplicationManager.getApplication invokeLater new Runnable {
          override def run() {
            checkAndAdd(originalImpl, viewerImpl)
            checkAndAdd(viewerImpl, originalImpl)

            val line = originalImpl.getCaretModel.getLogicalPosition.line
            viewerImpl.getCaretModel.moveToLogicalPosition(
              new LogicalPosition(Math.min(line, viewerImpl.getDocument.getLineCount), 0)
            )

            viewerImpl.getScrollPane.getVerticalScrollBar setModel originalImpl.getScrollPane.getVerticalScrollBar.getModel
          }
        }
      case _ =>
    }
  }

  def saveWorksheetEvaluation(file: ScalaFile, result: String) {
    LAST_WORKSHEET_RUN_RESULT.writeAttributeBytes(file.getVirtualFile, result.getBytes)
  }

  def loadWorksheetEvaluation(file: ScalaFile): Option[String] = {
    Option(LAST_WORKSHEET_RUN_RESULT.readAttributeBytes(file.getVirtualFile)) map (new String(_))
  }

  def deleteWorksheetEvaluation(file: ScalaFile) {
    LAST_WORKSHEET_RUN_RESULT.writeAttributeBytes(file.getVirtualFile, Array.empty[Byte])
  }

  def newWorksheetUiFor(editor: Editor, virtualFile: VirtualFile) =
    new WorksheetEditorPrinter(editor,  createMacrosheetViewer(editor, virtualFile),
      PsiManager getInstance editor.getProject findFile virtualFile match {
        case scalaFile: ScalaFile => scalaFile
        case _ => null
      }
    )

  def createMacrosheetViewer(editor: Editor, virtualFile: VirtualFile, modelSync: Boolean = true): Editor = {
    val editorComponent = editor.getComponent
    val project = editor.getProject

    val prop = if (editorComponent.getComponentCount > 0) editorComponent.getComponent(0) match {
      case splitter: JBSplitter => splitter.getProportion
      case _ => 0.5f
    } else 0.5f
    val dimension = editorComponent.getSize()
    val prefDim = new Dimension(dimension.width / 2, dimension.height)

    editor.getSettings setFoldingOutlineShown false

    val worksheetViewer = WorksheetViewerInfo getViewer editor match {
      case editorImpl: EditorImpl => editorImpl
      case _ => createBlankEditorWithLang(project, ScalaFileType.SCALA_LANGUAGE, ScalaFileType.SCALA_FILE_TYPE)
              .asInstanceOf[EditorImpl]
    }

    worksheetViewer.getComponent setPreferredSize prefDim

    val gutter: EditorGutterComponentEx = worksheetViewer.getGutterComponentEx
    if (gutter != null && gutter.getParent != null) gutter.getParent remove gutter

    if (modelSync) synch(editor, worksheetViewer)
    editor.getContentComponent.setPreferredSize(prefDim)

    if (!ApplicationManager.getApplication.isUnitTestMode) {
      val child = editorComponent.getParent
      val parent = child.getParent

      @inline def patchEditor() {
        val pane = new JBSplitter(false, prop)
        pane setSecondComponent worksheetViewer.getComponent

        (parent, child) match {
          case (parentPane: JLayeredPane, _) =>
            parentPane remove child
            pane.setFirstComponent(child.getComponent(0).asInstanceOf[JComponent])
            parentPane.add(pane, BorderLayout.CENTER)
          case (_, childPane: JLayeredPane) =>
            childPane remove editorComponent
            pane setFirstComponent editorComponent
            childPane.add(pane, BorderLayout.CENTER)
          case _ =>
        }
      }

      if (parent.getComponentCount > 1) parent.getComponent(1) match {
        case splitter: JBSplitter => splitter setSecondComponent worksheetViewer.getComponent
        case _ => patchEditor()
      } else patchEditor()
    }

    WorksheetViewerInfo.addViewer(worksheetViewer, editor)
    worksheetViewer
  }

  private def createBlankEditorWithLang(project: Project, lang: Language, fileType: LanguageFileType): Editor = {
    val file: PsiFile = PsiFileFactory.getInstance(project)
            .createFileFromText("dummy_" + Random.nextString(10), lang, "")
    val doc = PsiDocumentManager.getInstance(project).getDocument(file)
    val factory: EditorFactory = EditorFactory.getInstance
    val editor = factory.createViewer(doc, project)
    val editorHighlighter = EditorHighlighterFactory.getInstance
            .createEditorHighlighter(project, fileType)
    editor.asInstanceOf[EditorEx].setHighlighter(editorHighlighter)
    editor setBorder null
    editor
  }

}





