/*             _ _     _                                                      *\
**            | (_)   | |                                                     **
**         ___| |_  __| | ___      clide 2                                    **
**        / __| | |/ _` |/ _ \     (c) 2012-2014 Martin Ring                  **
**       | (__| | | (_| |  __/     http://clide.flatmap.net                   **
**        \___|_|_|\__,_|\___|                                                **
**                                                                            **
**   This file is part of Clide.                                              **
**                                                                            **
**   Clide is free software: you can redistribute it and/or modify            **
**   it under the terms of the GNU Lesser General Public License as           **
**   published by the Free Software Foundation, either version 3 of           **
**   the License, or (at your option) any later version.                      **
**                                                                            **
**   Clide is distributed in the hope that it will be useful,                 **
**   but WITHOUT ANY WARRANTY; without even the implied warranty of           **
**   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the            **
**   GNU General Public License for more details.                             **
**                                                                            **
**   You should have received a copy of the GNU Lesser General Public         **
**   License along with Clide.                                                **
**   If not, see <http://www.gnu.org/licenses/>.                              **
\*                                                                            */

package net.flatmap.cobra.isabelle

import akka.actor.{ActorLogging, PoisonPill}
import isabelle._
import isabelle.Session
import isabelle.Build
import isabelle.Path
import isabelle.Document
import isabelle.XML
import isabelle.Isabelle_System
import net.flatmap.cobra.Information
import net.flatmap.collaboration.{Document => _, _}

import scala.concurrent.Promise
import scala.language.postfixOps

trait IsabelleSession { self: IsabelleService with IsabelleConversions with ActorLogging =>
  var session: Session     = null

  var outdated = Set.empty[Document.Node.Name]

  case class OpenedFile(id: String, clientInterface: ClientInterface[Char], state: String)

  var files = scala.collection.mutable.Map.empty[Document.Node.Name,(scala.concurrent.Future[Document.Version],OpenedFile)]

  def edit_command(
    buffer: String,
    cid: String,
    padding: Boolean,
    s: String): Option[Operation[Char]] =
  {
    val snap = session.snapshot(fileToNodeName(buffer))
    val content = files(fileToNodeName(buffer))._2.state
    if (!snap.is_outdated) {
      Document_ID.unapply(cid).flatMap { id =>
        snap.state.find_command(snap.version, id).flatMap { case (node, command) =>
          node.command_start(command).map { start =>
            val range = command.proper_range + start
            val length = content.length
            val after = length - range.stop
            val ops = if (padding) {
              Retain(start + range.length) :: Insert("\n" + s) :: Retain(after) :: Nil
            } else {
              Retain(start) :: Insert(s) :: Delete(range.length) :: Retain(after) :: Nil
            }
            Operation(ops)
          }
        }
      }
    } else None
  }

  def updateFile(name: Document.Node.Name, file: OpenedFile, update: List[(Document.Node.Name,Document.Node.Edit[Text.Edit,Text.Perspective])]): scala.concurrent.Future[Unit] = {
    session.update(Document.Blobs.empty, update)
    val p = Promise[Document.Version]()
    val s = scala.concurrent.Future{
      file.clientInterface.localAnnotations("substitutions", IsabelleMarkup.substitutions(file.state))
      file.clientInterface.localAnnotations("sub/superscript", IsabelleMarkup.scripts(file.state))
    }

    val version = session.current_state.history.tip.version
    version.map(p.success)
    files(name) = (p.future, file)
    p.future.map(_ => ())
  }

  def getInfo(id: String, from: Int, to: Int, guid: String): Option[Information] = {
    val snapshot = session.snapshot(fileToNodeName(id))
    IsabelleMarkup.tooltip(snapshot, Text.Range.apply(from,to)).map { x =>
      Information(id,x.range.start,x.range.stop,x.info.mkString, guid)
    }
  }

  def refreshAnnotations() = {
    for {
      node    <- outdated
      snapshot = session.snapshot(node,Nil)
      version  = snapshot.version
    } for {
      (v,state) <- files.get(snapshot.node_name)
      if v.value.flatMap(_.toOption) == Some(snapshot.version)
    } {
      state.clientInterface.localAnnotations("inner syntax", IsabelleMarkup.highlighting(snapshot))
      state.clientInterface.localAnnotations("output", IsabelleMarkup.output(snapshot, Set.empty))
      state.clientInterface.localAnnotations("errors", IsabelleMarkup.errors(snapshot))
      state.clientInterface.localAnnotations("warnings", IsabelleMarkup.warnings(snapshot))
      //state.clientInterface.localAnnotations("progress", IsabelleMarkup.progress(state.state, snapshot))
    }
    outdated = Set.empty
  }

  def start(env: Map[String,String]) = {
    env.get("ISABELLE_HOME").fold {
      sys.env.get("ISABELLE_HOME").fold {
        IsabelleUtil.locateInstallation.map( p =>
          isabelle.Isabelle_System.init(p.toString)
        ).fold {
          log.error("Isabelle is not configured! Please add 'env.isabelle_home' to 'cobra.conf'")
        } { _ =>

        }
      } (isabelle.Isabelle_System.init(_))
    } (isabelle.Isabelle_System.init(_))
    val ops = isabelle.Options.init
    val initialized = Promise[Unit]()
    log.info("building session content")
    val content = Build.session_content(ops, false, Nil, "HOL")
    session = new Session(new Resources(content.loaded_theories, content.known_theories, content.syntax) {
      override def append(dir: String, source_path: Path): String = {
        log.info("thy_load.append({}, {})", dir, source_path)
        val path = source_path.expand
        if (dir == "" || path.is_absolute)           
          File.platform_path(path)
        else {
          (Path.explode(dir) + source_path).expand.implode
        }
      }      
    })    
    session.phase_changed += Session.Consumer("cobra"){ p => p match {
      case Session.Startup  =>
        log.info("I'm starting up, please wait a second!")
      case Session.Shutdown =>
        log.info("I'm shutting down")
      case Session.Inactive =>
        self ! PoisonPill
      case Session.Failed   =>
        log.info("I couldn't start")
        if (!initialized.isCompleted)
          initialized.failure(sys.error("isabelle session failed to initialize"))
      case Session.Ready    =>
        session.update_options(ops)
        if (!initialized.isCompleted)
          initialized.success(())
    } }
    session.syslog_messages += Session.Consumer("cobra"){ msg =>
      log.info("SYSLOG: {}", XML.content(msg.body))
      log.info(XML.content(msg.body))
    }
    session.commands_changed += Session.Consumer("cobra"){ msg =>
      outdated ++= msg.nodes
      self ! RequireRefresh
    }
    session.start("cobra", List("HOL"))
    initialized.future
  }

  def stop = {    
    session.stop()
  }
}
