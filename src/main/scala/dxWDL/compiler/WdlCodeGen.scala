package dxWDL.compiler

import scala.util.matching.Regex
import wom.types._
import wom.values._

import dxWDL.base._
import dxWDL.base.WomTypeSerialization.typeName
import dxWDL.util._

// A bunch of WDL source lines
case class WdlCodeSnippet(value: String)

case class WdlCodeGen(verbose: Verbose,
                      typeAliases: Map[String, WomType],
                      language: Language.Value) {

  // A self contained WDL workflow
  def versionString(): String = {
    language match {
      case Language.WDLvDraft2 => ""
      case Language.WDLv1_0    => "version 1.0"
      case Language.WDLv2_0    => "version development"
      case other =>
        throw new Exception(s"Unsupported language version ${other}")
    }
  }

  def genDefaultValueOfType(wdlType: WomType): WomValue = {
    wdlType match {
      case WomBooleanType    => WomBoolean(true)
      case WomIntegerType    => WomInteger(0)
      case WomFloatType      => WomFloat(0.0)
      case WomStringType     => WomString("")
      case WomSingleFileType => WomSingleFile("dummy.txt")

      // We could convert an optional to a null value, but that causes
      // problems for the pretty printer.
      // WomOptionalValue(wdlType, None)
      case WomOptionalType(t) => genDefaultValueOfType(t)

      // The WomMap type HAS to appear before the array types, because
      // otherwise it is coerced into an array. The map has to
      // contain at least one key-value pair, otherwise you get a type error.
      case WomMapType(keyType, valueType) =>
        val k = genDefaultValueOfType(keyType)
        val v = genDefaultValueOfType(valueType)
        WomMap(WomMapType(keyType, valueType), Map(k -> v))

      // an empty array
      case WomMaybeEmptyArrayType(t) =>
        WomArray(WomMaybeEmptyArrayType(t), List())

      // Non empty array
      case WomNonEmptyArrayType(t) =>
        WomArray(WomNonEmptyArrayType(t), List(genDefaultValueOfType(t)))

      case WomPairType(lType, rType) =>
        WomPair(genDefaultValueOfType(lType), genDefaultValueOfType(rType))

      case WomCompositeType(typeMap, structName) =>
        val m = typeMap.map {
          case (fieldName, t) =>
            fieldName -> genDefaultValueOfType(t)
        }.toMap
        WomObject(m, WomObjectType)

      case _ => throw new Exception(s"Unhandled type ${wdlType}")
    }
  }

  /*
Create a header for a task/workflow. This is an empty task
that includes the input and output definitions. It is used
to
(1) allow linking to native DNAx applets (and workflows in the future).
(2) make a WDL file stand-alone, without imports

For example, the stub for the Add task:
task Add {
    input {
      Int a
      Int b
    }
    command {
    command <<<
        python -c "print(${a} + ${b})"
    >>>
    output {
        Int result = read_int(stdout())
    }
}

is:
task Add {
   input {
     Int a
     Int b
   }
   command {}
   output {
       Int result
   }
  }
   */
  private def genTaskHeader(callable: IR.Callable): WdlCodeSnippet = {
    /*Utils.trace(verbose.on,
                    s"""|taskHeader  callable=${callable.name}
                        |  inputs= ${callable.inputVars.map(_.name)}
                        |  outputs= ${callable.outputVars.map(_.name)}"""
                        .stripMargin)*/

    // Sort the inputs by name, so the result will be deterministic.
    val inputs =
      callable.inputVars
        .sortWith(_.name < _.name)
        .map {
          case cVar =>
            cVar.default match {
              case None =>
                s"    ${typeName(cVar.womType)} ${cVar.name}"
              case Some(womValue) =>
                s"    ${typeName(cVar.womType)} ${cVar.name} = ${womValue.toWomString}"
            }
        }
        .mkString("\n")

    val outputs =
      callable.outputVars
        .sortWith(_.name < _.name)
        .map {
          case cVar =>
            val defaultVal = genDefaultValueOfType(cVar.womType)
            s"    ${typeName(cVar.womType)} ${cVar.name} = ${defaultVal.toWomString}"
        }
        .mkString("\n")

    language match {
      case Language.WDLvDraft2 =>
        // Draft-2 does not support the input block.
        WdlCodeSnippet(
            s"""|task ${callable.name} {
                |${inputs}
                |
                |  command {}
                |  output {
                |${outputs}
                |  }
                |}""".stripMargin
        )
      case Language.WDLv1_0 | Language.WDLv2_0 =>
        WdlCodeSnippet(
            s"""|task ${callable.name} {
                |  input {
                |${inputs}
                |  }
                |  command {}
                |  output {
                |${outputs}
                |  }
                |}""".stripMargin
        )
      case other =>
        throw new Exception(s"Unsupported language version ${other}")
    }
  }

  def genDnanexusAppletStub(id: String,
                            appletName: String,
                            inputSpec: Map[String, WomType],
                            outputSpec: Map[String, WomType]): WdlCodeSnippet = {
    val inputs = inputSpec
      .map {
        case (name, womType) =>
          s"    ${typeName(womType)} ${name}"
      }
      .mkString("\n")
    val outputs = outputSpec
      .map {
        case (name, womType) =>
          val defaultVal = genDefaultValueOfType(womType)
          s"    ${typeName(womType)} $name = ${defaultVal.toWomString}"
      }
      .mkString("\n")

    val metaSection =
      s"""|  meta {
          |     type : "native"
          |     id : "${id}"
          |  }""".stripMargin

    val taskSourceCode = language match {
      case Language.WDLvDraft2 =>
        // Draft-2 does not support the input block.
        s"""|task ${appletName} {
            |${inputs}
            |
            |  command {}
            |  output {
            |${outputs}
            |  }
            |${metaSection}
            |}""".stripMargin
      case Language.WDLv1_0 | Language.WDLv2_0 =>
        s"""|task ${appletName} {
            |  input {
            |${inputs}
            |  }
            |  command {}
            |  output {
            |${outputs}
            |  }
            |${metaSection}
            |}""".stripMargin

      case other =>
        throw new Exception(s"Unsupported language version ${other}")
    }

    // Make sure this is actually valid WDL
    //
    // We need to add the version to this task, to
    // make it valid WDL
    val taskStandalone = s"""|${versionString()}
                             |
                             |${taskSourceCode}
                             |""".stripMargin
    ParseWomSourceFile(false).validateWdlCode(taskStandalone, language)

    WdlCodeSnippet(taskSourceCode)
  }

  // A workflow can import other libraries:
  //
  // import "library.wdl" as lib
  // workflow foo {
  //   call lib.Multiply as mul { ... }
  //   call lib.Add { ... }
  //   call lib.Nice as nice { ... }
  //   call lib.Hello
  // }
  //
  // rewrite the workflow, and remove the calls to external libraries.
  //
  // workflow foo {
  //   call Multiply as mul { ... }
  //   call Add { ... }
  //   call Nice as nice { ... }
  //   call Nice as nice { ... }
  //   call Hello
  // }
  //
  private val callLibrary: Regex = "^(\\s*)call(\\s+)(\\w+)\\.(\\w+)(\\s+)(.+)".r
  private val callLibraryNoArgs: Regex = "^(\\s*)call(\\s+)(\\w+)\\.(\\w+)(\\s*)".r
  private def flattenWorkflow(wdlWfSource: String): String = {
    val originalLines = wdlWfSource.split("\n").toList
    val cleanLines = originalLines.map { line =>
      val allMatches = callLibrary.findAllMatchIn(line).toList
      assert(allMatches.size <= 1)
      val newLine =
        if (allMatches.isEmpty) {
          line
        } else {
          val m = allMatches(0)
          val callee: String = m.group(4)
          val rest = m.group(6)
          s"call ${callee} ${rest}"
        }

      // call with no arguments
      val allMatches2 = callLibraryNoArgs.findAllMatchIn(newLine).toList
      assert(allMatches2.size <= 1)
      if (allMatches2.isEmpty) {
        newLine
      } else {
        val m = allMatches2(0)
        val callee: String = m.group(4)
        s"call ${callee}"
      }
    }
    cleanLines.mkString("\n")
  }

  // Write valid WDL code that defines the type aliases we have.
  private def typeAliasDefinitions: String = {
    val sortedTypeAliases = SortTypeAliases(verbose).apply(typeAliases.toVector)
    val snippetVec = sortedTypeAliases.map {
      case (name, WomCompositeType(typeMap, _)) =>
        val fieldLines = typeMap
          .map {
            case (fieldName, womType) =>
              s"    ${typeName(womType)} ${fieldName}"
          }
          .mkString("\n")

        s"""|struct ${name} {
            |${fieldLines}
            |}""".stripMargin

      case (name, other) =>
        throw new Exception(s"Unknown type alias ${name} ${other}")
    }
    snippetVec.mkString("\n")
  }

  def standAloneTask(originalTaskSource: String): WdlCodeSnippet = {
    val wdlWfSource =
      List(versionString() + "\n",
           "# struct definitions",
           typeAliasDefinitions,
           "# Task",
           originalTaskSource).mkString("\n")

    // Make sure this is actually valid WDL
    ParseWomSourceFile(false).validateWdlCode(wdlWfSource, language)

    WdlCodeSnippet(wdlWfSource)
  }

  // A workflow must have definitions for all the tasks it
  // calls. However, a scatter calls tasks, that are missing from
  // the WDL file we generate. To ameliorate this, we add stubs for
  // called tasks. The generated tasks are named by their
  // unqualified names, not their fully-qualified names. This works
  // because the WDL workflow must be "flattenable".
  def standAloneWorkflow(originalWorkflowSource: String,
                         allCalls: Vector[IR.Callable]): WdlCodeSnippet = {
    val taskStubs: Map[String, WdlCodeSnippet] =
      allCalls.foldLeft(Map.empty[String, WdlCodeSnippet]) {
        case (accu, callable) =>
          if (accu contains callable.name) {
            // we have already created a stub for this call
            accu
          } else {
            val sourceCode = callable match {
              case IR.Applet(_, _, _, _, _, IR.AppletKindTask(_), taskSourceCode, _, _) =>
                // This is a task, include its source code, instead of a header.
                val taskDir = ParseWomSourceFile(false).scanForTasks(taskSourceCode)
                assert(taskDir.size == 1)
                val taskBody = taskDir.values.head
                WdlCodeSnippet(taskBody)

              case _ =>
                // no existing stub, create it
                genTaskHeader(callable)
            }
            accu + (callable.name -> sourceCode)
          }
      }

    // sort the task order by name, so the generated code will be deterministic
    val tasksStr = taskStubs.toVector
      .sortWith(_._1 < _._1)
      .map { case (name, wdlCode) => wdlCode.value }
      .mkString("\n\n")
    val wfWithoutImportCalls = flattenWorkflow(originalWorkflowSource)
    val wdlWfSource =
      List(versionString() + "\n",
           "# struct definitions",
           typeAliasDefinitions,
           "# Task headers",
           tasksStr,
           "# Workflow with imports made local",
           wfWithoutImportCalls).mkString("\n")

    // Make sure this is actually valid WDL
    ParseWomSourceFile(false).validateWdlCode(wdlWfSource, language)

    WdlCodeSnippet(wdlWfSource)
  }
}
