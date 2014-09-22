package acolyte.reactivemongo

import scala.concurrent.{ ExecutionContext, Future }
import reactivemongo.api.{ DB, MongoConnection, MongoDriver }

/** Functions to work with Mongo DB (provided driver functions). */
trait WithDB { withDriver: WithDriver =>
  /**
   * Works with Mongo database (named "acolyte") resolved using given driver
   * initialized using Acolyte for ReactiveMongo
   * (should not be used with other driver instances).
   * Driver and associated resources are released
   * after the function `f` the result `Future` is completed.
   *
   * @param driverParam Driver manager parameter (see [[DriverManager]])
   * @param f Function applied to initialized Mongo DB
   *
   * {{{
   * import reactivemongo.api.DB
   * import acolyte.reactivemongo.AcolyteDSL
   *
   * // handler: ConnectionHandler
   * val s: Future[String] = AcolyteDSL withDB(handler) { db =>
   *   val d: DB = db
   *   "Result"
   * }
   * }}}
   * @see [[AcolyteDSL.withConnection]]
   * @see [[withFlatDB]]
   */
  def withDB[A, B](driverParam: ⇒ A)(f: DB ⇒ B)(implicit m: DriverManager[A], c: ExecutionContext): Future[B] = withConnection(driverParam) {
    con ⇒ f(con("acolyte"))
  }

  /**
   * Works with Mongo database (named "acolyte") resolved using given driver
   * initialized using Acolyte for ReactiveMongo
   * (should not be used with other driver instances).
   *
   * @param driverParam Driver manager parameter (see [[DriverManager]])
   * @param f Function applied to initialized Mongo DB
   *
   * {{{
   * import reactivemongo.api.DB
   * import acolyte.reactivemongo.AcolyteDSL
   *
   * // handler: ConnectionHandler
   * val s: Future[String] = AcolyteDSL withConnection(handler) { con =>
   *   AcolyteDSL withDB(con) { db =>
   *     val d: DB = db
   *     "Result"
   *   }
   * }
   * }}}
   * @see [[AcolyteDSL.withConnection]]
   * @see [[withFlatDB]]
   */
  def withDB[T](con: ⇒ MongoConnection)(f: DB ⇒ T)(implicit c: ExecutionContext): Future[T] = Future(f(con("acolyte")))

  /**
   * Works with Mongo database (named "acolyte") resolved using given driver
   * initialized using Acolyte for ReactiveMongo
   * (should not be used with other driver instances).
   *
   * @param driverParam Driver manager parameter (see [[DriverManager]])
   * @param f Function applied to initialized Mongo DB
   *
   * {{{
   * import reactivemongo.api.DB
   * import acolyte.reactivemongo.AcolyteDSL
   *
   * // handler: ConnectionHandler
   * val s: Future[String] = AcolyteDSL withFlatDB(handler) { db =>
   *   val d: DB = db
   *   Future.successful("Result")
   * }
   * }}}
   * @see [[AcolyteDSL.withFlatConnection]]
   * @see [[withDB]]
   */
  def withFlatDB[A, B](driverParam: ⇒ A)(f: DB ⇒ Future[B])(implicit m: DriverManager[A], c: ExecutionContext): Future[B] = withFlatConnection(driverParam) {
    con ⇒ f(con("acolyte"))
  }

  /**
   * Works with Mongo database (named "acolyte") resolved using given driver
   * initialized using Acolyte for ReactiveMongo
   * (should not be used with other driver instances).
   *
   * @param driverParam Driver manager parameter (see [[DriverManager]])
   * @param f Function applied to initialized Mongo DB
   *
   * {{{
   * import reactivemongo.api.DB
   * import acolyte.reactivemongo.AcolyteDSL
   *
   * // handler: ConnectionHandler
   * val s: Future[String] = AcolyteDSL withConnection(handler) { con =>
   *   AcolyteDSL withDB(con) { db =>
   *     val d: DB = db
   *     Future.successful("Result")
   *   }
   * }
   * }}}
   * @see [[AcolyteDSL.withConnection]]
   * @see [[withFlatDB]]
   */
  def withFlatDB[T](con: ⇒ MongoConnection)(f: DB ⇒ Future[T])(implicit c: ExecutionContext): Future[T] = f(con("acolyte"))

}
