package net.flatmap.cobra

import net.flatmap.collaboration._
import net.flatmap.js.codemirror._
import net.flatmap.js.reveal.Reveal
import org.scalajs.dom.raw.HTMLElement

import scala.collection.mutable
import scala.scalajs.js

object CodeMirrorOps {
  def changeToOperation(doc: Doc, change: EditorChange): Operation[Char] = {
    val from = doc.indexFromPos(change.from)
    val to = doc.indexFromPos(change.to)
    val end = doc.getValue().length
    val text = change.text.mkString("\n")
    val retainPrefix = if (from > 0) Some(Retain(from)) else None
    val retainSuffix = if (end > to) Some(Retain(end - to)) else None
    val insert = if (text.nonEmpty) Some(Insert(text)) else None
    val delete = if (to > from) Some(Delete(to - from)) else None
    Operation(retainPrefix.toList ++ insert ++ delete ++ retainSuffix)
  }

  def applyOperation(doc: Doc, operation: Operation[Char]) = {
    val opLen = operation.actions.foldLeft(0) {
      case (offset,Retain(n)) =>
        offset + n
      case (offset,Insert(s)) =>
        doc.replaceRange(s.mkString,doc.posFromIndex(offset))
        offset + s.length
      case (offset,Delete(n)) =>
        doc.replaceRange("",doc.posFromIndex(offset),doc.posFromIndex(offset + n))
        offset
    }
    assert(opLen == doc.getValue().length)
  }

  def applyAnnotations(doc: Doc, annotations: Annotations): () => Unit = {
    val (_,markers) = annotations.annotations.foldLeft((0,Seq.empty[Clearable])) {
      case ((offset,markers),Empty(n)) => (offset + n, markers)
      case ((offset,markers),Annotated(l,c)) =>
        val from = doc.posFromIndex(offset)
        val to = doc.posFromIndex(offset + l)
        val marker = c.substitute.fold {
          val options = TextMarkerOptions()
          options.shared = true
          if (c.classes.nonEmpty) options.className = c.classes.mkString(" ")
          c.tooltip.foreach(options.title = _)
          doc.markText(from,to, options)
        } { substitution =>
          val options = TextMarkerOptions()
          options.shared = true
          options.replacedWith =
            net.flatmap.js.util.HTML(s"<span class='cm-m-isabelle ${c.classes.mkString(" ")}'>$substitution</span>").head.asInstanceOf[HTMLElement]
          c.tooltip.foreach(options.replacedWith.title = _)
          doc.markText(from,to,options)
        }
        val buf = mutable.Buffer.empty[Clearable]
        c.messages.foreach { message =>
          doc.iterLinkedDocs { (doc: Doc, sharedHistory: Boolean) =>
            Option(doc.getEditor()).foreach { editor => if (editor != js.undefined && doc.firstLine() <= to.line && doc.lastLine() >= to.line) {
              message match {
                case ErrorMessage(txt) =>
                  val elem = net.flatmap.js.util.HTML(s"<div class='error ${c.classes.mkString(" ")}'>$txt</div>").head.asInstanceOf[HTMLElement]
                  buf += editor.addLineWidget(to.line, elem)
                case WarningMessage(txt) =>
                  val elem = net.flatmap.js.util.HTML(s"<div class='warning ${c.classes.mkString(" ")}'>$txt</div>").head.asInstanceOf[HTMLElement]
                  buf += editor.addLineWidget(to.line, elem)
                case InfoMessage(txt) =>
                  val elem = net.flatmap.js.util.HTML(s"<div class='info ${c.classes.mkString(" ")}'>$txt</div>").head.asInstanceOf[HTMLElement]
                  buf += editor.addLineWidget(to.line, elem)
                case OutputMessage(txt) =>
                  val elem = net.flatmap.js.util.HTML(s"<div class='info ${c.classes.mkString(" ")}'>$txt</div>").head.asInstanceOf[HTMLElement]
                  buf += editor.addLineWidget(to.line, elem)
                case StateMessage(txt) =>
                  val elem = if (editor.getOption("state-fragments") == "all")
                    net.flatmap.js.util.HTML(s"<div class='output all fragment ${c.classes.mkString(" ")}'>$txt</div>").head.asInstanceOf[HTMLElement]
                  else if (editor.getOption("state-fragments") == "single")
                    net.flatmap.js.util.HTML(s"<div class='output fragment ${c.classes.mkString(" ")}'>$txt</div>").head.asInstanceOf[HTMLElement]
                  else
                    net.flatmap.js.util.HTML(s"<div class='output ${c.classes.mkString(" ")}'>$txt</div>").head.asInstanceOf[HTMLElement]
                  buf += editor.addLineWidget(to.line, elem)

              }
            } }
          }
        }
        (offset + l, markers ++ buf :+ marker)
    }
    Reveal.sync()
    () => markers.foreach(_.clear())
  }
}
