import sbt._
import Keys._

object ActiveRecordBuild extends Build {
  val _version = "0.3.2"
  val isRelease = System.getProperty("release") == "true"

  val originalJvmOptions = sys.process.javaVmArguments.filter(
    a => Seq("-Xmx", "-Xms", "-XX").exists(a.startsWith)
  )

  def specs2(scope: String, name: String = "core") = Def.setting {
    val v = if (scalaBinaryVersion.value == "2.11") "3.2" else "3.1.1"
    "org.specs2" %% s"specs2-${name}" % v % scope
  }

  def play20(app: String, scope: String) = Def.setting {
    val v = if (scalaBinaryVersion.value == "2.11") "2.3.0" else "2.2.0"
    "com.typesafe.play" %% app % v % scope
  }

  val compilerSettings = Seq(
    javacOptions ++= Seq("-source", "1.6", "-target", "1.6"),
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-language:implicitConversions"),
    scalacOptions in Compile in doc ++= {
      val base = baseDirectory.value
      Seq("-sourcepath", base.getAbsolutePath, "-doc-source-url",
        "https://github.com/aselab/scala-activerecord/tree/master/%s€{FILE_PATH}.scala".format(base.getName),
        "-diagrams")
    },
    compileOrder in Compile := CompileOrder.JavaThenScala
  )

  val defaultResolvers = Seq(
    Resolver.sonatypeRepo("snapshots"),
    Classpaths.typesafeReleases,
    "Scalaz Bintray Repo" at "http://dl.bintray.com/scalaz/releases"
  )

  val defaultSettings = super.settings ++ Seq(
    version := (if (isRelease) _version else _version + "-SNAPSHOT"),
    organization := "com.github.aselab",
    scalaVersion := "2.11.7",
    crossScalaVersions := Seq("2.11.7", "2.10.5"),
    resolvers ++= defaultResolvers,
    libraryDependencies ++= Seq(
      specs2("test").value,
      specs2("test", "mock").value,
      "com.h2database" % "h2" % "1.4.185" % "test",
      "ch.qos.logback" % "logback-classic" % "1.1.2" % "test"
    ) ++ Option(System.getProperty("ci")).map(_ => specs2("test", "junit").value).toSeq,
    testOptions in Test ++= Option(System.getProperty("ci")).map(_ => Tests.Argument("junitxml", "console")).toSeq,
    parallelExecution in Test := false,
    fork in Test := true,
    testForkedParallel in Test := true,
    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (version.value.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    },
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    pomExtra := pomXml,
    shellPrompt := {
      (state: State) => Project.extract(state).currentProject.id + "> "
    },
    ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) },
    ivyLoggingLevel := UpdateLogging.DownloadOnly,
    javaOptions ++= originalJvmOptions
  ) ++ compilerSettings ++ org.scalastyle.sbt.ScalastylePlugin.projectSettings

  val pluginSettings = defaultSettings ++ ScriptedPlugin.scriptedSettings ++
    Seq(
      scalaVersion := "2.10.5",
      sbtPlugin := true,
      crossScalaVersions := Seq("2.10.5"),
      ScriptedPlugin.scriptedBufferLog := false,
      ScriptedPlugin.scriptedLaunchOpts := { ScriptedPlugin.scriptedLaunchOpts.value ++
        originalJvmOptions :+ s"-Dversion=${version.value}"
      },
      watchSources ++= ScriptedPlugin.sbtTestDirectory.value.***.get
    )

  lazy val root = project.in(file("."))
    .settings(defaultSettings: _*)
    .settings(publish := {}, publishLocal := {}, packagedArtifacts := Map.empty)
    .aggregate(macros, core, specs, play2, play2Specs, scalatra, generator, play2Sbt, scalatraSbt)

  lazy val core: Project = Project("core", file("activerecord"),
    settings = defaultSettings ++ Seq(
      name := "scala-activerecord",
      libraryDependencies ++= Seq(
        "org.squeryl" %% "squeryl" % "0.9.6-RC3",
        "com.typesafe" % "config" % "1.2.1",
        "com.jolbox" % "bonecp" % "0.8.0.RELEASE",
        "io.backchat.inflector" %% "scala-inflector" % "1.3.5",
        "com.github.nscala-time" %% "nscala-time" % "2.0.0",
        "commons-validator" % "commons-validator" % "1.4.1",
        "org.json4s" %% "json4s-native" % "3.2.11",
        "org.slf4j" % "slf4j-api" % "1.7.12"
      ),
      unmanagedSourceDirectories in Test += (scalaSource in Compile in specs).value,
      initialCommands in console in Test := """
      import com.github.aselab.activerecord._
      import com.github.aselab.activerecord.dsl._
      import models._
      TestTables.initialize(Map("schema" -> "com.github.aselab.activerecord.models.TestTables"))
      """
    )
  ) dependsOn(macros)

  lazy val macros = Project("macro", file("macro"),
    settings = defaultSettings ++ Seq(
      name := "scala-activerecord-macro",
      libraryDependencies := Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value % "compile",
        "org.scala-lang" % "scala-compiler" % scalaVersion.value % "optional"
      ),
      libraryDependencies := {
        CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, scalaMajor)) if scalaMajor >= 11 =>
            libraryDependencies.value
          case Some((2, 10)) =>
            libraryDependencies.value ++ Seq(
              compilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full),
              "org.scalamacros" %% "quasiquotes" % "2.0.1" cross CrossVersion.binary
            )
        }
      }
    )
  )

  lazy val specs = project.settings(defaultSettings:_*).settings(
    name := "scala-activerecord-specs",
    libraryDependencies += specs2("provided").value
  ).dependsOn(core)

  lazy val play2 = project.settings(defaultSettings:_*).settings(
    name := "scala-activerecord-play2",
    resolvers += "typesafe" at "http://repo.typesafe.com/typesafe/maven-releases/",
    libraryDependencies ++= List(
      play20("play", "provided").value,
      play20("play-jdbc", "provided").value
    )
  ).dependsOn(core)

  lazy val play2Specs = project.settings(defaultSettings:_*).settings(
    name := "scala-activerecord-play2-specs",
    resolvers += "typesafe" at "http://repo.typesafe.com/typesafe/maven-releases/",
    libraryDependencies ++= List(
      play20("play", "provided").value,
      play20("play-jdbc", "provided").value,
      play20("play-test", "provided").value,
      specs2("provided").value
    )
  ).dependsOn(play2, specs)

  lazy val scalatra = project.settings(defaultSettings:_*).settings(
    name := "scala-activerecord-scalatra",
    libraryDependencies ++= Seq(
      "org.scalatra" %% "scalatra" % "2.3.0" % "provided",
      "javax.servlet" % "javax.servlet-api" % "3.1.0" % "provided",
      "org.scala-lang" % "scala-compiler" % scalaVersion.value
    )
  ).dependsOn(core)

  lazy val generator = project.settings(pluginSettings:_*).settings(
    name := "scala-activerecord-generator",
    resolvers ++= defaultResolvers,
    addSbtPlugin("com.github.aselab" % "sbt-generator" % "0.1.0-SNAPSHOT"),
    libraryDependencies ++= Seq(
      "io.backchat.inflector" %% "scala-inflector" % "1.3.5"
    )
  )

  lazy val play2Sbt = project.settings(pluginSettings:_*).settings(
    name := "scala-activerecord-play2-sbt"
  ).dependsOn(generator)

  lazy val scalatraSbt = project.settings(pluginSettings:_*).settings(
    name := "scala-activerecord-scalatra-sbt"
  ).dependsOn(generator)

  val pomXml =
    <url>https://github.com/aselab/scala-activerecord</url>
    <licenses>
      <license>
        <name>MIT License</name>
        <url>http://www.opensource.org/licenses/mit-license.php</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <scm>
      <url>git@github.com:aselab/scala-activerecord.git</url>
      <connection>scm:git:git@github.com:aselab/scala-activerecord.git</connection>
    </scm>
    <developers>
      <developer>
        <id>a-ono</id>
        <name>Akihiro Ono</name>
        <url>https://github.com/a-ono</url>
      </developer>
      <developer>
        <id>y-yoshinoya</id>
        <name>Yuki Yoshinoya</name>
        <url>https://github.com/y-yoshinoya</url>
      </developer>
    </developers>
}
