package org.jetbrains.plugins.scala
package lang
package psi
package impl
package base
package types

import com.intellij.lang.ASTNode
import psi.stubs.ScSelfTypeElementStub
import org.jetbrains.plugins.scala.lang.psi.api.base.types._
import com.intellij.psi.util.PsiTreeUtil
import java.lang.String
import psi.types.result.{TypeResult, Success, TypingContext}
import psi.types._
import api.toplevel.typedef.{ScTemplateDefinition, ScTypeDefinition}
import collection.mutable.ArrayBuffer
import api.base.ScStableCodeReferenceElement

/**
 * @author Alexander Podkhalyuzin
 */

class ScSelfTypeElementImpl extends ScalaStubBasedElementImpl[ScSelfTypeElement] with ScSelfTypeElement {
  def this(node: ASTNode) = {this (); setNode(node)}

  def this(stub: ScSelfTypeElementStub) = {this (); setStub(stub); setNode(null)}

  override def toString: String = "SelfType: " + name

  def nameId = findChildByType(TokenSets.SELF_TYPE_ID)

  def getType(ctx: TypingContext): TypeResult[ScType] = {
    val parent = PsiTreeUtil.getParentOfType(this, classOf[ScTemplateDefinition])
    assert(parent != null)
    typeElement match {
      case Some(ste) => {
        for {
          templateType <- parent.getType(ctx)
          selfType <- ste.getType(ctx)
          ct = ScCompoundType(Seq(templateType, selfType), Seq.empty, Seq.empty, ScSubstitutor.empty)
        } yield ct
      }
      case None => parent.getType(ctx)
    }
  }

  def typeElement: Option[ScTypeElement] = {
    val stub = getStub
    if (stub != null) {
      return stub.asInstanceOf[ScSelfTypeElementStub].getTypeElementText match {
        case "" => None
        case text => Some(ScalaPsiElementFactory.createTypeElementFromText(text, this, this))
      }
    }
    findChild(classOf[ScTypeElement])
  }

  def getClassNames: Array[String] = {
    val stub = getStub
    if (stub != null) {
      return stub.asInstanceOf[ScSelfTypeElementStub].getClassNames
    }
    val names = new ArrayBuffer[String]()
    def fillNames(typeElement: ScTypeElement) {
      typeElement match {
        case s: ScSimpleTypeElement => s.reference match {
          case Some(ref) => names += ref.refName
          case _ =>
        }
        case p: ScParameterizedTypeElement => fillNames(p.typeElement)
        case c: ScCompoundTypeElement =>
          c.components.foreach(fillNames)
        case _ => //do nothing
      }
    }
    typeElement.foreach(fillNames)
    names.toArray
  }
}