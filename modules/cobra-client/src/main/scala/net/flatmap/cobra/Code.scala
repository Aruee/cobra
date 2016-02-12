package net.flatmap.cobra

import org.scalajs.dom.Element
import net.flatmap.js.util._
import org.scalajs.dom.ext.Ajax

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

/**
  * Created by martin on 12.02.16.
  */
object Code {
  class Snippets private[Code] (root: NodeSeqQuery) extends Map[String,Snippet] {
    def get(id: String) = root.query(s"code#$id").elements.headOption.map { code =>
      new Snippet(code)
    }

    def iterator = root.query(s"code").elements.iterator.collect {
      case code if code.id != null && code.id.nonEmpty =>
        (code.id, new Snippet(code))
    }

    def +[S >: Snippet](kv: (String,S)): Map[String,Snippet] = ???
    def -(k: (String)): Map[String,Snippet] = ???
  }

  class Snippet private[Code] (code: Element) {
    def content: String = stripIndentation(code.textContent)
    def content_=(value: String): Unit = code.textContent = value
  }

  def loadDelayed(root: NodeSeqQuery) = Future.sequence {
    root.query(s"code[src]").elements.filter(!_.getAttribute("src").startsWith("#")).map { code =>
      val src = code.getAttribute("src")
      Ajax.get(src).collect {
        case response if response.status == 200 => code.textContent = response.responseText
      }.recover {
        case NonFatal(e) =>
          code.textContent = s"NOT FOUND: $src"
      }
    }
  }.map(_ => ())

  def stripIndentation(raw: String): String = {
    val strippedFront = raw.lines.dropWhile(!_.exists(!_.isWhitespace)).toSeq
    strippedFront.headOption.fold("") { firstLine =>
      val s = strippedFront.map(_.stripPrefix(firstLine.takeWhile(_.isWhitespace))).mkString("\n")
      s.take(s.lastIndexWhere(!_.isWhitespace) + 1)
    }
  }

  def getCodeSnippets(root: NodeSeqQuery) = new Snippets(root)

  def injectSnippets(root: NodeSeqQuery, snippets: Snippets) = {
    root.query("code[src^='#']").elements.foreach { code =>
      val src = code.getAttribute("src").tail
      code.innerHTML = snippets.get(src).map(_.content).getOrElse(s"UNDEFINED SRC '$src'")
    }
  }
}
