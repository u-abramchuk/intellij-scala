package org.jetbrains.plugins.scala
package actions

import com.intellij.openapi.project.DumbAware
import icons.Icons
import com.intellij.ide.fileTemplates.actions.CreateFromTemplateAction
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.actionSystem.{LangDataKeys, AnActionEvent}
import config.ScalaFacet
import com.intellij.openapi.module.Module

/**
 * Pavel Fatin
 */

class NewScalaScriptAction
        extends CreateFromTemplateAction(FileTemplateManager.getInstance().getInternalTemplate("Scala Script"))
        with DumbAware {
  override def update(e: AnActionEvent) {
    super.update(e)
    val module: Module = e.getDataContext.getData(LangDataKeys.MODULE.getName).asInstanceOf[Module]
    val isEnabled: Boolean = if (module == null) false else ScalaFacet.isPresentIn(module)
    e.getPresentation.setEnabled(isEnabled)
    e.getPresentation.setVisible(isEnabled)
    e.getPresentation.setIcon(Icons.SCRIPT_FILE_LOGO)
  }
}