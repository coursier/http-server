
lazy val `http-server` = project
  .in(file("."))
  .enablePlugins(PackPlugin)
  .settings(
    organization := "io.get-coursier",
    scalacOptions ++= Seq("-feature", "-deprecation"),
    Publish.settings,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-blaze-server" % "0.15.16a",
      "org.http4s" %% "http4s-dsl" % "0.15.16a",
      "org.slf4j" % "slf4j-nop" % "1.7.26",
      "com.github.alexarchambault" %% "case-app" % "1.2.0"
    )
  )

