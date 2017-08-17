package xitrum.routing

import java.io.File
import scala.collection.mutable.ArrayBuffer
import scala.reflect.runtime.universe
import scala.util.control.NonFatal
import scala.tools.reflect.ToolBox

import sclasner.{FileEntry, Scanner}

import xitrum.{Action, Config, SockJsAction}
import xitrum.annotation._
import xitrum.sockjs.SockJsPrefix

case class DiscoveredAcc(
  xitrumVersion:             String,
  normalRoutes:              SerializableRouteCollection,
  sockJsWithoutPrefixRoutes: SerializableRouteCollection,
  sockJsMap:                 Map[String, SockJsClassAndOptions],
  swaggerMap:                Map[Class[_ <: Action], Swagger]
)

/** Scan all classes to collect routes from actions. */
object RouteCollector {
  import ActionAnnotations._

  def deserializeCacheFileOrRecollect(cachedFile: File, cl: ClassLoader): DiscoveredAcc = {
    var acc = DiscoveredAcc(
      "<Invalid Xitrum version>",
      new SerializableRouteCollection,
      new SerializableRouteCollection,
      Map.empty[String, SockJsClassAndOptions],
      Map.empty[Class[_ <: Action], Swagger]
    )

    // Only serialize/deserialize ActionTreeBuilder, which represents
    // traits/classes extending Action, and their relationship. The relationship
    // is for the (Swagger) annotation inheritance feature. The traits/classes
    // are then loaded to get annotations.
    val xitrumVersion     = xitrum.version.toString
    val actionTreeBuilder = Scanner.foldLeft(cachedFile, ActionTreeBuilder(xitrumVersion), discovered(cl) _)

    if (actionTreeBuilder.xitrumVersion != xitrumVersion) {
      // The caller should see that the Xitrum version is invalid and act properly
      acc
    } else {
      val ka = actionTreeBuilder.getConcreteActionsAndAnnotations(cl)
      ka.foreach { case (klass, annotations) =>
        acc = processAnnotations(acc, klass, annotations)
      }
      DiscoveredAcc(xitrumVersion, acc.normalRoutes, acc.sockJsWithoutPrefixRoutes, acc.sockJsMap, acc.swaggerMap)
    }
  }

  //----------------------------------------------------------------------------

  private def discovered(cl: ClassLoader)(acc: ActionTreeBuilder, entry: FileEntry): ActionTreeBuilder = {
    // At ActionTreeBuilder, we can't use ASM or Javassist to get annotations
    // (because they don't understand Scala annotations), we have to actually
    // classes anyway, here we guess class name from .class file name and
    // load the class.

    if (!entry.relPath.endsWith(".class")) return acc

    // Optimize: Ignore standard Java, standard Scala, Netty classes etc.; these can be thousands
    val relPath = if (File.separatorChar != '/') entry.relPath.replace(File.separatorChar, '/') else entry.relPath
    if (IgnoredPackages.isIgnored(relPath)) return acc

    try {
      val withoutExt      = relPath.substring(0, relPath.length - ".class".length)
      val className       = withoutExt.replace('/', '.')
      val klass           = cl.loadClass(className)
      val superclass      = klass.getSuperclass.asInstanceOf[Class[_]]
      val superclassNameo = if (superclass == null) None else Some(superclass.getName)
      acc.addBranches(klass.getName, superclassNameo, klass.getInterfaces.map(_.getName))
    } catch {
      // Probably java.lang.NoClassDefFoundError: javax/servlet/http/HttpServlet
      case _: java.lang.Error =>
        acc

      // Probably the .class file name -> class name guess was wrong
      case NonFatal(_) =>
        acc
    }
  }

  private def processAnnotations(
      acc:         DiscoveredAcc,
      klass:       Class[_ <: Action],
      annotations: ActionAnnotations
  ): DiscoveredAcc = {
    val className  = klass.getName
    val fromSockJs = className.startsWith(classOf[SockJsPrefix].getPackage.getName)
    val routes     = if (fromSockJs) acc.sockJsWithoutPrefixRoutes else acc.normalRoutes

    collectNormalRoutes(routes, className, annotations)
    val newSockJsMap = collectSockJsMap(acc.sockJsMap, className, annotations)
    collectErrorRoutes(routes, className, annotations)

    val newSwaggerMap = collectSwagger(annotations) match {
      case None          => acc.swaggerMap
      case Some(swagger) => acc.swaggerMap + (klass -> swagger)
    }
    DiscoveredAcc(acc.xitrumVersion, acc.normalRoutes, acc.sockJsWithoutPrefixRoutes, newSockJsMap, newSwaggerMap)
  }

  private def collectNormalRoutes(
      routes:      SerializableRouteCollection,
      className:   String,
      annotations: ActionAnnotations
  ) {
    val routeOrder          = optRouteOrder(annotations.routeOrder)  // -1: first, 1: last, 0: other
    val cacheSecs           = optCacheSecs(annotations.cache)        // < 0: cache action, > 0: cache page, 0: no cache
    val method_pattern_coll = ArrayBuffer.empty[(String, String)]

    annotations.routes.foreach { a =>
      listMethodAndPattern(a).foreach { m_p   => method_pattern_coll.append(m_p) }
    }

    method_pattern_coll.foreach { case (method, pattern) =>
      val compiledPattern   = RouteCompiler.compile(pattern)
      val serializableRoute = new SerializableRoute(method, compiledPattern, className, cacheSecs)
      val coll              = (routeOrder, method) match {
        case (-1, "GET") => routes.firstGETs
        case ( 1, "GET") => routes.lastGETs
        case ( 0, "GET") => routes.otherGETs

        case (-1, "POST") => routes.firstPOSTs
        case ( 1, "POST") => routes.lastPOSTs
        case ( 0, "POST") => routes.otherPOSTs

        case (-1, "PUT") => routes.firstPUTs
        case ( 1, "PUT") => routes.lastPUTs
        case ( 0, "PUT") => routes.otherPUTs

        case (-1, "PATCH") => routes.firstPATCHs
        case ( 1, "PATCH") => routes.lastPATCHs
        case ( 0, "PATCH") => routes.otherPATCHs

        case (-1, "DELETE") => routes.firstDELETEs
        case ( 1, "DELETE") => routes.lastDELETEs
        case ( 0, "DELETE") => routes.otherDELETEs

        case (-1, "WEBSOCKET") => routes.firstWEBSOCKETs
        case ( 1, "WEBSOCKET") => routes.lastWEBSOCKETs
        case ( 0, "WEBSOCKET") => routes.otherWEBSOCKETs

        case _ => throw new IllegalArgumentException
      }
      coll.append(serializableRoute)
    }
  }

  private def collectErrorRoutes(
      routes:      SerializableRouteCollection,
      className:   String,
      annotations: ActionAnnotations)
  {
    annotations.error.foreach { a =>
      val tpe       = a.tree.tpe
      val tpeString = tpe.toString
      if (tpeString == TYPE_OF_ERROR_404.toString) routes.error404 = Some(className)
      if (tpeString == TYPE_OF_ERROR_500.toString) routes.error500 = Some(className)
    }
  }

  private def collectSockJsMap(
      sockJsMap:   Map[String, SockJsClassAndOptions],
      className:   String,
      annotations: ActionAnnotations
  ): Map[String, SockJsClassAndOptions] =
  {
    var pathPrefix: String = null
    annotations.routes.foreach { a =>
      val tpe       = a.tree.tpe
      val tpeString = tpe.toString

      if (tpeString == TYPE_OF_SOCKJS.toString)
        pathPrefix = a.tree.children.tail.head.productElement(0).asInstanceOf[universe.Constant].value.toString
    }

    if (pathPrefix == null) {
      sockJsMap
    } else {
      val cl               = Thread.currentThread.getContextClassLoader
      val sockJsActorClass = cl.loadClass(className).asInstanceOf[Class[SockJsAction]]
      val noWebSocket      = annotations.sockJsNoWebSocket.isDefined
      var cookieNeeded     = Config.xitrum.response.sockJsCookieNeeded
      if (annotations.sockJsCookieNeeded  .isDefined) cookieNeeded = true
      if (annotations.sockJsNoCookieNeeded.isDefined) cookieNeeded = false
      sockJsMap + (pathPrefix -> new SockJsClassAndOptions(sockJsActorClass, !noWebSocket, cookieNeeded))
    }
  }

  //----------------------------------------------------------------------------

  /** -1: first, 1: last, 0: other */
  private def optRouteOrder(annotationo: Option[universe.Annotation]): Int = {
    annotationo match {
      case None => 0

      case Some(annotation) =>
        val tpe       = annotation.tree.tpe
        val tpeString = tpe.toString
        if (tpeString == TYPE_OF_FIRST.toString)
          -1
        else if (tpeString == TYPE_OF_LAST.toString)
          1
        else
          0
    }
  }

  /** < 0: cache action, > 0: cache page, 0: no cache */
  private def optCacheSecs(annotationo: Option[universe.Annotation]): Int = {
    annotationo match {
      case None => 0

      case Some(annotation) =>
        val tpe       = annotation.tree.tpe
        val tpeString = tpe.toString

        if (tpeString == TYPE_OF_CACHE_ACTION_DAY.toString)
          -annotation.tree.children.tail.head.toString.toInt * 24 * 60 * 60
        else if (tpeString == TYPE_OF_CACHE_ACTION_HOUR.toString)
          -annotation.tree.children.tail.head.toString.toInt      * 60 * 60
        else if (tpeString == TYPE_OF_CACHE_ACTION_MINUTE.toString)
          -annotation.tree.children.tail.head.toString.toInt           * 60
        else if (tpeString == TYPE_OF_CACHE_ACTION_SECOND.toString)
          -annotation.tree.children.tail.head.toString.toInt
        else if (tpeString == TYPE_OF_CACHE_PAGE_DAY.toString)
          annotation.tree.children.tail.head.toString.toInt * 24 * 60 * 60
        else if (tpeString == TYPE_OF_CACHE_PAGE_HOUR.toString)
          annotation.tree.children.tail.head.toString.toInt      * 60 * 60
        else if (tpeString == TYPE_OF_CACHE_PAGE_MINUTE.toString)
          annotation.tree.children.tail.head.toString.toInt           * 60
        else if (tpeString == TYPE_OF_CACHE_PAGE_SECOND.toString)
          annotation.tree.children.tail.head.toString.toInt
        else
          0
    }
  }

  /** @return Seq[(method, pattern)] */
  private def listMethodAndPattern(annotation: universe.Annotation): Seq[(String, String)] = {
    val tpe       = annotation.tree.tpe
    val tpeString = tpe.toString

    if (tpeString == TYPE_OF_GET.toString)
      getStrings(annotation).map(("GET", _))
    else if (tpeString == TYPE_OF_POST.toString)
      getStrings(annotation).map(("POST", _))
    else if (tpeString == TYPE_OF_PUT.toString)
      getStrings(annotation).map(("PUT", _))
    else if (tpeString == TYPE_OF_PATCH.toString)
      getStrings(annotation).map(("PATCH", _))
    else if (tpeString == TYPE_OF_DELETE.toString)
      getStrings(annotation).map(("DELETE", _))
    else if (tpeString == TYPE_OF_WEBSOCKET.toString)
      getStrings(annotation).map(("WEBSOCKET", _))
    else
      Seq.empty
  }

  private def getStrings(annotation: universe.Annotation): Seq[String] = {
    annotation.tree.children.tail.map { tree => tree.productElement(0).asInstanceOf[universe.Constant].value.toString }
  }

  //----------------------------------------------------------------------------

  private def collectSwagger(annotations: ActionAnnotations): Option[Swagger] = {
    val universeAnnotations = annotations.swaggers
    if (universeAnnotations.isEmpty) {
      None
    } else {
      val cl = Thread.currentThread.getContextClassLoader
      val rm = universe.runtimeMirror(cl)
      val tb = rm.mkToolBox()

      val swaggerArgs = universeAnnotations.map(annotation => tb.eval(tb.untypecheck(annotation.tree)).asInstanceOf[Swagger]).flatMap(_.swaggerArgs)

      Some(Swagger(swaggerArgs: _*))
    }
  }
}
