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

package models.tosca

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._
import scalaz.syntax.SemigroupOps

import cache._
import db._
import models.json.tosca._
import models.json.tosca.carton._
import models.Constants._
import io.megam.auth.funnel.FunnelErrors._
import app.MConfig
import models.base._
import wash._

import com.stackmob.scaliak._
import com.basho.riak.client.core.query.indexes.{ RiakIndexes, StringBinIndex, LongIntIndex }
import com.basho.riak.client.core.util.{ Constants => RiakConstants }
import io.megam.util.Time
import io.megam.common.riak.GunnySack
import io.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset

import java.util.UUID
import com.datastax.driver.core.{ ResultSet, Row }
import com.websudos.phantom.dsl._
import scala.concurrent.{ Future => ScalaFuture }
import com.websudos.phantom.connectors.{ ContactPoint, KeySpaceDef }
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * @author rajthilak
 *
 */
case class AssembliesInput(name: String, org_id: String, assemblies: models.tosca.AssemblysList, inputs: KeyValueList) {
  val json = "{\"name\":\"" + name + "\",\"org_id\":\"" + org_id + "\", \"assemblies\":" + AssemblysList.toJson(assemblies, true) + ",\"inputs\":" + KeyValueList.toJson(inputs, true) + "}"
}

case class KeyValueField(key: String, value: String) {
  val json = "{\"key\":\"" + key + "\",\"value\":\"" + value + "\"}"

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    val preser = new KeyValueFieldSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    prettyRender(toJValue)
  } else {
    compactRender(toJValue)
  }

}

object KeyValueField {
  def empty: KeyValueField = new KeyValueField(new String(), new String())

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[KeyValueField] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    val preser = new KeyValueFieldSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[KeyValueField] = (Validation.fromTryCatchThrowable[net.liftweb.json.JValue, Throwable] {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}

case class AssembliesResult(id: String,
    accounts_id: String,
    org_id: String,
    name: String,
    assemblies: models.tosca.AssemblyLinks,
    inputs: KeyValueList,
    created_at: String) {

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    val preser = new AssembliesResultSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    prettyRender(toJValue)
  } else {
    compactRender(toJValue)
  }

}

object AssembliesResult {

  def apply(id: String, accounts_id: String, org_id: String, name: String, assemblies: models.tosca.AssemblyLinks, inputs: KeyValueList) = new AssembliesResult(id, accounts_id, org_id, name, assemblies, inputs, Time.now.toString)

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[AssembliesResult] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    val preser = new AssembliesResultSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[AssembliesResult] = (Validation.fromTryCatchThrowable[net.liftweb.json.JValue, Throwable] {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}

sealed class AssembliesSacks extends CassandraTable[AssembliesSacks, AssembliesResult] {

  implicit val formats = DefaultFormats

  object id extends StringColumn(this)
  object accounts_id extends StringColumn(this) with PartitionKey[String]
  object org_id extends StringColumn(this) with PartitionKey[String]
  object name extends StringColumn(this)
  object assemblies extends ListColumn[AssembliesSacks, AssembliesResult, String](this)

  object inputs extends JsonListColumn[AssembliesSacks, AssembliesResult, KeyValueField](this) {
    override def fromJson(obj: String): KeyValueField = {
      JsonParser.parse(obj).extract[KeyValueField]
    }

    override def toJson(obj: KeyValueField): String = {
      compactRender(Extraction.decompose(obj))
    }
  }

  object created_at extends StringColumn(this)

  def fromRow(row: Row): AssembliesResult = {
    AssembliesResult(
      id(row),
      accounts_id(row),
      org_id(row),
      name(row),
      assemblies(row),
      inputs(row),
      created_at(row))
  }
}

abstract class ConcreteAssemblies extends AssembliesSacks with RootConnector {
  // you can even rename the table in the schema to whatever you like.
  override lazy val tableName = "assemblies"
  override implicit def space: KeySpace = scyllaConnection.space
  override implicit def session: Session = scyllaConnection.session

  def insertNewRecord(ams: AssembliesResult): ValidationNel[Throwable, ResultSet] = {
    val res = insert.value(_.id, ams.id)
      .value(_.accounts_id, ams.accounts_id)
      .value(_.org_id, ams.org_id)
      .value(_.name, ams.name)
      .value(_.assemblies, ams.assemblies)
      .value(_.inputs, ams.inputs)
      .value(_.created_at, ams.created_at)
      .future()
    Await.result(res, 5.seconds).successNel
  }

  def listRecords(email: String, org: String): ValidationNel[Throwable, Seq[AssembliesResult]] = {
    val res = select.allowFiltering().where(_.accounts_id eqs email).and(_.org_id eqs org).fetch()
    Await.result(res, 5.seconds).successNel
  }

}

case class WrapAssembliesResult(thatGS: Option[GunnySack], idPair: Map[String, String]) {

  implicit val formats = DefaultFormats

  val ams = parse(thatGS.get.value).extract[AssembliesResult].some

  def cattype = idPair.map(x => x._2.split('.')(1)).head
}

object Assemblies extends ConcreteAssemblies {

  // implicit val formats = DefaultFormats

  private lazy val bucker = "assemblies"

  private lazy val idxedBy = idxTeamId

  private val riak = GWRiak(bucker)

  private def mkAssembliesSack(authBag: Option[io.megam.auth.stack.AuthBag], input: String): ValidationNel[Throwable, AssembliesResult] = {
    val ripNel: ValidationNel[Throwable, AssembliesInput] = (Validation.fromTryCatchThrowable[AssembliesInput, Throwable] {
      parse(input).extract[AssembliesInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel
    for {
      rip <- ripNel
      aor <- (Accounts.findByEmail(authBag.get.email) leftMap { t: NonEmptyList[Throwable] => t })
      ams <- (AssemblysList.createLinks(authBag, rip.assemblies) leftMap { t: NonEmptyList[Throwable] => t })
      uir <- (UID("ams").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {
      val asml = ams.flatMap { assembly => nels({ assembly.map { a => (a.id, a.tosca_type) } }) }
      val asmlist = asml.toList.filterNot(_.isEmpty)
      AssembliesResult(uir.get._1 + uir.get._2, aor.get.email, rip.org_id, rip.name, asmlist.map(_.get._1), rip.inputs)
    }
  }

  def create(authBag: Option[io.megam.auth.stack.AuthBag], input: String): ValidationNel[Throwable, AssembliesResult] = {
    for {
      ams <- (mkAssembliesSack(authBag, input) leftMap { err: NonEmptyList[Throwable] => err })
      set <- (insertNewRecord(ams) leftMap { t: NonEmptyList[Throwable] => t })
    } yield {
      ams
    }
  }

  def findById(assembliesID: Option[List[String]]): ValidationNel[Throwable, AssembliesResults] = {
    (assembliesID map {
      _.map { asm_id =>
        play.api.Logger.debug(("%-20s -->[%s]").format("Assemblies Id", asm_id))
        (riak.fetch(asm_id) leftMap { t: NonEmptyList[Throwable] =>
          new ServiceUnavailableError(asm_id, (t.list.map(m => m.getMessage)).mkString("\n"))
        }).toValidationNel.flatMap { xso: Option[GunnySack] =>
          xso match {
            case Some(xs) => {
              (AssembliesResult.fromJson(xs.value) leftMap
                { t: NonEmptyList[net.liftweb.json.scalaz.JsonScalaz.Error] =>
                  JSONParsingError(t)
                }).toValidationNel.flatMap { j: AssembliesResult =>
                  play.api.Logger.debug(("%-20s -->[%s]").format("AssembliesResult", j))
                  Validation.success[Throwable, AssembliesResults](nels(j.some)).toValidationNel //screwy kishore, every element in a list ?
                }
            }
            case None => {
              Validation.failure[Throwable, AssembliesResults](new ResourceItemNotFound(asm_id, "")).toValidationNel
            }
          }
        }
      } // -> VNel -> fold by using an accumulator or successNel of empty. +++ => VNel1 + VNel2
    } map {
      _.foldRight((AssembliesResults.empty).successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head.
  }

  /*
   * An IO wrapped finder using an email. Upon fetching the account_id for an email,
   * the csarnames are listed on the index (account.id) in bucket `CSARs`.
   * Using a "csarname" as key, return a list of ValidationNel[List[CSARResult]]
   * Takes an email, and returns a Future[ValidationNel, List[Option[CSARResult]]]
   */
  def findByEmail(email: String, org: String): ValidationNel[Throwable, AssembliesResults] = {
    (listRecords(email, org) leftMap { t: NonEmptyList[Throwable] =>
      new ResourceItemNotFound(email, "Assemblies = nothing found.")
    }).toValidationNel.flatMap { nm: Seq[AssembliesResult] =>
      if (!nm.isEmpty)
        Validation.success[Throwable, AssembliesResults](nels(nm.map(x => x.some).head)).toValidationNel
      else
        Validation.failure[Throwable, AssembliesResults](new ResourceItemNotFound(email, "Assemblies = nothing found.")).toValidationNel
    }

  }

  /* Lets clean it up in 1.0 using Messageable  */
  private def pub(email: String, wa: WrapAssembliesResult): ValidationNel[Throwable, AssembliesResult] = {
    models.base.Requests.createAndPub(email, RequestInput(wa.ams.get.id, wa.cattype, "", CREATE, STATE).json)
    wa.ams.get.successNel[Throwable]
  }
}
