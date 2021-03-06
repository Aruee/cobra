package net.flatmap.cobra.scalac

/*
 * Most of this is copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.reporters.AbstractReporter
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

import net.flatmap.cobra.Information
import net.flatmap.collaboration._

import scala.reflect.internal.util.{BatchSourceFile, Position}
import scala.tools.nsc.interactive.Global
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.collection.mutable.SortedSet
import scala.concurrent.{Future, Promise}
import scala.tools.nsc.interactive.Response
import scala.tools.refactoring.common.PimpedTrees
import scala.tools.refactoring.common.CompilerAccess
import scala.reflect.internal.util.OffsetPosition
import scala.util.Try
import scala.util.control.NonFatal

trait ScalaCompiler extends CompilerAccess with PimpedTrees { self: ScalaService =>
  /*
   * For a given FQ classname, trick the resource finder into telling us the containing jar.
   */
  private def classPathOfClass(className: String) = {
    val resource = className.split('.').mkString("/", "/", ".class")
    val path = getClass.getResource(resource).getPath
    if (path.indexOf("file:") >= 0) {
      val indexOfFile = path.indexOf("file:") + 5
      val indexOfSeparator = path.lastIndexOf('!')
      List(path.substring(indexOfFile, indexOfSeparator))
    } else {
      require(path.endsWith(resource))
      List(path.substring(0, path.length - resource.length + 1))
    }
  }

  private lazy val compilerPath = try {
    classPathOfClass("scala.tools.nsc.Interpreter")
  } catch {
    case e: Throwable =>
      throw new RuntimeException("Unable to load Scala interpreter from classpath (scala-compiler jar is missing?)", e)
  }

  private lazy val libPath = try {
    classPathOfClass("scala.AnyVal")
  } catch {
    case e: Throwable =>
      throw new RuntimeException("Unable to load scala base object from classpath (scala-library jar is missing?)", e)
  }

  /*
    * Try to guess our app's classpath.
    * This is probably fragile.
    */
  lazy val impliedClassPath: List[String] = {
    def getClassPath(cl: ClassLoader, acc: List[List[String]] = List.empty): List[List[String]] = {
      val cp = cl match {
        case urlClassLoader: URLClassLoader => urlClassLoader.getURLs.filter(_.getProtocol == "file").
          map(u => new File(u.toURI).getPath).toList
        case _ => Nil
      }
      cl.getParent match {
        case null => (cp :: acc).reverse
        case parent => getClassPath(parent, cp :: acc)
      }
    }

    val classPath = getClassPath(this.getClass.getClassLoader)
    val currentClassPath = classPath.head

    // if there's just one thing in the classpath, and it's a jar, assume an executable jar.
    currentClassPath ::: (if (currentClassPath.size == 1 && currentClassPath.head.endsWith(".jar")) {
      val jarFile = currentClassPath.head
      val relativeRoot = new File(jarFile).getParentFile()
      val nestedClassPath = new JarFile(jarFile).getManifest.getMainAttributes.getValue("Class-Path")
      if (nestedClassPath eq null) {
        Nil
      } else {
        nestedClassPath.split(" ").map { f => new File(relativeRoot, f).getAbsolutePath }.toList
      }
    } else {
      Nil
    }) ::: classPath.tail.flatten
  }


  val target = new VirtualDirectory("<memory>", None)

  lazy val settings = new scala.tools.nsc.Settings
  settings.outputDirs.setSingleOutput(target)

  val pathList = compilerPath ::: libPath
  settings.bootclasspath.value = pathList.mkString(File.pathSeparator)
  settings.classpath.value = (pathList ::: impliedClassPath).mkString(File.pathSeparator)
  //settings.YpresentationDebug.value = true
  //settings.YpresentationVerbose.value = true

  val messages = collection.mutable.Map.empty[String,SortedSet[(Int,Int,String,String)]]
  val identifiers = collection.mutable.Map.empty[String,SortedSet[(Int,Int,Option[String],Seq[String])]]
  val implicits = collection.mutable.Map.empty[String,SortedSet[(Int,Int,String)]]

  def markImplicit(pos: Position, t: String) = if (pos.isDefined) {
    if (!implicits.isDefinedAt(pos.source.file.name))
      implicits(pos.source.file.name) = SortedSet.empty
    val (start,length) =
      (pos.start, pos.end - pos.start)
    implicits(pos.source.file.name) += ((start,length,t))
  }

  def identifier(pos: Position, kind: String*): Unit = identifier(pos, None, kind :_*)

  def identifier(pos: Position, t: Option[String], kind: String*): Unit = if (pos.isDefined && pos.isRange) {
    if (!identifiers.isDefinedAt(pos.source.file.name))
      identifiers(pos.source.file.name) = SortedSet.empty(Ordering.by(x => (x._1,x._2)))
    val (start,length) =
      (pos.start, pos.end - pos.start)
    identifiers(pos.source.file.name) += ((start,length,t,kind))
  }

  lazy val reporter = new AbstractReporter {
    val settings = ScalaCompiler.this.settings

    def display(pos: Position, message: String, severity: Severity) = if (pos.isDefined) {
      val severityName = severity match {
        case ERROR => "error"
        case WARNING => "warning"
        case INFO => "info"
        case _ => "warning"
      }
      val (start,length) = try {
        (pos.start, pos.end - pos.start)
      } catch {
        case NonFatal(_) => (0,0)
      }
      if (!messages.isDefinedAt(pos.source.file.name)) messages(pos.source.file.name) = SortedSet.empty
      messages(pos.source.file.name) += ((start, length, severityName, message))
      annotate()
    }

    def displayPrompt {
      // no
    }

    override def reset {
      super.reset
    }
  }

  lazy val global = new Global(settings, reporter)

  def compilationUnitOfFile(f: tools.nsc.io.AbstractFile): Option[global.CompilationUnit] = None

  var classLoader = new AbstractFileClassLoader(target, this.getClass.getClassLoader)

  def reset() = {
    reporter.reset
    classLoader = new AbstractFileClassLoader(target, this.getClass.getClassLoader)
  }

  def complete(id: String, state: String, p: Int)(respond: List[global.Member] => Unit) = {
    val reloaded = new Response[Unit]
    val source = new BatchSourceFile(id, state)
    global.askReload(List(source), reloaded)

    val c = reloaded.get.left.foreach { _ =>
      val tcompletion = new Response[List[global.Member]]
      val pos = global.ask(() => new OffsetPosition(source, p))
      global.askTypeCompletion(pos, tcompletion)
      tcompletion.get(5000).get match {
        case Left(members) => global.ask( () => respond(members) )
        case Right(e) =>
          println("error " + e.getMessage())
          val scompletion = new Response[List[global.Member]]
          global.askScopeCompletion(pos, scompletion)
          scompletion.get(5000).get.left.foreach { members =>
            global.ask( () => respond(members) )
          }
      }
    }
  }

  def annotationsFromTree(tree: global.Tree): Unit = if (tree.pos.isDefined && tree.pos.isRange) tree match {
    case t: global.TypeTree =>
      val classes = "ref" :: "type" ::  "variable-3" ::
        (if (t.symbol.isTypeParameter) List("param") else Nil)
      identifier(t.namePosition, global.docComments.get(t.symbol).map(_.toString).orElse(Some(t.symbol.kindString + " " + t.symbol.nameString + t.symbol.signatureString)), classes :_*)
      t.children.foreach(annotationsFromTree(_))
    case c: global.TypeDef =>
      val classes = "def" :: "type" ::  "variable-3" ::
        (if (c.symbol.isTypeParameter) List("param") else Nil)
      identifier(c.namePosition,Some(c.symbol.kindString + " " + c.symbol.nameString + c.symbol.signatureString), classes :_*)
      c.children.foreach(annotationsFromTree(_))
    case c: global.ClassDef =>
      identifier(c.namePosition,Some(c.symbol.kindString + " " + c.symbol.nameString + c.symbol.signatureString), "def", "type")
      c.children.foreach(annotationsFromTree(_))
    case c: global.ModuleDef =>
      identifier(c.namePosition,Some(c.symbol.kindString + " " + c.symbol.nameString + c.symbol.signatureString), "def", "module")
      c.children.foreach(annotationsFromTree(_))
    case c: global.Bind =>
      identifier(c.namePosition,Some(c.symbol.kindString + " " + c.symbol.nameString + c.symbol.signatureString), "def", "local")
      c.children.foreach(annotationsFromTree(_))
    case c: global.Literal =>
      identifier(c.pos,Some("literal " + c.toString + ": " + c.tpe.toString().takeWhile(_ != '(')))
    case c: global.ValDef =>
      val classes = "def" :: "val-def" ::
        (if (c.symbol.isLocalToBlock) List("local","em") else Nil) ++
          (if (c.symbol.isParameter) List("param","variable-2") else Nil) ++
          (if (c.symbol.isVar) List("var-def","variable") else Nil)
      global.ask(() => identifier(c.namePosition,Some(c.symbol.kindString + " " + c.symbol.nameString + c.symbol.signatureString), classes :_*))
      c.children.foreach(annotationsFromTree(_))
    case c: global.DefDef =>
      val classes = "def" :: "def-def" ::
        (if (c.symbol.isLocalToBlock) List("local","em") else Nil) ++
          (if (c.symbol.isParameter) List("param","variable-2") else Nil) ++
          (if (c.symbol.isVar) List("var-def","variable") else Nil)
      global.ask(() => identifier(c.namePosition,Some(c.symbol.kindString + " " + c.symbol.nameString + c.symbol.signatureString), classes :_*))
      c.children.foreach(annotationsFromTree(_))
    case a: global.Apply if a.symbol != global.NoSymbol =>
      if (a.symbol.isImplicit) {
        markImplicit(a.namePosition, a.args.apply(0).tpe.toLongString + " -> " + a.tpe.toLongString)
      }
      a.children.foreach(annotationsFromTree(_))
    case c: global.Select if c.symbol != global.NoSymbol =>
      val classes = "ref" :: "select" ::
        global.ask(() => (if (c.symbol.isDeprecated) List("deprecated", "strikethrough") else Nil)) ++
          (if (c.symbol.isVar) List("variable") else Nil) ++
          (if (c.symbol.isLocalToBlock) List("local","em") else Nil) ++
          (if (c.symbol.isValueParameter) List("param","variable-2") else Nil) ++
          (if (c.symbol.isModule) List("module","qualifier") else Nil) ++
          (if (c.symbol.isConstructor) List("constructor", "variable-3") else Nil)
      global.ask(() => identifier(c.namePosition,Some(c.symbol.kindString + " " + c.symbol.nameString + c.symbol.signatureString), classes :_*))
      c.children.foreach(annotationsFromTree(_))
    case i: global.Ident if i.symbol != global.NoSymbol =>
      val classes = "ref" :: "ident" ::
        global.ask(() => (if (i.symbol.isDeprecated) List("deprecated") else Nil)) ++
          (if (i.symbol.isVar) List("variable") else Nil) ++
          (if (i.symbol.isLocalToBlock) List("local","em") else Nil) ++
          (if (i.symbol.isValueParameter) List("param","variable-2") else Nil) ++
          (if (i.symbol.isModule) List("module", "qualifier") else Nil) ++
          (if (i.symbol.isType) List("type", "variable-3") else Nil)
      global.ask(() => identifier(i.namePosition,Some(i.symbol.kindString + " " + i.symbol.nameString + i.symbol.signatureString),classes :_*))
      i.children.foreach(annotationsFromTree(_))
    case t: global.Tree =>
      t.children.foreach(annotationsFromTree(_))
  }

  def compile[T](id: String, state: String)(andThen: => T): Future[T] = {
    val promise = Promise[T]
    messages.values.foreach(_.clear)
    val source = new BatchSourceFile(id, state)
    val reloaded = new Response[Unit]
    global.askReload(List(source), reloaded)
    reloaded.get.left.foreach { _ =>
      val tree = new Response[global.Tree]
      global.askLoadedTyped(source, tree)
      tree.get.left.foreach { tree =>
        identifiers.get(source.path).foreach(_.clear())
        implicits.get(source.path).foreach(_.clear())
        annotationsFromTree(tree)
        annotateSemantics()
        annotate()
        promise.complete(Try(andThen))
      }
    }
    promise.future
  }


  def annotate() {
    messages.collect { case (path, messages) if files.isDefinedAt(path) =>
      val (state, client) = files(path)
      val tlength = state.length
      val annotations = messages.collect {
        case (offset, length, tpe, msg) if length > 0 =>
          new Annotations().plain(offset).annotate(length,
            tpe match {
              case "error" => AnnotationOptions(classes = Set("error"), messages = List(ErrorMessage(msg)))
              case "warning" => AnnotationOptions(classes = Set("warning"), messages = List(WarningMessage(msg)))
              case _ => AnnotationOptions(messages = List(InfoMessage(msg)))
            }
          ).plain(tlength - (offset + length))
      }.foldLeft(new Annotations().plain(tlength))(_.compose(_).get)
      client.localAnnotations("messages", annotations)
    }
  }

  def getInfo(id: String, state: String, from: Int, to: Int, guid: String) = compile(id,state) {
    identifiers.get(id).flatMap { messages =>
      val msgs = messages.filter {
        case (offset,length,tt,tpe) => length > 0 && from <= offset && offset <= to
      }
      if (msgs.isEmpty) None
      else Some {
        val res = msgs.toList.sortBy {
          case (offset,length,tt,tpe) => offset - length
        }.map {
          case (offset,length,tt,tpe) => (offset,offset + length,tt.mkString)
        }
        val min = res.map(_._1).min
        val max = res.map(_._2).max
        val txt = res.map(_._3).mkString("\n")
        Information(id,min,max,txt, guid)
      }
    }
  }

  def annotateSemantics() {
    identifiers.collect { case (path,messages) if files.isDefinedAt(path) =>
      val (state,client) = files(path)
      val tlength = state.length
      val annotations = messages.collect {
        case (offset, length, tt, tpe) if length > 0 =>
          new Annotations().plain(offset).annotate(length,
            (tpe.toList.map{ (x: String) => AnnotationOptions(classes = Set(x)) } ++
              tt.toList.map( (x: String) => AnnotationOptions(tooltip = Some(x)) )).reduce(_ ++ _)
          ).plain(tlength - (offset + length))
      }.foldLeft(new Annotations().plain(tlength))(_.compose(_).get)
      client.localAnnotations("semantic", annotations)
    }
    implicits.collect { case (path,messages) if files.isDefinedAt(path) =>
      val (state,client) = files(path)
      val tlength = state.length
      val annotations = messages.collect {
        case (offset, length, tt) if length > 0 =>
          new Annotations().plain(offset).annotate(length,
            AnnotationOptions(classes=Set("link"),tooltip = Some("implicit conversion: " + tt))
          ).plain(tlength - (offset + length))
      }.foldLeft(new Annotations().plain(tlength))(_.compose(_).get)
      client.localAnnotations("implicits", annotations)
    }
  }
}