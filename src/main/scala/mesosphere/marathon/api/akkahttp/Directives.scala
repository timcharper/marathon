package mesosphere.marathon
package api.akkahttp
import akka.http.scaladsl.server.{ Directive1, Directives => AkkaDirectives }
import com.wix.accord.{ Success, Failure, Validator }

/**
  * All Marathon Directives and Akka Directives
  *
  * These should be imported by the respective controllers
  */
object Directives extends AuthDirectives with LeaderDirectives with AkkaDirectives {
  val RemainingPathId = {
    import mesosphere.marathon.state.PathId.StringPathId
    RemainingPath.map(_.toString.toRootPath)
  }

  /**
    * Validate the given resource using the implicit validator in scope; reject if invalid
    *
    * Ideally, we validate while unmarshalling; however, in the case of app updates, we need to apply validation after
    * applying some update operation.
    */
  def validated[T](resource: T)(implicit validator: Validator[T]): Directive1[T] = {
    validator(resource) match {
      case Success => provide(resource)
      case failure: Failure =>
        reject(EntityMarshallers.ValidationFailed(failure))
    }
  }
}
