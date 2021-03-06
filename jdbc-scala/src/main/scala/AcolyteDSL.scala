// -*- mode: scala -*-
package acolyte.jdbc

import java.util.{ List ⇒ JList }
import java.util.regex.Pattern
import java.sql.{ Connection ⇒ SqlConnection, SQLException }

import scala.language.implicitConversions
import scala.collection.JavaConverters._

import acolyte.jdbc.StatementHandler.Parameter
import acolyte.jdbc.AbstractCompositeHandler.{ QueryHandler, UpdateHandler }
import acolyte.jdbc.RowList.{ Column ⇒ Col }

/**
 * Acolyte DSL for JDBC.
 *
 * {{{
 * import acolyte.jdbc.AcolyteDSL.{ connection, handleStatement }
 * import acolyte.jdbc.Implicits._
 *
 * connection {
 *   handleStatement.withQueryDetection("...").
 *     withQueryHandler({ e: QueryExecution => ... }).
 *     withUpdateHandler({ e: UpdateExecution => ... })
 * }
 * }}}
 */
object AcolyteDSL {

  /**
   * Creates a connection, whose statement will be passed to given handler.
   *
   * @param h statement handler
   * @param p connection properties
   * @return a new Acolyte connection
   *
   * {{{
   * connection(handler) // without connection properties
   *
   * // With connection property to fallback untyped null
   * connection(handler, "acolyte.parameter.untypedNull" -> "true")
   * }}}
   */
  def connection(h: AbstractCompositeHandler[_], p: (String, String)*) =
    Driver.connection(h, p.foldLeft(new java.util.Properties()) { (ps, t) ⇒
      ps.put(t._1, t._2); ps
    })

  /**
   * Creates a connection, managed with given handler.
   *
   * @param h connection handler
   * @param p connection properties
   * @return a new Acolyte connection
   *
   * {{{
   * connection(handler) // without connection properties
   *
   * // With connection property to fallback untyped null
   * connection(handler, "acolyte.parameter.untypedNull" -> "true")
   * }}}
   */
  def connection(h: ConnectionHandler, p: (String, String)*) =
    Driver.connection(h, p.foldLeft(new java.util.Properties()) { (ps, t) ⇒
      ps.put(t._1, t._2); ps
    })

  /**
   * Creates an empty handler.
   *
   * {{{
   * import acolyte.jdbc.AcolyteDSL.{ connection, handleStatement }
   *
   * connection { handleStatement }
   * }}}
   */
  def handleStatement = ScalaCompositeHandler.empty

  /**
   * Creates a new handler detecting all statements as queries
   * (like `handleStatement.withQueryDetection(".*").withQueryHandler(h)`).
   *
   * {{{
   * import acolyte.jdbc.AcolyteDSL.{ connection, handleQuery }
   *
   * connection { handleQuery { _ => res }
   * }}}
   */
  def handleQuery(h: QueryExecution ⇒ QueryResult): ScalaCompositeHandler =
    handleStatement withQueryDetection ".*" withQueryHandler h

  /**
   * Executes `f` using a connection accepting only queries,
   * and answering with `result` to any query.
   *
   * {{{
   * import acolyte.jdbc.AcolyteDSL.withQueryResult
   *
   * val str: String = withQueryResult(queryRes) { con => "str" }
   * }}}
   */
  def withQueryResult[A](res: QueryResult)(f: SqlConnection ⇒ A): A =
    f(connection(handleQuery(_ ⇒ res)))

  /**
   * Returns an update result with row `count` and generated `keys`.
   * @param count Updated (row) count
   * @param keys Generated keys
   *
   * {{{
   * import acolyte.jdbc.AcolyteDSL.updateResult
   * import acolyte.jdbc.RowLists
   *
   * updateResult(2/* updated rows */, RowLists.stringList("a", "b"))
   * }}}
   */
  def updateResult(count: Int, keys: RowList[_]): UpdateResult =
    new UpdateResult(count) withGeneratedKeys keys

  /**
   * Manages a scope to debug any JDBC execution
   *
   * @param printer the operation to print any [[QueryExecution]] that occurs within the scope of debuging.
   * @param f the function working with the debug connection.
   *
   * {{{
   * import acolyte.jdbc.AcolyteDSL
   *
   * AcolyteDSL.debuging() { con =>
   *   val stmt = con.prepareStatement("SELECT * FROM Test WHERE id = ?")
   *   stmt.setString(1, "foo")
   *   stmt.executeQuery()
   * }
   * // print on stdout:
   * "Executed query: QueryExecution(SELECT * FROM Test WHERE id = ?,List(Param(foo, VARCHAR)))"
   * }}}
   */
  def debuging[A](printer: QueryExecution ⇒ Unit = { x ⇒ println(s"Executed query: $x") })(f: SqlConnection ⇒ A): Unit = {
    implicit val con = connection(handleQuery { x ⇒
      printer(x)
      throw DebugException
    })

    try {
      f(con)
      ()
    } catch {
      case e: SQLException ⇒ e.getCause match {
        case DebugException ⇒ ()
        case sqlError       ⇒ throw sqlError
      }
    } finally {
      con.close()
    }
  }

  private case object DebugException
    extends Exception with scala.util.control.NoStackTrace
}

/**
 * Acolyte implicit conversions for Scala use.
 *
 * {{{
 * import acolyte.jdbc.Implicits._
 * }}}
 */
object Implicits extends ScalaRowLists with CompositeHandlerImplicits {

  /**
   * Converts tuple to column definition.
   *
   * {{{
   * import acolyte.jdbc.Implicits.PairAsColumn
   *
   * rowList1(classOf[Int] -> "name") // rowList(new Column(...))
   * }}}
   */
  implicit def PairAsColumn[T](c: (Class[T], String)): Column[T] =
    Col(c._1, c._2)

}

final class ScalaCompositeHandler(qd: Array[Pattern], qh: QueryHandler, uh: UpdateHandler) extends AbstractCompositeHandler[ScalaCompositeHandler](qd, qh, uh) {

  /**
   * Returns handler that detects statement matching given pattern(s)
   * as query.
   *
   * {{{
   * import acolyte.jdbc.AcolyteDSL.handleStatement
   *
   * // Created handle will detect as query statements
   * // either starting with 'SELECT ' or containing 'EXEC proc'.
   * handleStatement.withQueryDetection("^SELECT ", "EXEC proc")
   * }}}
   */
  def withQueryDetection(pattern: Array[Pattern]) = new ScalaCompositeHandler(
    queryDetectionPattern(pattern: _*), queryHandler, updateHandler)

  /**
   * Returns handler that delegates query execution to `h` function.
   * Given function will be used only if executed statement is detected
   * as a query by withQueryDetection.
   *
   * {{{
   * import acolyte.jdbc.QueryExecution
   * import acolyte.jdbc.AcolyteDSL.handleStatement
   *
   * handleStatement withQueryHandler { e: QueryExecution => aQueryResult }
   *
   * // With pattern matching ...
   * import acolyte.jdbc.ParameterVal
   *
   * handleStatement withQueryHandler {
   *   _ match {
   *     case QueryExecution("SELECT * FROM Test WHERE id = ?", ParameterVal(1) :: Nil) => aQueryResult
   *     case _ => otherResult
   *   }
   * }
   * }}}
   */
  def withQueryHandler(h: QueryExecution ⇒ QueryResult) =
    new ScalaCompositeHandler(queryDetection, new QueryHandler {
      def apply(sql: String, p: JList[Parameter]): QueryResult =
        h(QueryExecution(sql, scalaParameters(p)))
    }, updateHandler)

  /**
   * Returns handler that delegates update execution to `h` function.
   * Given function will be used only if executed statement is not detected
   * as a query by withQueryDetection.
   *
   * {{{
   * import acolyte.jdbc.UpdateExecution
   * import acolyte.jdbc.AcolyteDSL.handleStatement
   *
   * handleStatement withUpdateHandler { e: UpdateExecution => aQueryResult }
   *
   * // With pattern matching ...
   * import acolyte.jdbc.ParameterVal
   *
   * handleStatement withUpdateHandler {
   *   _ match {
   *     case UpdateExecution("INSERT INTO Country (code, name) VALUES (?, ?)", ParameterVal(code) :: ParameterVal(name) :: Nil) => 1 /* update count */
   *     case _ => otherResult
   *   }
   * }
   * }}}
   */
  def withUpdateHandler(h: UpdateExecution ⇒ UpdateResult) =
    new ScalaCompositeHandler(queryDetection, queryHandler, new UpdateHandler {
      def apply(sql: String, p: JList[Parameter]): UpdateResult =
        h(UpdateExecution(sql, scalaParameters(p)))
    })

  private def scalaParameters(p: JList[Parameter]): List[ExecutedParameter] =
    p.asScala.foldLeft(Nil: List[ExecutedParameter]) { (l, t) ⇒
      l :+ DefinedParameter(t.right, t.left)
    }

}

trait CompositeHandlerImplicits { srl: ScalaRowLists ⇒

  /**
   * Allows to directly use update count as update result.
   *
   * {{{
   * ScalaCompositeHandler.empty withUpdateHandler { exec ⇒ 1/*count*/ }
   * }}}
   */
  implicit def IntUpdateResult(updateCount: Int) = new UpdateResult(updateCount)

  /**
   * Allows to directly use row list as query result.
   *
   * {{{
   * val qr: QueryResult = stringList
   * }}}
   */
  implicit def RowListAsResult[R <: RowList[_]](r: R): QueryResult = r.asResult

  /**
   * Allows to directly use string as query result.
   *
   * {{{
   * val qr: QueryResult = "str"
   * }}}
   */
  implicit def StringAsResult(v: String): QueryResult =
    (RowLists.stringList :+ v).asResult

  implicit def BooleanAsResult(v: Boolean): QueryResult =
    (RowLists.booleanList :+ v).asResult

  implicit def ByteAsResult(v: Byte): QueryResult =
    (RowLists.byteList :+ v).asResult

  implicit def ShortAsResult(v: Short): QueryResult =
    (RowLists.shortList :+ v).asResult

  implicit def IntAsResult(v: Int): QueryResult =
    (RowLists.intList :+ v).asResult

  implicit def LongAsResult(v: Long): QueryResult =
    (RowLists.longList :+ v).asResult

  implicit def FloatAsResult(v: Float): QueryResult =
    (RowLists.floatList :+ v).asResult

  implicit def DoubleAsResult(v: Double): QueryResult =
    (RowLists.doubleList :+ v).asResult

  implicit def ScalaBigDecimalAsResult(v: BigDecimal): QueryResult =
    (RowLists.bigDecimalList :+ v.bigDecimal).asResult

  implicit def JavaBigDecimalAsResult(v: java.math.BigDecimal): QueryResult =
    (RowLists.bigDecimalList :+ v).asResult

  implicit def DateAsTimestampResult(v: java.util.Date): QueryResult =
    (RowLists.timestampList :+ new java.sql.Timestamp(v.getTime)).asResult

  implicit def SqlDateAsTimestampResult(v: java.sql.Timestamp): QueryResult =
    (RowLists.timestampList :+ v).asResult

}

private object ScalaCompositeHandler {
  def empty = new ScalaCompositeHandler(null, null, null)
}

/**
 * Convertions from Java datatypes to Scala.
 */
@deprecated("Direct manipulation of row is no longer required", "1.0.12")
object JavaConverters {

  /**
   * Pimps result row.
   *
   * {{{
   * row.list // Scala list equivalent to .cells
   * }}}
   */
  implicit def rowAsScala[R <: Row](r: R): ScalaRow = new ScalaRow(r)

  final class ScalaRow(r: Row) extends Row {
    lazy val cells = r.cells

    lazy val list: List[Any] = cells.asScala.foldLeft(List[Any]()) {
      (l, v) ⇒ l :+ v
    }
  }
}
