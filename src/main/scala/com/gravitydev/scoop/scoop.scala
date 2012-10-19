package com.gravitydev.scoop

import java.sql.{ResultSet, PreparedStatement, Types, Timestamp}

object `package` {
  type Table[T <: ast.SqlTable[T]] = ast.SqlTable[T]
  type TableCompanion[T <: Table[T]] = {def apply(s: String, pf: String): T}

  def opt [T](p: ResultSetParser[T]): ResultSetParser[Option[T]] = new ResultSetParser [Option[T]] {
    def columns = p.columns
    def apply (rs: ResultSet) = p(rs) match {
      case Success(s) => Success(Option(s))
      case Failure(e) => Success(None)
    }
  }

  implicit object int        extends SqlNativeType [Int]       (Types.INTEGER,   _ getInt _,       _ setInt (_,_))  
  implicit object long       extends SqlNativeType [Long]      (Types.BIGINT,    _ getLong _,      _ setLong (_,_))
  implicit object string     extends SqlNativeType [String]    (Types.VARCHAR,   _ getString _,    _ setString (_,_))
  implicit object timestamp  extends SqlNativeType [Timestamp] (Types.TIMESTAMP, _ getTimestamp _, _ setTimestamp (_,_))
  implicit object boolean    extends SqlNativeType [Boolean]   (Types.BOOLEAN,   _ getBoolean _,   _ setBoolean (_,_))
  
  implicit def toColumnParser [X](c: ast.SqlCol[X]) = new ColumnParser(c)
  implicit def toNullableColumnParser [X](c: ast.SqlNullableCol[X]) = new NullableColumnParser(c)
  implicit def toColumnWrapper [X](c: ast.SqlCol[X]) = ColumnWrapper(c)
  
  private[scoop] def renderParams (params: Seq[SqlSingleParam[_,_]]) = params.map(x => x.v + ":"+x.v.asInstanceOf[AnyRef].getClass.getName.stripPrefix("java.lang."))
}

private [scoop] sealed trait ParseResult[+T] {
  def map [X](fn: T => X): ParseResult[X] = this match {
    case Success(v) => Success(fn(v))
    case Failure(e) => Failure(e)
  }
  def flatMap [X](fn: T => ParseResult[X]): ParseResult[X] = this match {
    case Success(v) => fn(v)
    case Failure(e) => Failure(e)
  }
}
private [scoop] case class Success [T] (v: T) extends ParseResult[T]
private [scoop] case class Failure (error: String) extends ParseResult[Nothing]

trait SqlType [T] {self =>
  def tpe: Int // jdbc sql type
  def set (stmt: PreparedStatement, idx: Int, value: T): Unit
  def parse (rs: ResultSet, name: String): Option[T]
  def apply (n: String, sql: String = "") = new ExprParser (n, this)
}
  
abstract class SqlNativeType[T] (val tpe: Int, get: (ResultSet, String) => T, _set: (PreparedStatement, Int, T) => Unit) extends SqlType [T] {
  def set (stmt: PreparedStatement, idx: Int, value: T): Unit = _set(stmt, idx, value)
  def parse (rs: ResultSet, name: String) = Option(get(rs, name)) filter {_ => !rs.wasNull} 
}
abstract class SqlCustomType[T,N] (from: N => T, to: T => N)(implicit nt: SqlNativeType[N]) extends SqlType[T] {
  def tpe = nt.tpe
  def parse (rs: ResultSet, name: String) = nt.parse(rs, name) map from
  def set (stmt: PreparedStatement, idx: Int, value: T): Unit = nt.set(stmt, idx, to(value))
}

sealed trait SqlParam [T] {
  val v: T
}

case class SqlSingleParam [T,S] (v: T)(implicit val tp: SqlType[T]) extends SqlParam[T] {
  def apply (stmt: PreparedStatement, idx: Int) = tp.set(stmt, idx, v)
}
case class SqlSetParam [T](v: Set[T])(implicit tp: SqlType[T]) extends SqlParam[Set[T]] {
  def toList = v.toList.map(x => SqlSingleParam(x))
}

trait ResultSetParser[T] extends (ResultSet => ParseResult[T]) {self =>
  def map [X] (fn: T => X): ResultSetParser[X] = new ResultSetParser [X] {
    def apply (rs: ResultSet) = self(rs) map fn
    def columns = self.columns
  }
  def flatMap [X] (fn: T => ResultSetParser[X]): ResultSetParser[X] = new ResultSetParser [X] {
    def apply (rs: ResultSet) = for (x <- self(rs); y <- fn(x)(rs)) yield y
    def columns = self.columns 
  }
  
  def columns: List[query.ExprS]
}

/*
class ScoopParser [T] (
  parseFn: ResultSet => Option[T],
  name: String,
  prefix: String
) extends ResultSetParser[T] {
  def apply (rs: ResultSet) = parseFn(rs)
  def prefix (pf: String) = new ScoopParser[T](parseFn, name, pf)
}
*/

/*
case class ParserWrapper [T,X] (p: Parser[T], fn: Option[T] => Option[X]) extends Parser[X] {
  def name = "ParserWrapper(" + p.name + ")"
  def columns = p.columns
  def as (prefix: String = null) = ParserWrapper(p.as(prefix), fn)
  def parse (rs: ResultSet) = fn(p(rs))
}
*/

class ExprParser [T] (name: String, exp: SqlType[T]) 
    extends boilerplate.ParserBase[T] (exp.parse(_, name) map {Success(_)} getOrElse Failure("Could not parse expression: " + name)) {
  def prefix (pf: String) = new ExprParser (pf+name, exp)
  def columns = List(name)
}

class ColumnParser[T](column: ast.SqlCol[T]) 
    extends boilerplate.ParserBase[T] (rs => column parse rs map {Success(_)} getOrElse Failure("Could not parse column: " + column.name)) {
  def name = column.name
  def columns = List(column.selectSql)
}

class NullableColumnParser[T](column: ast.SqlNullableCol[T]) 
    extends boilerplate.ParserBase[Option[T]] (rs => column parse rs map {Success(_)} getOrElse Failure("Could not parse column: " + column.name)) {
  def name = column.name
  def columns = List(column.sql)
}

abstract class SqlOrder (val sql: String)
case object Ascending   extends SqlOrder ("ASC")
case object Descending  extends SqlOrder ("DESC")

case class SqlOrdering (col: ast.SqlCol[_], order: SqlOrder) {
  def sql = col.sql + " " + order.sql
}

case class ColumnWrapper [X](col: ast.SqlCol[X]) {
  def desc  = SqlOrdering(col, Descending)
  def asc   = SqlOrdering(col, Ascending)
}
