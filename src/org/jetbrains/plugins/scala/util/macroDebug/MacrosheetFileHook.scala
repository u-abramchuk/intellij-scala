package org.jetbrains.plugins.scala
package util.macroDebug

import com.intellij.openapi.project.Project
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.fileEditor._
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.{ScalaFileType, extensions}
import org.jetbrains.plugins.scala.components.{StopWorksheetAction, WorksheetProcess}
import java.awt.FlowLayout
import com.intellij.openapi.application.{ModalityState, ApplicationManager}
import org.jetbrains.plugins.scala.worksheet.runconfiguration.WorksheetViewerInfo
import org.jetbrains.plugins.scala.worksheet.actions.WorksheetFileHook

/**
 * Created by ibogomolov on 28.05.14.
 */
class MacrosheetFileHook(private val project: Project) extends ProjectComponent{

  override def projectOpened() {
    project.getMessageBus.connect(project).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, MacrosheetEditorListener)
  }

  override def projectClosed() {
    ApplicationManager.getApplication.invokeAndWait(new Runnable {
      def run() {
        WorksheetViewerInfo.invalidate()
      }
    }, ModalityState.any())
  }

  override def disposeComponent() {}

  override def initComponent() {}

  override def getComponentName: String = "Macrosheet component"

  def initActions(file: VirtualFile, run: Boolean, exec: Option[WorksheetProcess] = None) {
    //    scala.extensions.inReadAction {
    if (project.isDisposed) return

    val myFileEditorManager = FileEditorManager.getInstance(project)
    val editors = myFileEditorManager.getAllEditors(file)

    for (editor <- editors) {
      WorksheetFileHook.getAndRemovePanel(file) map {
        case ref =>
          val p = ref.get()
          if (p != null) myFileEditorManager.removeTopComponent(editor, p)
      }
      val panel = new WorksheetFileHook.MyPanel(file)

      panel.setLayout(new FlowLayout(FlowLayout.LEFT))

      if (run) new RunMacrosheetAction().init(panel) else exec map (new StopWorksheetAction(_).init(panel))
      new CleanMacrosheetAction().init(panel)

      myFileEditorManager.addTopComponent(editor, panel)
    }
    //    }
  }

  private object MacrosheetEditorListener extends FileEditorManagerListener{

    override def fileOpened(source:FileEditorManager,file:VirtualFile) {
      if (!ScalaMacroDebuggingUtil.isEnabled || ScalaFileType.DEFAULT_EXTENSION != file.getExtension)
        return

      MacrosheetFileHook.this.initActions(file,true)
      //      loadEvaluationResult(source,file)
    }

    override def selectionChanged(event:FileEditorManagerEvent) {}

    override def fileClosed(source:FileEditorManager,file:VirtualFile) {}

    private def loadEvaluationResult(source:FileEditorManager,file:VirtualFile) {
      source getSelectedEditor file match{
        case txt:TextEditor=>txt.getEditor match{
          case ext:EditorEx=>

            PsiDocumentManager getInstance project getPsiFile ext.getDocument match{
              case scalaFile:ScalaFile=>MacrosheetEditorPrinter.loadWorksheetEvaluation(scalaFile)foreach{
                case result if!result.isEmpty=>
                  val viewer=MacrosheetEditorPrinter.createMacrosheetViewer(ext,file)
                  val document=viewer.getDocument

                  extensions.inWriteAction{
                    document setText result
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                  }
                case _=>
              }
              case _=>
            }
          case _=>
        }
        case _=>
      }
    }

  }

}