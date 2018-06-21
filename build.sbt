name := "akka-okex-mmm"

version := "1.1.0"

scalaVersion := "2.12.6"

lazy val akkaHttpVersion = "10.1.1"
lazy val akkaVersion = "2.5.12"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http"            % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-xml"        % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-stream"          % akkaVersion,
  "com.typesafe.play" %% "play-json"            % "2.6.9",
  "com.typesafe.play" %% "play"                 % "2.6.13",
  "javax.inject" % "javax.inject" % "1",
  "com.neovisionaries" % "nv-websocket-client" % "2.4",
  "com.typesafe.play" %% "play-functional" % "2.6.9",
  "com.beachape.filemanagement" %% "schwatcher" % "0.3.2",
  "jp.co.bizreach" %% "aws-ses-scala" % "0.0.2",
  "com.typesafe.akka" %% "akka-http-testkit"    % akkaHttpVersion % Test,
  "com.typesafe.akka" %% "akka-testkit"         % akkaVersion     % Test,
  "com.typesafe.akka" %% "akka-stream-testkit"  % akkaVersion     % Test,
  "org.scalatest"     %% "scalatest"            % "3.0.1"         % Test
)

excludeDependencies += "commons-logging" % "commons-logging"

libraryDependencies += "com.typesafe.play" %% "play-json-joda" % "2.6.9"

libraryDependencies += "com.typesafe.play" %% "play-ahc-ws-standalone" % "2.0.0-M1"
libraryDependencies += "com.typesafe.play" %% "play-ws-standalone-json" % "2.0.0-M1"