organization := "pt.tecnico.dsi"
name := "ldap"
version := "0.5.0-SNAPSHOT"

githubOwner := "kryptt"
githubRepository := "ldap"

// =====================================================================================================================
// ==== Compile Options ================================================================================================
// =====================================================================================================================
javacOptions ++= Seq("-Xlint", "-encoding", "UTF-8", "-Dfile.encoding=utf-8")
scalaVersion := "3.2.2"
scalacOptions ++= Seq(
  "-encoding", "utf-8",                // Specify character encoding used by source files.
  "-language:higherKinds",             // Allow higher-kinded types
  "-language:implicitConversions",     // Explicitly enables the implicit conversions feature
  "-Xfatal-warnings",                  // Fail the compilation if there are any warnings.
  "-deprecation",                      // Emit warning and location for usages of deprecated APIs.
  "-unchecked",                        // Enable additional warnings where generated code depends on assumptions.
  "-feature",                          // Emit warning and location for usages of features that should be imported explicitly.
)
// These lines ensure that in sbt console or sbt test:console the -Ywarn* and the -Xfatal-warning are not bothersome.
// https://stackoverflow.com/questions/26940253/in-sbt-how-do-you-override-scalacoptions-for-console-in-all-configurations
scalacOptions in (Compile, console) ~= (_ filterNot { option =>
  option.startsWith("-Ywarn") || option == "-Xfatal-warnings" || option.startsWith("-Xlint")
})
scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value

// ======================================================================================================================
// ==== Dependencies ====================================================================================================
// ======================================================================================================================
val ldaptiveVersion = "2.1.1"
libraryDependencies ++= Seq(
  //Ldap
  "org.ldaptive" % "ldaptive" % ldaptiveVersion,
  "com.unboundid" % "unboundid-ldapsdk" % "6.0.7",
  //Logging
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
  "ch.qos.logback" % "logback-classic" % "1.4.5",
  //Testing
  "org.scalatest" %% "scalatest" % "3.2.15" % Test,
  //Configuration
  "com.typesafe" % "config" % "1.4.2"
)

// =====================================================================================================================
// ==== Scaladoc =======================================================================================================
// =====================================================================================================================
git.remoteRepo := s"git@github.com:ist-dsi/${name.value}.git"
git.useGitDescribe := true // Get version by calling `git describe` on the repository
val latestReleasedVersion = SettingKey[String]("latest released version")
latestReleasedVersion := git.gitDescribedVersion.value.getOrElse("0.0.1-SNAPSHOT")

// Define the base URL for the Scaladocs for your library. This will enable clients of your library to automatically
// link against the API documentation using autoAPIMappings.
apiURL := Some(url(s"${homepage.value.get}/api/${latestReleasedVersion.value}/"))
autoAPIMappings := true // Tell scaladoc to look for API documentation of managed dependencies in their metadata.
scalacOptions in (Compile, doc) ++= Seq(
  "-author",      // Include authors.
  "-diagrams",    // Create inheritance diagrams for classes, traits and packages.
  "-groups",      // Group similar functions together (based on the @group annotation)
  "-implicits",   // Document members inherited by implicit conversions.
  "-doc-title", name.value.capitalize,
  "-doc-version", latestReleasedVersion.value,
  "-doc-source-url", s"${homepage.value.get}/tree/v${latestReleasedVersion.value}€{FILE_PATH}.scala",
  "-sourcepath", (baseDirectory in ThisBuild).value.getAbsolutePath,
)

// =====================================================================================================================
// ==== Publishing/Release =============================================================================================
// =====================================================================================================================
publishTo := githubPublishTo.value

publishArtifact in Test := false

licenses += "MIT" -> url("http://opensource.org/licenses/MIT")
homepage := Some(url(s"https://github.com/kryptt/${name.value}"))
scmInfo := Some(ScmInfo(homepage.value.get, git.remoteRepo.value))
developers ++= List(
  Developer("magicknot", "David Duarte", "", url("https://github.com/magicknot")),
  Developer("Lasering", "Simão Martins", "", new URL("https://github.com/Lasering")),
)

// Will fail the build/release if updates for the dependencies are found
dependencyUpdatesFailBuild := true

releaseUseGlobalVersion := false

releasePublishArtifactsAction := PgpKeys.publishSigned.value
import ReleaseTransformations._
releaseProcess := Seq[ReleaseStep](
  releaseStepTask(dependencyUpdates),
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  releaseStepTask(Compile / doc),
  runTest,
  setReleaseVersion,
  tagRelease,
  publishArtifacts,
  setNextVersion,
  pushChanges,
)
