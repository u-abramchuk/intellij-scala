package org.jetbrains.plugins.scala
package codeInsight.intentions.booleans

import codeInsight.intentions.ScalaIntentionTestBase
import codeInsight.intention.booleans.ReplaceEqualsOrEqualityInMethodCallExprIntention

/**
 * @author Ksenia.Sautina
 * @since 4/20/12
 */

class ReplaceEqualsOrEqualityInMethodCallExprIntentionTest extends ScalaIntentionTestBase {
  val familyName = ReplaceEqualsOrEqualityInMethodCallExprIntention.familyName

  def testReplaceQuality() {
    val text = "if (a.<caret>==(b)) return"
    val resultText = "if (a.<caret>equals(b)) return"

    doTest(text, resultText)
  }

  def testReplaceQuality2() {
    val text = "if (a.eq<caret>uals(false)) return"
    val resultText = "if (a.<caret>==(false)) return"

    doTest(text, resultText)
  }
}