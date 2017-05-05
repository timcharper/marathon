package mesosphere.marathon
package api.akkahttp

import akka.http.scaladsl.marshalling.{ Marshaller, ToEntityMarshaller }
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ Rejection, RejectionError, Route }
import akka.http.scaladsl.unmarshalling.{ FromMessageUnmarshaller, FromEntityUnmarshaller, Unmarshaller }
import akka.util.ByteString
import com.wix.accord.{ Failure, Success, Validator }
import mesosphere.marathon.api.v2.Validation
import mesosphere.marathon.core.appinfo.AppInfo
import mesosphere.marathon.plugin.PathId
import mesosphere.marathon.state.AppDefinition
import play.api.libs.json._

object EntityMarshallers {
  import Directives.complete
  import mesosphere.marathon.api.v2.json.Formats._

  private val jsonStringUnmarshaller =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(`application/json`)
      .mapWithCharset {
        case (ByteString.empty, _) => throw Unmarshaller.NoContentException
        case (data, charset) => data.decodeString(charset.nioCharset.name)
      }

  private val jsonStringMarshaller =
    Marshaller.stringMarshaller(`application/json`)

  /**
    * HTTP entity => `A`
    *
    * @param reads reader for `A`
    * @tparam A type to decode
    * @return unmarshaller for `A`
    */
  private def playJsonUnmarshaller[A](
    implicit
    reads: Reads[A]
  ): FromEntityUnmarshaller[A] = {
    def read(json: JsValue) =
      reads
        .reads(json)
        .recoverTotal(
          error =>
            throw new IllegalArgumentException(JsError.toJson(error).toString)
        )
    jsonStringUnmarshaller.map(data => read(Json.parse(data)))
  }

  /**
    * `A` => HTTP entity
    *
    * @param writes writer for `A`
    * @param printer pretty printer function
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  private def playJsonMarshaller[A](
    implicit
    writes: Writes[A],
    printer: JsValue => String = Json.prettyPrint
  ): ToEntityMarshaller[A] =
    jsonStringMarshaller.compose(printer).compose(writes.writes)

  import mesosphere.marathon.raml.AppConversion.appRamlReader
  implicit def appDefinitionUnmarshaller(
    implicit
    normalization: Normalization[raml.App],
    validator: Validator[AppDefinition]): FromEntityUnmarshaller[AppDefinition] = {
    validEntityRaml(playJsonUnmarshaller[raml.App])
  }

  implicit def appUpdateUnmarshaller(
    appId: PathId, partialUpdate: Boolean)(
    implicit
    appUpdateNormalization: Normalization[raml.AppUpdate],
    appNormalization: Normalization[raml.App]): FromEntityUnmarshaller[raml.AppUpdate] = {
    if (partialUpdate)
      playJsonUnmarshaller[raml.AppUpdate].map { appUpdate =>
        appUpdateNormalization.normalized(appUpdate.copy(id = Some(appId.toString)))
      }
    else
      playJsonUnmarshaller[JsObject].map { jsObj =>
        // this is a complete replacement of the app as we know it, so parse and normalize as if we're dealing
        // with a brand new app because the rules are different (for example, many fields are non-optional with brand-new apps).
        // however since this is an update, the user isn't required to specify an ID as part of the definition so we do
        // some hackery here to pass initial JSON parsing.
        val app = (jsObj + ("id" -> JsString(appId.toString))).as[raml.App]
        appNormalization.normalized(app).toRaml[raml.AppUpdate]
      }
  }

  implicit val jsValueMarshaller = playJsonMarshaller[JsValue]
  implicit val wixResultMarshaller = playJsonMarshaller[com.wix.accord.Failure](Validation.failureWrites)
  implicit val messageMarshaller = playJsonMarshaller[Rejections.Message]
  implicit val appInfoMarshaller = playJsonMarshaller[AppInfo]
  implicit val deploymentResultMarshaller = playJsonMarshaller[Messages.DeploymentResult]

  private def validEntityRaml[A, B](um: FromEntityUnmarshaller[A])(
    implicit
    normalization: Normalization[A], reader: raml.Reads[A, B], validator: Validator[B]): FromEntityUnmarshaller[B] = {
    um.map { ent =>
      val normalized = reader.read(normalization.normalized(ent))
      validator(normalized) match {
        case Success => normalized
        case failure: Failure =>
          throw new RejectionError(ValidationFailed(failure))
      }
    }
  }

  case class ValidationFailed(failure: Failure) extends Rejection

  def handleNonValid: PartialFunction[Rejection, Route] = {
    case ValidationFailed(failure) =>
      complete(StatusCodes.UnprocessableEntity -> failure)
  }

  import scala.language.implicitConversions
  implicit def entityMarshallerToMessageUnmarshaller[T](um: FromEntityUnmarshaller[T]): FromMessageUnmarshaller[T] =
    Unmarshaller.withMaterializer { implicit ec ⇒ implicit mat ⇒ request ⇒ um(request.entity) }
}
