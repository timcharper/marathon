package mesosphere.marathon
package api.akkahttp
package v2

import akka.event.EventStream
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ StatusCodes, Uri }
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Route
import mesosphere.marathon.api.v2.{ AppNormalization, AppTasksResource, InfoEmbedResolver, LabelSelectorParsers, AppHelpers }
import mesosphere.marathon.api.akkahttp.{ Controller, EntityMarshallers }
import mesosphere.marathon.api.v2.AppHelpers.authzSelector
import mesosphere.marathon.api.v2.validation.AppValidation
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.base.Clock
import mesosphere.marathon.core.deployment.DeploymentPlan
import mesosphere.marathon.core.event.ApiPostEvent
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.core.plugin.PluginManager
import mesosphere.marathon.plugin.auth.{ Authenticator => MarathonAuthenticator, Authorizer, CreateRunSpec, Identity, ViewResource }
import mesosphere.marathon.state.{ AppDefinition, Identifiable, PathId }
import play.api.libs.json._
import mesosphere.marathon.core.election.ElectionService

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal

class AppsController(
    val clock: Clock,
    val eventBus: EventStream,
    val appTasksRes: AppTasksResource,
    val service: MarathonSchedulerService,
    val appInfoService: AppInfoService,
    val config: MarathonConf,
    val groupManager: GroupManager,
    val pluginManager: PluginManager)(
    implicit
    val actorSystem: ActorSystem,
    val executionContext: ExecutionContext,
    val authenticator: MarathonAuthenticator,
    val authorizer: Authorizer,
    val electionService: ElectionService
) extends Controller {
  import Directives._

  private implicit lazy val validateApp = AppDefinition.validAppDefinition(config.availableFeatures)(pluginManager)
  private implicit lazy val updateValidator = AppValidation.validateCanonicalAppUpdateAPI(config.availableFeatures)

  import AppHelpers._
  import EntityMarshallers._

  import mesosphere.marathon.api.v2.json.Formats._

  private def listApps(implicit identity: Identity): Route = {
    parameters('cmd.?, 'id.?, 'label.?, 'embed.*) { (cmd, id, label, embed) =>
      def index: Future[Seq[AppInfo]] = {
        def containCaseInsensitive(a: String, b: String): Boolean = b.toLowerCase contains a.toLowerCase

        val selectors = Seq[Option[Selector[AppDefinition]]](
          cmd.map(c => Selector(_.cmd.exists(containCaseInsensitive(c, _)))),
          id.map(s => Selector(app => containCaseInsensitive(s, app.id.toString))),
          label.map(new LabelSelectorParsers().parsed),
          Some(authzSelector)
        ).flatten
        val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.toSet) + AppInfo.Embed.Counts + AppInfo.Embed.Deployments
        appInfoService.selectAppsBy(Selector.forall(selectors), resolvedEmbed)
      }
      onSuccess(index)(apps => complete(Json.obj("apps" -> apps)))
    }
  }

  private def createApp(implicit identity: Identity): Route = {
    (entity(as[AppDefinition]) & parameters('force.as[Boolean].?(false))) { (app, force) =>

      def create: Future[(DeploymentPlan, AppInfo)] = {

        def createOrThrow(opt: Option[AppDefinition]) = opt
          .map(_ => throw ConflictingChangeException(s"An app with id [${app.id}] already exists."))
          .getOrElse(app)

        groupManager.updateApp(app.id, createOrThrow, app.version, force).map { plan =>
          val appWithDeployments = AppInfo(
            app,
            maybeCounts = Some(TaskCounts.zero),
            maybeTasks = Some(Seq.empty),
            maybeDeployments = Some(Seq(Identifiable(plan.id)))
          )
          plan -> appWithDeployments
        }
      }
      authorized(CreateRunSpec, app).apply {
        onSuccess(create) { (plan, app) =>
          //TODO: post ApiPostEvent
          complete((StatusCodes.Created, Seq(Headers.`Marathon-Deployment-Id`(plan.id)), app))
        }
      }
    }
  }

  private def showApp(appId: PathId)(implicit identity: Identity): Route = {
    parameters('embed.*) { embed =>
      val resolvedEmbed = InfoEmbedResolver.resolveApp(embed.toSet) ++ Set(
        // deprecated. For compatibility.
        AppInfo.Embed.Counts, AppInfo.Embed.Tasks, AppInfo.Embed.LastTaskFailure, AppInfo.Embed.Deployments
      )

      onSuccess(appInfoService.selectApp(appId, authzSelector, resolvedEmbed)) {
        case None =>
          reject(Rejections.EntityNotFound.app(appId))
        case Some(info) =>
          authorized(ViewResource, info.app).apply {
            complete(Json.obj("app" -> info))
          }
      }
    }
  }

  /**
    * Internal representation of `replace or update` logic.
    *
    * @param id appId
    * @param body request body
    * @param force force update?
    * @param partialUpdate partial update?
    * @param req http servlet request
    * @param allowCreation is creation allowed?
    * @param identity implicit identity
    * @return http servlet response
    */
  private[this] def update(appId: PathId, partialUpdate: Boolean, allowCreation: Boolean)(implicit identity: Identity) = {
    val version = clock.now()

    (parameter('force.as[Boolean].?(false)) &
      extractClientIP &
      extractUri &
      entity(as(appUpdateUnmarshaller(appId, partialUpdate)))) { (force, remoteAddr, requestUri, appUpdate) =>
        /* Note - this function throws exceptions and handles authorization synchronously. We need to catch and map these
       * exceptions to the appropriate rejections */
        val fn = updateOrCreate(
          appId, _: Option[AppDefinition], appUpdate, partialUpdate, allowCreation, clock.now(), service)

        onComplete(foldExceptions(groupManager.updateApp(appId, fn, version, force))) {
          case Success(plan) =>
            plan.target.app(appId).foreach { appDef =>
              eventBus.publish(ApiPostEvent(remoteAddr.toString, requestUri.toString, appDef))
            }

            plan.original.app(appId) match {
              case Some(_) =>
                complete(Messages.DeploymentResult(plan))
              case None =>
                complete((StatusCodes.Created, List(Location(Uri(appId.toString))), Messages.DeploymentResult(plan)))
            }

          case Failure(ValidationFailedException(_, failure)) =>
            reject(EntityMarshallers.ValidationFailed(failure))
          case Failure(AccessDeniedException(msg)) =>
            reject(AuthDirectives.NotAuthorized(AuthDirectives.ToResponseString(msg)))
          case Failure(_: AppNotFoundException) =>
            reject(Rejections.EntityNotFound.app(appId))
          case Failure(ex) =>
            throw ex
        }
      }
  }

  private def foldExceptions[T](f: => Future[T]): Future[T] =
    try { f }
    catch {
      case NonFatal(ex) =>
        Future.failed(ex)
    }

  private def patchSingle(appId: PathId)(implicit identity: Identity) = {
    update(appId, partialUpdate = true, allowCreation = false)
  }

  val route: Route = {
    asLeader(electionService) {
      authenticated.apply { implicit identity =>
        pathEnd {
          post {
            createApp
          } ~
            get {
              listApps
            }
        } ~
          path(RemainingPathId) { appId =>
            get {
              showApp(appId)
            } ~
              patch {
                patchSingle(appId)
              }
          }
      }
    }
  }

  private val normalizationConfig = AppNormalization.Configure(
    config.defaultNetworkName.get,
    config.mesosBridgeName())

  private implicit val validateAndNormalizeApp: Normalization[raml.App] =
    appNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())

  private implicit val validateAndNormalizeAppUpdate: Normalization[raml.AppUpdate] =
    appUpdateNormalization(config.availableFeatures, normalizationConfig)(AppNormalization.withCanonizedIds())
}
