package hbt

import java.io.FileOutputStream

import collection.JavaConverters._
import java.nio.{file => jnio}
import java.util.jar.JarEntry

import sourcecode.Enclosing

import scala.collection.mutable
class Args(val args: IndexedSeq[_]){
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
sealed trait Target[T]{
  val label: String
  def evaluate(args: Args): T
  val inputs: Seq[Target[_]]

  def map[V](f: T => V)(implicit path: Enclosing) = {
    Target.Mapped(this, f, path.value)
  }
  def zip[V](other: Target[V])(implicit path: Enclosing) = {
    Target.Zipped(this, other, path.value)
  }
  def ~[V, R](other: Target[V])
             (implicit s: Implicits.Sequencer[T, V, R]): Target[R] = {
    this.zip(other).map(s.apply _ tupled)
  }

}

object Target{
  def traverse[T](source: Seq[Target[T]])(implicit path: Enclosing) = {
    Traverse(source, path.value)
  }
  case class Traverse[T](inputs: Seq[Target[T]], label: String) extends Target[Seq[T]]{
    def evaluate(args: Args) = {
      for (i <- 0 until args.length)
      yield args(i)
    }

  }
  case class Mapped[T, V](source: Target[T], f: T => V,
                          label: String) extends Target[V]{
    def evaluate(args: Args) = f(args(0))
    val inputs = List(source)
  }
  case class Zipped[T, V](source1: Target[T],
                          source2: Target[V],
                          label: String) extends Target[(T, V)]{
    def evaluate(args: Args) = (args(0), args(0))
    val inputs = List(source1, source1)
  }
  case class Path(path: jnio.Path, label: String) extends Target[jnio.Path]{
    def evaluate(args: Args) = path
    val inputs = Nil
  }
//  case class Command(inputs: Seq[Target[jnio.Path]],
//                     output: Seq[Target[jnio.Path]],
//                     label: String) extends Target[Command.Result]
//  object Command{
//    case class Result(stdout: String,
//                      stderr: String,
//                      writtenFiles: Seq[jnio.Path])
//  }
}
object Hbt{


  def evaluateTargetGraph[T](t: Target[T]): T = {
    val targetSet = mutable.Set.empty[Target[_]]
    def rec(t: Target[_]): Unit = {
      if (targetSet.contains(t)) () // do nothing
      else {
        targetSet.add(t)
        t.inputs.foreach(rec)
      }
    }
    rec(t)
    val targets = targetSet.toIndexedSeq
    val targetIndices = targets.zipWithIndex.toMap

    val numberedEdges =
      for(i <- targets.indices)
      yield targets(i).inputs.map(targetIndices)

    val sortedClusters = Tarjans(numberedEdges)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)

    val results = mutable.Map.empty[Target[_], Any]
    for (cluster <- sortedClusters){
      val Seq(singletonIndex) = cluster
      val singleton = targets(singletonIndex)
      val inputResults = singleton.inputs.map(results)
      results(singleton) = singleton.evaluate(new Args(inputResults.toIndexedSeq))
    }
    results(t).asInstanceOf[T]
  }
}