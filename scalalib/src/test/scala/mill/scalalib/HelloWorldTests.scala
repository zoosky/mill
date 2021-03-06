package mill.scalalib

import java.util.jar.JarFile

import ammonite.ops._
import ammonite.ops.ImplicitWd._
import mill._
import mill.define.{Cross, Target}
import mill.discover.Discovered
import mill.eval.{Evaluator, Result}
import mill.scalalib.publish._
import mill.util.TestEvaluator
import sbt.internal.inc.CompileFailed
import utest._

import scala.collection.JavaConverters._

trait HelloWorldModule extends scalalib.Module {
  def scalaVersion = "2.12.4"
  def basePath = HelloWorldTests.workingSrcPath
}

object HelloWorld extends HelloWorldModule
object CrossHelloWorld extends mill.Module{
  val cross =
    for(v <- Cross("2.10.6", "2.11.11", "2.12.3", "2.12.4"))
    yield new HelloWorldModule {
      def scalaVersion = v
    }
}

object HelloWorldWithMain extends HelloWorldModule {
  def mainClass = Some("Main")
}

object HelloWorldWarnUnused extends HelloWorldModule {
  def scalacOptions = T(Seq("-Ywarn-unused"))
}

object HelloWorldFatalWarnings extends HelloWorldModule {
  def scalacOptions = T(Seq("-Ywarn-unused", "-Xfatal-warnings"))
}

object HelloWorldWithPublish extends HelloWorldModule with PublishModule {
  def artifactName = "hello-world"
  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    organization = "com.lihaoyi",
    description = "hello world ready for real world publishing",
    url = "https://github.com/lihaoyi/hello-world-publish",
    licenses = Seq(
      License("Apache License, Version 2.0",
              "http://www.apache.org/licenses/LICENSE-2.0")),
    scm = SCM(
      "https://github.com/lihaoyi/hello-world-publish",
      "scm:git:https://github.com/lihaoyi/hello-world-publish"
    ),
    developers =
      Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
  )
}
object HelloWorldScalaOverride extends HelloWorldModule {
  override def scalaVersion: Target[String] = "2.11.11"
}
object HelloWorldTests extends TestSuite {

  val srcPath = pwd / 'scalalib / 'src / 'test / 'resource / "hello-world"
  val basePath = pwd / 'target / 'workspace / "hello-world"
  val workingSrcPath = basePath / 'src
  val outPath = basePath / 'out
  val mainObject = workingSrcPath / 'src / 'main / 'scala / "Main.scala"




  val helloWorldEvaluator = new TestEvaluator(
    Discovered.mapping(HelloWorld),
    outPath,
    workingSrcPath
  )
  val helloWorldWithMainEvaluator = new TestEvaluator(
    Discovered.mapping(HelloWorldWithMain),
    outPath,
    workingSrcPath
  )
  val helloWorldFatalEvaluator = new TestEvaluator(
    Discovered.mapping(HelloWorldFatalWarnings),
    outPath,
    workingSrcPath
  )
  val helloWorldOverrideEvaluator = new TestEvaluator(
    Discovered.mapping(HelloWorldScalaOverride),
    outPath,
    workingSrcPath
  )
  val helloWorldCrossEvaluator = new TestEvaluator(
    Discovered.mapping(CrossHelloWorld),
    outPath,
    workingSrcPath
  )


  def tests: Tests = Tests {
    prepareWorkspace()
    'scalaVersion - {
      'fromBuild - {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloWorld.scalaVersion)

        assert(
          result == "2.12.4",
          evalCount > 0
        )
      }
      'override - {
        val Right((result, evalCount)) = helloWorldOverrideEvaluator(HelloWorldScalaOverride.scalaVersion)

        assert(
          result == "2.11.11",
          evalCount > 0
        )
      }
    }
    'scalacOptions - {
      'emptyByDefault - {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloWorld.scalacOptions)

        assert(
          result.isEmpty,
          evalCount > 0
        )
      }
      'override - {
        val Right((result, evalCount)) = helloWorldFatalEvaluator(HelloWorldFatalWarnings.scalacOptions)

        assert(
          result == Seq("-Ywarn-unused", "-Xfatal-warnings"),
          evalCount > 0
        )
      }
    }
    'compile - {
      'fromScratch - {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloWorld.compile)

        val analysisFile = result.analysisFile
        val outputFiles = ls.rec(result.classes.path)
        val expectedClassfiles = compileClassfiles.map(outPath / 'compile / 'dest / 'classes / _)
        assert(
          result.classes.path == outPath / 'compile / 'dest / 'classes,
          exists(analysisFile),
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) = helloWorldEvaluator(HelloWorld.compile)
        assert(unchangedEvalCount == 0)
      }
      'recompileOnChange - {
        val Right((_, freshCount)) = helloWorldEvaluator(HelloWorld.compile)
        assert(freshCount > 0)

        write.append(mainObject, "\n")

        val Right((_, incCompileCount)) = helloWorldEvaluator(HelloWorld.compile)
        assert(incCompileCount > 0, incCompileCount < freshCount)
      }
      'failOnError - {
        write.append(mainObject, "val x: ")

        val Left(Result.Exception(err, _)) = helloWorldEvaluator(HelloWorld.compile)

        assert(err.isInstanceOf[CompileFailed])

        val paths = Evaluator.resolveDestPaths(
          outPath,
          helloWorldEvaluator.evaluator.mapping.targetsToSegments(HelloWorld.compile)
        )

        assert(
          ls.rec(paths.dest / 'classes).isEmpty,
          !exists(paths.meta)
        )
        // Works when fixed
        write.over(mainObject, read(mainObject).dropRight("val x: ".length))

        val Right((result, evalCount)) = helloWorldEvaluator(HelloWorld.compile)
      }
      'passScalacOptions - {
        // compilation fails because of "-Xfatal-warnings" flag
        val Left(Result.Exception(err, _)) = helloWorldFatalEvaluator(HelloWorldFatalWarnings.compile)

        assert(err.isInstanceOf[CompileFailed])
      }
    }
    'runMain - {
      'runMainObject - {
        val runResult = basePath / 'out / 'runMain / 'dest / "hello-mill"

        val Right((_, evalCount)) = helloWorldEvaluator(HelloWorld.runMain("Main", runResult.toString))
        assert(evalCount > 0)

        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'runCross{
        def cross(v: String) {

          val runResult = basePath / 'out / 'cross / v / 'runMain / 'dest / "hello-mill"

          val Right((_, evalCount)) = helloWorldCrossEvaluator(
            CrossHelloWorld.cross(v).runMain("Main", runResult.toString)
          )

          assert(evalCount > 0)


          assert(
            exists(runResult),
            read(runResult) == "hello rockjam, your age is: 25"
          )
        }
        'v210 - cross("2.10.6")
        'v211 - cross("2.11.11")
        'v2123 - cross("2.12.3")
        'v2124 - cross("2.12.4")
      }


      'notRunInvalidMainObject - {
        val Left(Result.Exception(err, _)) = helloWorldEvaluator(HelloWorld.runMain("Invalid"))

        assert(
          err.isInstanceOf[InteractiveShelloutException]
        )
      }
      'notRunWhenComplileFailed - {
        write.append(mainObject, "val x: ")

        val Left(Result.Exception(err, _)) = helloWorldEvaluator(HelloWorld.runMain("Main"))

        assert(
          err.isInstanceOf[CompileFailed]
        )
      }
    }
    'run - {
      'runIfMainClassProvided - {
        val runResult = basePath / 'out / 'run / 'dest / "hello-mill"
        val Right((_, evalCount)) = helloWorldWithMainEvaluator(
          HelloWorldWithMain.run(runResult.toString)
        )

        assert(evalCount > 0)


        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'notRunWithoutMainClass - {
        val Left(Result.Exception(err, _)) = helloWorldEvaluator(HelloWorld.run())

        assert(
          err.isInstanceOf[RuntimeException]
        )
      }
    }
    'jar - {
      'nonEmpty - {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloWorld.jar)

        assert(
          exists(result.path),
          evalCount > 0
        )

        val entries = new JarFile(result.path.toIO).entries().asScala.map(_.getName).toSet

        val manifestFiles = Seq[RelPath](
          "META-INF" / "MANIFEST.MF"
        )
        val expectedFiles = compileClassfiles ++ manifestFiles

        assert(
          entries.nonEmpty,
          entries == expectedFiles.map(_.toString()).toSet
        )
      }
      'runJar - {
        val Right((result, evalCount)) = helloWorldWithMainEvaluator(HelloWorldWithMain.jar)

        assert(
          exists(result.path),
          evalCount > 0
        )
        val runResult = basePath / "hello-mill"

        %("scala", result.path, runResult)(wd = basePath)


        assert(
          exists(runResult),
          read(runResult) == "hello rockjam, your age is: 25"
        )
      }
      'logOutputToFile {
        helloWorldEvaluator(HelloWorld.compile)

        val logFile = outPath / 'compile / 'log
        assert(exists(logFile))
      }
    }
  }

  def compileClassfiles = Seq[RelPath](
    "Main.class",
    "Main$.class",
    "Main$delayedInit$body.class",
    "Person.class",
    "Person$.class"
  )

  def prepareWorkspace(): Unit = {
    rm(outPath)
    rm(workingSrcPath)
    mkdir(outPath)
    cp(srcPath, workingSrcPath)
  }

}
