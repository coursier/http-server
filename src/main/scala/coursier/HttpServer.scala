package coursier

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.{Authorization, `Content-Type`, `WWW-Authenticate`}
import org.http4s.server.Server

import java.io.{File, FileOutputStream}
import java.net.NetworkInterface
import java.nio.channels.{FileLock, OverlappingFileLockException}
import java.nio.file.Files
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

final case class AuthOptions(
  user: String = "",
  password: String = "",
  realm: String = ""
) {
  def checks(): Unit = {
    if (user.nonEmpty && password.isEmpty)
      Console.err.println(
        "Warning: authentication enabled but no password specified. " +
          "Specify one with the --password or -P option."
      )

    if (password.nonEmpty && user.isEmpty)
      Console.err.println(
        "Warning: authentication enabled but no user specified. " +
          "Specify one with the --user or -u option."
      )

    if ((user.nonEmpty || password.nonEmpty) && realm.isEmpty)
      Console.err.println(
        "Warning: authentication enabled but no realm specified. " +
          "Specify one with the --realm or -r option."
      )
  }
}

final case class VerbosityOptions(
  verbose: Int = 0,
  quiet: Boolean = false
) {
  lazy val verbosityLevel = verbose - (if quiet then 1 else 0)
}

final case class HttpServerOptions(
  auth: AuthOptions = AuthOptions(),
  verbosity: VerbosityOptions = VerbosityOptions(),
  directory: String = ".",
  host: String = "0.0.0.0",
  port: Int = 8080,
  acceptPost: Boolean = false,
  acceptPut: Boolean = false,
  acceptWrite: Boolean = false,
  listPages: Boolean = false,
  timeout: Option[String] = None
)

object HttpServerOptions {
  private val helpText =
    """Usage: http-server [options]
      |
      |Options:
      |  -d, --directory <path>       Served directory (default: .)
      |  -h, --host <host>            Host to bind (default: 0.0.0.0)
      |  -p, --port <port>            Port to bind (default: 8080)
      |  -s, --accept-post            Accept POST write requests
      |  -t, --accept-put             Accept PUT write requests
      |  -w, --accept-write           Accept write requests. Equivalent to -s -t
      |  -l, --list-pages             Generate content listing pages for directories
      |  -u, --user <user>            Basic authentication user
      |  -P, --password <password>    Basic authentication password
      |  -r, --realm <realm>          Basic authentication realm
      |      --timeout <duration>     Idle timeout, for example 30s or 5m
      |  -v, --verbose                Increase verbosity; can be repeated
      |  -q, --quiet                  Decrease verbosity
      |      --help                   Print this help and exit
      |""".stripMargin

  sealed trait ParseResult
  final case class Parsed(options: HttpServerOptions) extends ParseResult
  case object HelpAsked extends ParseResult
  final case class ParseError(message: String) extends ParseResult

  def help: String = helpText

  def parse(args: List[String]): ParseResult = {
    def requireValue(flag: String, rest: List[String]): Either[String, (String, List[String])] =
      rest match {
        case value :: tail if !value.startsWith("-") => Right((value, tail))
        case _ => Left(s"$flag requires a value")
      }

    def updateAuth(options: HttpServerOptions)(f: AuthOptions => AuthOptions): HttpServerOptions =
      options.copy(auth = f(options.auth))

    def updateVerbosity(options: HttpServerOptions)(f: VerbosityOptions => VerbosityOptions): HttpServerOptions =
      options.copy(verbosity = f(options.verbosity))

    def loop(options: HttpServerOptions, remaining: List[String]): Either[String, HttpServerOptions] =
      remaining match {
        case Nil => Right(options)
        case ("--help" | "-?") :: _ => Left(helpText)
        case ("-d" | "--directory") :: tail =>
          requireValue("--directory", tail).flatMap((value, rest) => loop(options.copy(directory = value), rest))
        case ("-h" | "--host") :: tail =>
          requireValue("--host", tail).flatMap((value, rest) => loop(options.copy(host = value), rest))
        case ("-p" | "--port") :: tail =>
          requireValue("--port", tail).flatMap { (value, rest) =>
            value.toIntOption match {
              case Some(port) => loop(options.copy(port = port), rest)
              case None => Left(s"--port expects an integer, got '$value'")
            }
          }
        case ("-s" | "--accept-post") :: tail => loop(options.copy(acceptPost = true), tail)
        case ("-t" | "--accept-put") :: tail => loop(options.copy(acceptPut = true), tail)
        case ("-w" | "--accept-write") :: tail => loop(options.copy(acceptWrite = true), tail)
        case ("-l" | "--list-pages") :: tail => loop(options.copy(listPages = true), tail)
        case ("-q" | "--quiet") :: tail =>
          loop(updateVerbosity(options)(_.copy(quiet = true)), tail)
        case ("-v" | "--verbose") :: tail =>
          loop(updateVerbosity(options)(v => v.copy(verbose = v.verbose + 1, quiet = v.quiet)), tail)
        case flag :: tail if flag.matches("-v{2,}") =>
          loop(updateVerbosity(options)(v => v.copy(verbose = v.verbose + flag.length - 1, quiet = v.quiet)), tail)
        case ("-u" | "--user") :: tail =>
          requireValue("--user", tail).flatMap { (value, rest) =>
            loop(updateAuth(options)(_.copy(user = value)), rest)
          }
        case ("-P" | "--password") :: tail =>
          requireValue("--password", tail).flatMap { (value, rest) =>
            loop(updateAuth(options)(_.copy(password = value)), rest)
          }
        case ("-r" | "--realm") :: tail =>
          requireValue("--realm", tail).flatMap { (value, rest) =>
            loop(updateAuth(options)(_.copy(realm = value)), rest)
          }
        case "--timeout" :: tail =>
          requireValue("--timeout", tail).flatMap((value, rest) => loop(options.copy(timeout = Some(value)), rest))
        case unknown :: _ => Left(s"Unrecognized argument: $unknown")
      }

    loop(HttpServerOptions(), args) match {
      case Right(options) => Parsed(options)
      case Left(`helpText`) => HelpAsked
      case Left(message) => ParseError(message)
    }
  }
}

object HttpServer {
  private def pathSegments(req: Request[IO]): Seq[String] =
    req.pathInfo.segments.map(_.decoded())

  def write(baseDir: File, path: Seq[String], req: Request[IO]): IO[Boolean] = {
    val acquire =
      IO.blocking {
        val f = new File(baseDir, path.mkString("/"))
        f.getParentFile.mkdirs()

        val os = new FileOutputStream(f)
        val lock =
          try os.getChannel.tryLock()
          catch {
            case _: OverlappingFileLockException => null
          }

        (os, lock)
      }

    def release(resources: (FileOutputStream, FileLock)): IO[Unit] =
      IO.blocking {
        val (os, lock) = resources
        if lock != null then lock.release()
        os.close()
      }

    Resource.make(acquire)(release).use {
      case (_, null) => IO.pure(false)
      case (os, _) =>
        req.body
          .through(fs2.io.writeOutputStream(IO.pure(os), closeAfterUse = false))
          .compile
          .drain
          .as(true)
    }
  }

  def isDirectory(f: File): IO[Option[Boolean]] =
    IO.blocking {
      if f.isDirectory then Some(true)
      else if f.isFile then Some(false)
      else None
    }

  def directoryListingPage(dir: File, title: String): IO[String] =
    IO.blocking {
      val entries = dir
        .listFiles()
        .toSeq
        .flatMap { f =>
          def name = f.getName
          if f.isDirectory then Seq(name + "/")
          else if f.isFile then Seq(name)
          else Nil
        }

      s"""<!DOCTYPE html>
         |<html>
         |<head>
         |<title>$title</title>
         |</head>
         |<body>
         |<ul>
         |${entries.map(e => "  <li><a href=\"" + e + "\">" + e + "</a></li>").mkString("\n")}
         |</ul>
         |</body>
         |</html>
       """.stripMargin
    }

  def unauthorized(realm: String): IO[Response[IO]] =
    Unauthorized(`WWW-Authenticate`(Challenge("Basic", realm)))

  def authenticated0(options: AuthOptions, verbosityLevel: Int)(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    if options.user.isEmpty && options.password.isEmpty then routes
    else
      HttpRoutes.of[IO] { case req =>
        def warn(msg: => String): IO[Unit] =
          IO.whenA(verbosityLevel >= 1)(
            IO(Console.err.println(s"${req.method.name} ${req.uri.path}: $msg"))
          )

        req.headers.get[Authorization] match {
          case None =>
            warn("no authentication provided") *> unauthorized(options.realm)
          case Some(auth) =>
            auth.credentials match {
              case BasicCredentials(username, password) =>
                if username == options.user && password == options.password then
                  routes.run(req).getOrElseF(NotFound())
                else {
                  val msg =
                    if username == options.user then "wrong password"
                    else s"unrecognized user $username"

                  warn(s"authentication failed ($msg)") *> unauthorized(options.realm)
                }
              case _ =>
                warn("no basic credentials found") *> unauthorized(options.realm)
            }
        }
      }

  def authenticated(options: AuthOptions, verbosityLevel: Int)(
    pf: PartialFunction[Request[IO], IO[Response[IO]]]
  ): HttpRoutes[IO] =
    authenticated0(options, verbosityLevel)(HttpRoutes.of[IO](pf))

  def putService(baseDir: File, auth: AuthOptions, verbosityLevel: Int): HttpRoutes[IO] =
    authenticated(auth, verbosityLevel) {
      case req if req.method == Method.PUT =>
        val path = pathSegments(req)
        IO.whenA(verbosityLevel >= 1)(IO(Console.err.println(s"PUT /${path.mkString("/")}"))) *>
          write(baseDir, path, req).flatMap {
            case true => Ok()
            case false => Locked()
          }
    }

  def postService(baseDir: File, auth: AuthOptions, verbosityLevel: Int): HttpRoutes[IO] =
    authenticated(auth, verbosityLevel) {
      case req if req.method == Method.POST =>
        val path = pathSegments(req)
        IO.whenA(verbosityLevel >= 1)(IO(Console.err.println(s"POST /${path.mkString("/")}"))) *>
          write(baseDir, path, req).flatMap {
            case true => Ok()
            case false => Locked()
          }
    }

  def getService(baseDir: File, auth: AuthOptions, verbosityLevel: Int, listPages: Boolean): HttpRoutes[IO] =
    authenticated(auth, verbosityLevel) {
      case req if req.method == Method.GET || req.method == Method.HEAD =>
        val path = pathSegments(req)
        val relPath = path.mkString("/")

        val resp =
          for {
            _ <- IO.whenA(verbosityLevel >= 1)(IO(Console.err.println(s"${req.method.name} /$relPath")))
            isDirOpt <- isDirectory(new File(baseDir, relPath))
            response <- isDirOpt match {
              case Some(true) if listPages =>
                directoryListingPage(new File(baseDir, relPath), relPath).flatMap { page =>
                  Ok(page).map(_.withContentType(`Content-Type`(MediaType.text.html)))
                }
              case Some(false) =>
                val f = new File(baseDir, relPath)
                IO.blocking(Files.readAllBytes(f.toPath)).flatMap { bytes =>
                  IO.whenA(verbosityLevel >= 1)(IO(Console.err.println(s"Length of $f data: ${bytes.length}"))) *>
                    Ok(bytes)
                }
              case _ => NotFound()
            }
          } yield response

        if req.method == Method.HEAD then resp.map(_.copy(body = Stream.empty))
        else resp
    }

  def server(options: HttpServerOptions): Resource[IO, Server] = {
    val baseDir = new File(if options.directory.isEmpty then "." else options.directory)
    val verbosityLevel = options.verbosity.verbosityLevel
    val routes =
      List(
        Option.when(options.acceptWrite || options.acceptPut)(putService(baseDir, options.auth, verbosityLevel)),
        Option.when(options.acceptWrite || options.acceptPost)(postService(baseDir, options.auth, verbosityLevel)),
        Some(getService(baseDir, options.auth, verbosityLevel, options.listPages))
      ).flatten.reduce(_ <+> _)

    for {
      host <- Resource.eval(IO.fromOption(Host.fromString(options.host))(new IllegalArgumentException(s"Invalid host: ${options.host}")))
      port <- Resource.eval(IO.fromOption(Port.fromInt(options.port))(new IllegalArgumentException(s"Invalid port: ${options.port}")))
      _ <- Resource.eval(IO(options.auth.checks()))
      _ <- Resource.eval(logListening(options, verbosityLevel))
      server <- {
        val builder = EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(routes.orNotFound)

        options.timeout
          .fold(builder)(timeout => builder.withIdleTimeout(Duration(timeout)))
          .build
      }
    } yield server
  }

  private def logListening(options: HttpServerOptions, verbosityLevel: Int): IO[Unit] =
    IO.whenA(verbosityLevel >= 0) {
      IO(Console.err.println(s"Listening on http://${options.host}:${options.port}")) *>
        IO.whenA(verbosityLevel >= 1 && options.host == "0.0.0.0") {
          IO(Console.err.println("Listening on addresses")) *>
            IO.blocking {
              for {
                itf <- NetworkInterface.getNetworkInterfaces.asScala
                addr <- itf.getInetAddresses.asScala
              } Console.err.println(s"  ${addr.getHostAddress} (${itf.getName})")
            }
        }
    }
}

object HttpServerApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    HttpServerOptions.parse(args) match {
      case HttpServerOptions.Parsed(options) =>
        HttpServer.server(options).useForever.as(ExitCode.Success)
      case HttpServerOptions.HelpAsked =>
        IO.println(HttpServerOptions.help).as(ExitCode.Success)
      case HttpServerOptions.ParseError(message) =>
        IO(Console.err.println(message)) *>
          IO(Console.err.println("Run with --help to see available options.")) *>
          IO.pure(ExitCode.Error)
    }
}
