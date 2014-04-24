package org.jetbrains.plugins.scala
package util.macroDebug

import com.intellij.openapi.vfs.newvfs.FileAttribute
import com.intellij.psi.{PsiElement, PsiFileFactory, PsiFile}
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import collection.mutable
import lang.psi.api.ScalaFile
import collection.mutable.ListBuffer
import com.intellij.testFramework.LightVirtualFile
import lang.psi.api.expr.{ScReferenceExpression, MethodInvocation}
import lang.psi.api.statements.ScMacroDefinition
import com.intellij.openapi.util.SystemInfo

/**
 * User: Dmitry Naydanov
 * Date: 11/5/12
 */
object ScalaMacroDebuggingUtil {
  private[this] val MACRO_DEBUG_ENABLE_PROPERTY = "scala.macro.debug.enabled"
  
  val MACRO_SIGN_PREFIX = "<[[macro:" //=\
  val needFixCarriageReturn = SystemInfo.isWindows
  val isEnabled = true // System.getProperty(MACRO_DEBUG_ENABLE_PROPERTY) != null
  
  private[this] val SOURCE_FILE_NAME = new FileAttribute("PreimageFileName", 1, false)
  private[this] val SYNTHETIC_SOURCE_ATTRIBUTE = new FileAttribute("SyntheticMacroCode", 1, false)
  private[this] val SOURCE_CACHE = mutable.HashMap[String, PsiFile]()
  private[this] val SYNTHETIC_OFFSETS_MAP = mutable.HashMap[String, List[(Int, Int, Int)]]()
  private[this] val UPDATE_QUEUE = mutable.HashSet[String]()
  private[this] val MARKERS_CACHE = mutable.HashMap[String, Int]()
  private[this] val PREIMAGE_CACHE = mutable.HashMap[PsiFile, PsiFile]()

  def saveCode(fileName: String, code: java.util.ArrayList[String]) {
    import scala.collection.JavaConversions._

    if (!isEnabled) return 
    val file = VfsUtil.findFileByIoFile(new File(fileName stripPrefix MACRO_SIGN_PREFIX), true)

    val dataStream = SYNTHETIC_SOURCE_ATTRIBUTE writeAttribute file
    code foreach (dataStream writeUTF _.stripPrefix(MACRO_SIGN_PREFIX))
    dataStream flush()
    dataStream close()
    
    UPDATE_QUEUE += file.getCanonicalPath
  }

  def loadCode1(file: PsiFile, force: Boolean = false): PsiFile = {
    if (!isEnabled || file.getVirtualFile.isInstanceOf[LightVirtualFile]) return null

    val canonicalPath = file.getVirtualFile.getCanonicalPath

    def createFile(): PsiFile = {
      val expanded =
"""package MacroTest

import Macros.Macros._

object Runner {
  def main(args: Array[String]) = {
    println("Hello, World!")
    println(5)
  }

  implicit def doSmth(num:String) = num.toInt
}
10,128,138"""

      val lines = expanded split '\n'

      val offsets = ListBuffer.empty[(Int, Int, Int)]
      @inline def parse(s: String) = Integer parseInt s
      lines.last match {
        //match s with ///macro exansion - begin
        //read until ///macro expansion - end
        //calculate langth, start, end

        case s =>
          val nums = s split ","
          if (nums.length == 3) {
            offsets.append((parse(nums(0)), parse(nums(1)), parse(nums(2))))
          }
      }
      SYNTHETIC_OFFSETS_MAP += (canonicalPath -> offsets.result())

      val synFile = PsiFileFactory.getInstance(file.getManager.getProject).
              createFileFromText("expanded_" + file.getName,
                ScalaFileType.SCALA_FILE_TYPE, lines.dropRight(1).mkString("\n"), file.getModificationStamp, true).asInstanceOf[ScalaFile]

      SOURCE_CACHE += (canonicalPath -> synFile)
      PREIMAGE_CACHE += (synFile -> file)

      synFile
    }

    if (force || UPDATE_QUEUE.remove(canonicalPath)) createFile() else SOURCE_CACHE get canonicalPath getOrElse createFile()
  }

  def loadCode(file: PsiFile, force: Boolean = false): PsiFile = {
    if (!isEnabled || file.getVirtualFile.isInstanceOf[LightVirtualFile]) return null
    
    val canonicalPath = file.getVirtualFile.getCanonicalPath

    def createFile(): PsiFile = {
      val dataStream = SYNTHETIC_SOURCE_ATTRIBUTE readAttribute file.getVirtualFile
      if (dataStream == null) return null

      var line = dataStream readUTF()
      val linesRed = StringBuilder.newBuilder

      while (line != null && dataStream.available() > 0) {
        linesRed ++= (line map (c => if (c == 0) ' ' else c)) ++= "\n"
        line = dataStream readUTF()
      }

      //linesRed ++= line
      //unpack debug info 
      val offsets = ListBuffer.empty[(Int, Int, Int)]
      @inline def parse(s: String) = Integer parseInt s
      line split '|' foreach {
        case s =>
          val nums = s split ","
          if (nums.length == 3) {
            offsets.append((parse(nums(0)), parse(nums(1)), parse(nums(2))))
          }
      }
      SYNTHETIC_OFFSETS_MAP += (canonicalPath -> offsets.result())
      // /unpack

      dataStream.close()

      val synFile = PsiFileFactory.getInstance(file.getManager.getProject).
        createFileFromText("expanded_" + file.getName,
        ScalaFileType.SCALA_FILE_TYPE, linesRed.toString(), file.getModificationStamp, true).asInstanceOf[ScalaFile]

      SOURCE_CACHE += (canonicalPath -> synFile)
      PREIMAGE_CACHE += (synFile -> file)

      synFile
    }
    
    if (force || UPDATE_QUEUE.remove(canonicalPath)) createFile() else SOURCE_CACHE get canonicalPath getOrElse createFile()
  }
  
  def readPreimageName(file: PsiFile): Option[String] = 
    Option(SOURCE_FILE_NAME readAttributeBytes file.getVirtualFile) map (new String(_))
  
  def getPreimageFile(file: PsiFile) = PREIMAGE_CACHE get file

  def isLoaded(file: PsiFile) = SOURCE_CACHE get file.getVirtualFile.getCanonicalPath match {
    case Some(_) => true
    case _ => false
  }
  
  def tryToLoad(file: PsiFile) = !file.getVirtualFile.isInstanceOf[LightVirtualFile] && 
    (isLoaded(file) || loadCode1(file, false) != null)

  def getOffsets(file: PsiFile) = SYNTHETIC_OFFSETS_MAP get file.getVirtualFile.getCanonicalPath
  
  def getOffsetsCount(file: PsiFile) = SYNTHETIC_OFFSETS_MAP get file.getVirtualFile.getCanonicalPath match {
    case Some(offsets) => offsets.length
    case _ => 0 
  }
  
  def checkMarkers(fileName: String, markersCount: Int) = MARKERS_CACHE get fileName match {
    case Some(oldCount) => if (oldCount == markersCount) { false } else {
      MARKERS_CACHE += (fileName -> markersCount); true
    } 
    case None => MARKERS_CACHE += (fileName -> markersCount); true
  }

  def isMacroCall(element: PsiElement) = element match {
    case methodInvocation: MethodInvocation => methodInvocation.getEffectiveInvokedExpr match {
      case ref: ScReferenceExpression => ref.resolve() match {
        case _: ScMacroDefinition => true
        case _ => false
      }
      case _ => false
    }
    case _ => false
  }
}
