package com.gravitydev.scoop
package parsers

import ast.SqlType
import query.SelectExprS
import java.sql.ResultSet

object `package` {
  private [parsers] type RP[+T] = ResultSetParser[T]
  private [parsers] type SP[+T] = Selection[T]
 
  implicit def rightBiasParseResult [T](r: ParseResult[T]): Either.RightProjection[String,T] = r.right

  type ResultSetParser[+T] = ResultSet => ParseResult[T]
}

// Convenience to be able to flatMap a parser in the boiler plate code
private [scoop] trait ResultSetParserFlatMap [+T] {self: (ResultSet => ParseResult[T]) =>
  def flatMap [X] (fn: T => ResultSetParser[X]): ResultSetParser[X] = (rs: ResultSet) => (apply(rs).right flatMap (x => fn(x)(rs)))
}

final private [parsers] class ResultSetParserImpl[+T] (impl: ResultSet => ParseResult[T]) extends ResultSetParser[T] {
  @inline def apply (rs: ResultSet): ParseResult[T] = impl(rs)
}

private [scoop] class MappedSelection [T,+X] (parser: Selection[T], fn: T => X) extends Selection[X] {
  def apply (rs: ResultSet) = parser(rs).right map fn
  lazy val expressions = parser.expressions
  override def toString = "Selection(expressions=" + expressions + ", fn=" + util.fnToString(fn) + ")"
}

class ExprParser [+T:SqlType] (name: String) extends Selection1[T] (
  rs => SqlType[T].parse(rs, name) map {Right(_)} getOrElse Left("Could not parse expression: " + name + " [" + SqlType[T] + "] from " + util.inspectRS(rs))
)

// BOILERPLATE
abstract class SelectionX [+T] (selectors: Selection[_]*) extends SP[T] {
  def list: List[Selection[_]] = selectors.toList
  def expressions = list.foldLeft(List[query.SelectExprS]())((l,p) => l ++ p.expressions)
  override def toString = list.map((x: AnyRef) => x.toString).mkString(" ~ ")
}

class Selection1 [+A] (fn: ResultSet => ParseResult[A], val expressions: List[query.SelectExprS] = Nil) extends SP[A] {
  def >> [T](fn: A=>T) = new Selection1(apply(_) map fn, expressions)
  def ~ [X](px: SP[X]): Selection2[A,X] = new Selection2(this, px)
  def apply (rs: ResultSet): ParseResult[A] = fn(rs)
  override def toString = "Selection1(fn=" + fn + ", expressions=" + expressions + ")"
}
