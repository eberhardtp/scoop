package com.gravitydev.scoop.sbt

import sbt._
import Keys._

object ScoopPlugin extends AutoPlugin {

  object autoImport {
    val scoopPackage = settingKey[String]("The package where the generated 'tables' object will be created in")
    val scoopJdbcUrl = settingKey[String]("The JDBC URL for the database to inspect.")
    val scoopJdbcUsername = settingKey[String]("The JDBC username for the the database to inspect.")
    val scoopJdbcPassword = settingKey[String]("The JDBC password for the database to inspect.")
    val scoopIncludeTables = settingKey[String=>Boolean]("Filter function to include tables.")
    val scoopIncludeColumns = settingKey[((String,String))=>Boolean]("Filter function to include columns.")
    val scoopMapType = settingKey[Int=>String]("Function that maps a java.sql.Types value to a scala type.")
    val scoopOverrideColumnType = settingKey[PartialFunction[(String,String),String]]("Override the type of a column given the table name and the column name.")
    val scoopFile = settingKey[File]("The file where the generated code is placed.")
  }

  import autoImport._

  val scoopGenerate = taskKey[Seq[java.io.File]]("Generate the Scoop metadata for the configured database.")

  private val scoopGenerateMaybe = taskKey[Seq[java.io.File]]("Generate scoop metadata if it hasn't been generated yet.")

  override lazy val projectSettings = Seq(
    sourceGenerators in Compile += {
      scoopGenerateMaybe.taskValue
    },
    scoopGenerateMaybe := Def.taskDyn {
      if (scoopFile.value.exists) Def.task{
        streams.value.log.info("Scoop meta-data already exists. Run 'clean' if you wish to re-generate from DB'")
        Seq(scoopFile.value)
      }
      else Def.task(scoopGenerate.value)
    }.value,
    scoopFile := (sourceManaged in Compile).value / "scoop" / "scoop.scala",
    scoopIncludeTables := (_ => true), // include all tables
    //scoopIncludeTables := (_ != "play_evolutions"), // include all tables
    scoopIncludeColumns := (_ => true),

    scoopMapType := {(i: Int) => 
      import java.sql.Types._
      i match {
        case TINYINT|SMALLINT => "Int"
        case CHAR|VARCHAR|LONGVARCHAR => "String"
        case INTEGER|BIGINT => "Long"
        case TIMESTAMP      => "java.sql.Timestamp"
        case DATE           => "java.sql.Date"
        case BIT|BOOLEAN    => "Boolean"
        case DECIMAL        => "BigDecimal"
        case JAVA_OBJECT    => "AnyRef"
        case x              => "Unknown: " + x
      }
    },

    scoopOverrideColumnType := PartialFunction.empty,
    //scoopOverrideColumnType := {case ("equipment", "created_date") => "org.joda.time.DateTime"},

    scoopGenerate := {
      streams.value.log.info("Generating scoop data definition.")
      generateScoop(
        scoopFile.value, 
        scoopPackage.value,
        scoopJdbcUrl.value, 
        scoopJdbcUsername.value, 
        scoopJdbcPassword.value,
        scoopIncludeTables.value,
        scoopIncludeColumns.value,
        scoopMapType.value,
        scoopOverrideColumnType.value
      )
    }

  )

  private def generateScoop (
    file: java.io.File,
    pkg: String,
    jdbcUrl: String, 
    jdbcUsername: String, 
    jdbcPassword: String, 
    includeTables: String=>Boolean,
    includeColumns: ((String, String)) =>Boolean,
    mapType: Int => String,
    overrideColumnType: PartialFunction[(String,String),String]
  ): Seq[java.io.File] = {
    import java.sql.{Connection, DriverManager}
    import com.gravitydev.scoop._, query._
    import scala.collection.mutable.ListBuffer

    Class.forName("com.mysql.jdbc.Driver").newInstance()
    val con = DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword)

    val md = con.getMetaData

    val rs = md.getColumns(null, null, "%", null)
    val lb = new ListBuffer[(String,(String,Int,Int))]()
    while (rs.next) {
      lb.append( 
        (
          sqlString.parse(rs, 3).get, 
          (sqlString.parse(rs, 4).get, sqlInt.parse(rs, 5).get, sqlInt.parse(rs, 11).get)
        ) 
      )
    }
    rs.close()
    con.close()

    val tables = lb.toList groupBy (_._1) filter (x => includeTables(x._1)) map {
      case (table,data) => table -> data.map(_._2)
    }

    val content = 
      s"package ${pkg}\n\n" +
      s"import scala.language.postfixOps\n" +
      s"import com.gravitydev.scoop._\n\n" +
      s"object tables {\n\n" + 
        (for ((t, cols) <- tables) yield {
          s"  case class `$t`() extends Table[`$t`](`$t`) {\n" +
          (for ((col,tpe,nullable) <- cols if includeColumns(t, col)) yield {
            s"    val `${col}`".padTo(35, " ").mkString + 
            s" = col[${overrideColumnType.applyOrElse((t,col), (x: (String,String)) => mapType(tpe))}] ".padTo(30, " ").mkString +
            s"""(Symbol("$col")) """.padTo(30, " ").mkString + 
            (if (nullable == 1) "nullable" else "") + "\n"
          }).mkString("") +
          s"  }\n\n"
        }).mkString("", "", "\n\n") +
      s"}\n"

    IO.write(file, content)

    Seq(file)
  }
}

