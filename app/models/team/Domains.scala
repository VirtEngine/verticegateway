/*
** Copyright [2013-2016] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package models.team

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._
import scalaz.syntax.SemigroupOps

import models.json._
import models.base._
import db._
import cache._
import app.MConfig
import models.Constants._
import io.megam.auth.funnel.FunnelErrors._
import scalaz.Validation
import scalaz.Validation.FlatMap._

import com.stackmob.scaliak._
import com.basho.riak.client.core.query.indexes.{ RiakIndexes, StringBinIndex, LongIntIndex }
import com.basho.riak.client.core.util.{ Constants => RiakConstants }
import io.megam.common.riak.GunnySack
import io.megam.util.Time
import io.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset
//import com.twitter.util.{ Future, Await }
import scala.concurrent.Await
import scala.concurrent.duration._

//import com.twitter.conversions.time._
import org.joda.time.DateTime

import com.websudos.phantom.dsl._
import com.websudos.phantom.iteratee.Iteratee
import com.websudos.phantom.connectors.{ ContactPoint, KeySpaceDef }

/**
 *
 * @author morpheyesh
 */

case class DomainsInput(name: String) {
  val json = "{\"name\":\"" + name + "\"}"
}

case class DomainsResult(
  id: String,
  org_id: String,
  name: String,
  created_at: String) {

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.team.DomainsResultSerialization
    val preser = new DomainsResultSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    prettyRender(toJValue)
  } else {
    compactRender(toJValue)
  }
}

object DomainsResult {

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[DomainsResult] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON

    import models.json.team.DomainsResultSerialization
    val preser = new DomainsResultSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[DomainsResult] = (Validation.fromTryCatchThrowable[net.liftweb.json.JValue, Throwable] {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}

sealed class DomainsT extends CassandraTable[DomainsT, DomainsResult] {

  object id extends StringColumn(this) with PrimaryKey[String]
  object org_id extends StringColumn(this) with PartitionKey[String]
  object name extends StringColumn(this)
  object created_at extends StringColumn(this)

  override def fromRow(r: Row): DomainsResult = {
    DomainsResult(
      id(r),
      org_id(r),
      name(r),
      created_at(r))
  }
}

/*
 *   This class talks to scylla and performs the actions
 */

abstract class ConcreteDmn extends DomainsT with ScyllaConnector {

  override lazy val tableName = "domains"

  def insertNewRecord(d: DomainsResult): ResultSet = {
    val res = insert.value(_.id, d.id)
      .value(_.org_id, d.org_id)
      .value(_.name, d.name)
      .value(_.created_at, d.created_at)
      .future()
    Await.result(res, 5.seconds)
  }

}

object Domains extends ConcreteDmn {

  implicit val formats = DefaultFormats

  private def dmnNel(input: String): ValidationNel[Throwable, DomainsInput] = {
    (Validation.fromTryCatchThrowable[DomainsInput, Throwable] {
      parse(input).extract[DomainsInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel
  }

  private def domainsSet(id: String, org_id: String, c: DomainsInput): ValidationNel[Throwable, DomainsResult] = {
    (Validation.fromTryCatchThrowable[DomainsResult, Throwable] {
      DomainsResult(id, org_id, c.name, Time.now.toString)
    } leftMap { t: Throwable => new MalformedBodyError(c.json, t.getMessage) }).toValidationNel
  }

  /*
   * org_id is set as the partition key - orgId is retrieved from header
   */

  def create(org_id: String, input: String): ValidationNel[Throwable, DomainsResult] = {
    for {
      c <- dmnNel(input)
      uir <- (UID("DMN").get leftMap { u: NonEmptyList[Throwable] => u })
      dmn <- domainsSet(uir.get._1 + uir.get._2, org_id, c)
    } yield {
      insertNewRecord(dmn)
      dmn
    }
  }

  def findByOrgId(id: String): ValidationNel[Throwable, DomainsResults] = {
    val resp = select.allowFiltering().where(_.id eqs id).fetch()
    val p = (Await.result(resp, 5.seconds)) map { i: DomainsResult => (i.some) }
    Validation.success[Throwable, DomainsResults](nel(p.head, p.tail)).toValidationNel
  }

}
