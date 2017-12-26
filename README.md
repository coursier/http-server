# http-server

Minimal JVM HTTP server, à la [`http.server`](https://docs.python.org/3/library/http.server.html), mainly for test purposes

[![Build Status](https://travis-ci.org/coursier/http-server.svg?branch=master)](https://travis-ci.org/coursier/http-server)
[![Maven Central](https://img.shields.io/maven-central/v/io.get-coursier/http-server.svg)](https://maven-badges.herokuapp.com/maven-central/io.get-coursier/http-server)

Relies on [http4s](https://github.com/http4s/http4s)

Use like
```
$ coursier launch io.get-coursier:http-server_2.12:1.0.0
```
(spawns a web server serving files in the current directory).

See the available options with
```
$ coursier launch io.get-coursier:http-server_2.12:1.0.0 -- --help
```
