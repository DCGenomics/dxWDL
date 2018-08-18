// Intermediate Representation (IR)
//
// Representation the compiler front end generates from a WDL
// workflow. The compiler back end uses it to generate a
// dx:workflow. A more detailed description can be found at
// ToplevelDir/[IntermediateForm.md].
//
// We use YAML as a human readable representation of the IR.
package dxWDL.compiler

import com.dnanexus.DXRecord
import dxWDL.{DeclAttrs, Utils, WdlPrettyPrinter}
import spray.json._
import wom.types.WomType
import wom.values._

object IR {
    // Compile time representation of a variable. Used also as
    // an applet argument. We keep track of the syntax-tree, for error
    // reporting purposes.
    //
    // The attributes are used to encode DNAx applet input/output
    // specification fields, such as {help, suggestions, patterns}.
    //
    case class CVar(name: String,
                    womType: WomType,
                    attrs: DeclAttrs) {
        // dx does not allow dots in variable names, so we
        // convert them to underscores.
        //
        // TODO: check for collisions that are created this way.
        def dxVarName : String = Utils.transformVarName(name)
    }

    /** Specification of instance type.
      *
      *  An instance could be:
      *  Default: the platform default, useful for auxiliary calculations.
      *  Const:   instance type is known at compile time. We can start the
      *           job directly on the correct instance type.
      *  Runtime: WDL specifies a calculation for the instance type, based
      *           on information known only at runtime. The generated applet
      *           will need to evalulate the expressions at runtime, and then
      *           start another job on the correct instance type.
      */
    sealed trait InstanceType
    case object InstanceTypeDefault extends InstanceType
    case class InstanceTypeConst(
        dxInstanceType: Option[String],
        memoryMB: Option[Int],
        diskGB: Option[Int],
        cpu: Option[Int]
    ) extends InstanceType
    case object InstanceTypeRuntime extends InstanceType

    // A task may specify a docker image to run under. There are three
    // supported options:
    //  None:    no image
    //  Network: the image resides on a network site and requires download
    //  DxAsset: the image is a platform asset
    //
    sealed trait DockerImage
    case object DockerImageNone extends DockerImage
    case object DockerImageNetwork extends DockerImage
    case class DockerImageDxAsset(asset: DXRecord) extends DockerImage

    // A unified type representing a WDL workflow or a WDL applet.
    // This is useful when compiling WDL workflows, because they can
    // call other WDL workflows and applets. This is done using the
    // same syntax.
    sealed trait Callable {
        def name: String
        def inputs: Vector[CVar]
        def outputs: Vector[CVar]
    }

    // There are several kinds of applets
    //   Native:     a native platform applet
    //   WfFragment: WDL workflow fragment, can included nested if/scatter blocks
    //   Task:       call a task, execute a shell command (usually)
    //   WorkflowOutputReorg: move intermediate result files to a subdirectory.
    sealed trait AppletKind
    case class  AppletKindNative(id: String) extends AppletKind
    case class  AppletKindWfFragment(calls: Map[String, String]) extends AppletKind
    case object AppletKindTask extends AppletKind

    /** @param name          Name of applet
      * @param inputs        input arguments
      * @param outputs       output arguments
      * @param instaceType   a platform instance name
      * @param docker        is docker used? if so, what image
      * @param kind          Kind of applet: task, scatter, ...
      * @param task          Task definition
      */
    case class Applet(name: String,
                      inputs: Vector[CVar],
                      outputs: Vector[CVar],
                      instanceType: InstanceType,
                      docker: DockerImage,
                      kind: AppletKind,
                      task: wom.callable.ExecutableTaskDefinition) extends Callable

    case class Bundle(primaryCallable: Option[Callable],
                      allCallables: Map[String, Callable])
}
