# http-server

Minimal JVM HTTP server, à la [`http.server`](https://docs.python.org/3/library/http.server.html), mainly only for coursier's own tests' purposes

[![CI](https://github.com/coursier/http-server/actions/workflows/ci.yml/badge.svg)](https://github.com/coursier/http-server/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.get-coursier/http-server_3.svg)](https://central.sonatype.com/artifact/io.get-coursier/http-server_3)

Relies on [http4s](https://github.com/http4s/http4s)

Use like
```
$ coursier launch io.get-coursier:http-server_3:1.0.2
```
(spawns a web server serving files in the current directory).

See the available options with
```
$ coursier launch io.get-coursier:http-server_3:1.0.2 -- --help
```

Build with
```
$ ./mill compile
```

Run from sources with
```
$ ./mill run --help
```
