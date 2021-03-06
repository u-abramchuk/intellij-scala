package org.jetbrains.plugins.scala
package lang.psi.api

import extensions._
import lang.psi.api.base.ScStableCodeReferenceElement
import com.intellij.psi._
import com.intellij.psi.scope.PsiScopeProcessor
import lang.psi.impl._
import lang.resolve.processor.{BaseProcessor, ResolveProcessor, ImplicitProcessor, ResolverEnv}
import lang.psi.impl.expr.ScReferenceExpressionImpl
import util.PsiTreeUtil
import lang.psi.api.toplevel.packaging.ScPackaging
import lang.resolve.ResolveUtils
import com.intellij.psi.impl.migration.PsiMigrationManager
import lang.psi.impl.toplevel.synthetic.SyntheticClasses
import com.intellij.openapi.progress.ProgressManager
import scala.collection.mutable
import lang.psi.api.toplevel.typedef.ScTypeDefinition
import lang.psi.types.result.TypingContext
import lang.psi.types.ScType
import caches.ScalaShortNamesCacheManager
import lang.psi.{ScImportsHolder, ScDeclarationSequenceHolder}
import com.intellij.psi.search.GlobalSearchScope
import collection.mutable.ArrayBuffer
import org.jetbrains.plugins.scala.lang.resolve.processor.PrecedenceHelper.PrecedenceTypes

/**
 * User: Dmitry Naydanov
 * Date: 12/12/12
 */
trait FileDeclarationsHolder extends PsiElement with ScDeclarationSequenceHolder with ScImportsHolder {
  override def processDeclarations(processor: PsiScopeProcessor,
                                   state: ResolveState,
                                   lastParent: PsiElement,
                                   place: PsiElement): Boolean = {
    val isScriptProcessed = this match {
      case scalaFile: ScalaFile if scalaFile.isScriptFile() => true
      case _ => false
    }
    
    if (isScriptProcessed && !super[ScDeclarationSequenceHolder].processDeclarations(processor,
      state, lastParent, place)) return false

    if (!super[ScImportsHolder].processDeclarations(processor,
      state, lastParent, place)) return false

    if (context != null) {
      return true
    }

    val scope = place.getResolveScope

    place match {
      case ref: ScStableCodeReferenceElement if ref.refName == "_root_" && ref.qualifier == None => {
        val top = ScPackageImpl(JavaPsiFacade.getInstance(getProject).findPackage(""))
        if (top != null && !processor.execute(top, state.put(ResolverEnv.nameKey, "_root_"))) return false
        state.put(ResolverEnv.nameKey, null)
      }
      case ref: ScReferenceExpressionImpl if ref.refName == "_root_" && ref.qualifier == None => {
        val top = ScPackageImpl(JavaPsiFacade.getInstance(getProject).findPackage(""))
        if (top != null && !processor.execute(top, state.put(ResolverEnv.nameKey, "_root_"))) return false
        state.put(ResolverEnv.nameKey, null)
      }
      case _ => {
        val defaultPackage = ScPackageImpl(JavaPsiFacade.getInstance(getProject).findPackage(""))
        if (place != null && PsiTreeUtil.getParentOfType(place, classOf[ScPackaging]) == null) {
          if (defaultPackage != null &&
            !ResolveUtils.packageProcessDeclarations(defaultPackage, processor, state, null, place)) return false
        }
        else if (defaultPackage != null && !BaseProcessor.isImplicitProcessor(processor)) {
          //we will add only packages
          //only packages resolve, no classes from default package
          val name = processor match {case rp: ResolveProcessor => rp.ScalaNameHint.getName(state) case _ => null}
          val facade = JavaPsiFacade.getInstance(getProject).asInstanceOf[com.intellij.psi.impl.JavaPsiFacadeImpl]
          if (name == null) {
            val packages = defaultPackage.getSubPackages(scope)
            val iterator = packages.iterator
            while (iterator.hasNext) {
              val pack = iterator.next()
              if (!processor.execute(pack, state)) return false
            }
            val migration = PsiMigrationManager.getInstance(getProject).getCurrentMigration
            if (migration != null) {
              val list = migration.getMigrationPackages("")
              val packages = list.toArray(new Array[PsiPackage](list.size)).map(ScPackageImpl(_))
              val iterator = packages.iterator
              while (iterator.hasNext) {
                val pack = iterator.next()
                if (!processor.execute(pack, state)) return false
              }
            }
          } else {
            val aPackage: PsiPackage = ScPackageImpl(facade.findPackage(name))
            if (aPackage != null && !processor.execute(aPackage, state)) return false
          }
        }
      }
    }

    this match {
      case scalaFile: ScalaFileImpl =>
        if (!SbtFile.processDeclarations(scalaFile, processor, state, lastParent, place)) return false    
      case _ =>
    }

    if (isScriptProcessed) {
      val syntheticValueIterator = SyntheticClasses.get(getProject).getScriptSyntheticValues.iterator
      while (syntheticValueIterator.hasNext) {
        val syntheticValue = syntheticValueIterator.next()
        ProgressManager.checkCanceled()
        if (!processor.execute(syntheticValue, state)) return false
      }
    }

    val checkPredefinedClassesAndPackages = processor match {
      case r: ResolveProcessor => r.checkPredefinedClassesAndPackages()
      case _ => true
    }

    val checkWildcardImports = processor match {
      case r: ResolveProcessor => r.checkWildcardImports()
      case _ => true
    }

    def checkObjects(objects: Seq[String], precedence: Int): Boolean = {
      val attachedQualifiers = new mutable.HashSet[String]()
      val implObjIter = importedObjects(getManager, scope, objects).iterator

      updateProcessor(processor, precedence) {
        while (implObjIter.hasNext) {
          val clazz = implObjIter.next()
          if (!attachedQualifiers.contains(clazz.qualifiedName)) {
            attachedQualifiers += clazz.qualifiedName
            ProgressManager.checkCanceled()

            clazz match {
              case td: ScTypeDefinition if !isScalaPredefinedClass =>
                var newState = state
                td.getType(TypingContext.empty).foreach {
                  case tp: ScType => newState = state.put(BaseProcessor.FROM_TYPE_KEY, tp)
                }
                if (!clazz.processDeclarations(processor, newState, null, place)) return false
              case _ =>
            }
          }
        }
      }
      true
    }

    def checkPackages(packages: Seq[String]): Boolean = {
      val implPIterator = packages.iterator
      while (implPIterator.hasNext) {
        val implP = implPIterator.next()
        ProgressManager.checkCanceled()
        val pack: PsiPackage = JavaPsiFacade.getInstance(getProject).findPackage(implP)
        if (pack != null && !ResolveUtils.packageProcessDeclarations(pack, processor, state, null, place)) return false
      }
      true
    }

    if (checkWildcardImports) {
      if (!checkObjects(implicitlyImportedObjects, PrecedenceTypes.WILDCARD_IMPORT)) return false
      if (!checkPackages(implicitlyImportedPackages)) return false
    }

    if (checkPredefinedClassesAndPackages) {
      if (!checkObjects(predefObjects, PrecedenceTypes.SCALA_PREDEF)) return false

      val scalaPack = ScPackageImpl.findPackage(getProject, "scala")
      val namesSet =
        if (scalaPack != null) ScalaShortNamesCacheManager.getInstance(getProject).getClassNames(scalaPack, scope)
        else Set.empty[String]

      def alreadyContains(className: String) = namesSet.contains(className)
      val classes = SyntheticClasses.get(getProject)
      val synthIterator = classes.getAll.iterator
      while (synthIterator.hasNext) {
        val synth = synthIterator.next()
        ProgressManager.checkCanceled()
        if (!alreadyContains(synth.getName) && !processor.execute(synth, state)) return false
      }

      val synthObjectsIterator = classes.syntheticObjects.iterator
      while (synthObjectsIterator.hasNext) {
        val synth = synthObjectsIterator.next()
        ProgressManager.checkCanceled()
        if (!alreadyContains(synth.name) && !processor.execute(synth, state)) return false
      }

      if (!checkPackages(predefPackages)) return false
    }

    true
  }

  //method extracted due to VerifyError in Scala compiler
  private def updateProcessor(processor: PsiScopeProcessor, priority: Int)(body: => Unit) {
    processor match {
      case b: BaseProcessor => b.definePriority(priority)(body)
      case _ => body
    }
  }

  private def importedObjects(manager: PsiManager, scope: GlobalSearchScope, objects: Seq[String]): Seq[PsiClass] = {
    val res = new ArrayBuffer[PsiClass]
    for (obj <- objects) {
      res ++= ScalaPsiManager.instance(manager.getProject).getCachedClasses(scope, obj)
    }
    res.toSeq
  }

  protected def isScalaPredefinedClass: Boolean

  protected def implicitlyImportedPackages: Seq[String] = Seq.empty

  protected def implicitlyImportedObjects: Seq[String] = Seq.empty
  
  private def predefObjects: Seq[String] = ScalaFileImpl.DefaultImplicitlyImportedObjects

  private def predefPackages: Seq[String] = ScalaFileImpl.DefaultImplicitlyImportedPackges
}
