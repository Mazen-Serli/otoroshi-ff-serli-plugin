package otoroshi_plugins.com.serli.otoroshi.plugins.FFSerli

import akka.stream.Materializer
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits.{BetterJsReadable, BetterJsValue, BetterSyntax}
import play.api.libs.json._
import play.api.libs.typedmap.TypedKey
import play.api.mvc.Result

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// Configuration du plugin
case class FeatureFlagsConfig(
                               ttl: FiniteDuration,
                               apiDomain: String,
                               projectId: String,
                               apiKey: String
                             ) extends NgPluginConfig {
  def json: JsValue = FeatureFlagsConfig.format.writes(this)
}

private object FeatureFlagsConfig {
  val default: FeatureFlagsConfig = FeatureFlagsConfig(
    ttl = 10.minutes,
    apiDomain = "",
    apiKey = "",
    projectId = ""
  )
  val format: Format[FeatureFlagsConfig] = new Format[FeatureFlagsConfig] {
    override def reads(json: JsValue): JsResult[FeatureFlagsConfig] = Try {
      FeatureFlagsConfig(
        ttl = json.select("ttl").asOpt[Long].map(m => FiniteDuration(m, TimeUnit.MILLISECONDS)).getOrElse(10.minutes),
        apiDomain = json.select("api_domain").asString,
        apiKey = json.select("api_key").asString,
        projectId = json.select("project_id").asString
      )
    } match {
      case Failure(e) => JsError(e.getMessage)
      case Success(cfg) => JsSuccess(cfg)
    }
    override def writes(o: FeatureFlagsConfig): JsValue = Json.obj(
      "ttl" -> o.ttl.toMillis,
      "api_domain" -> o.apiDomain,
      "api_key" -> o.apiKey,
      "project_id" -> o.projectId
    )
  }
}

// Clé pour stocker les flags dans le contexte
object FeatureFlagsManager {
  val FeatureFlagsKey: TypedKey[JsValue] = TypedKey[JsValue]("com.serli.otoroshi.plugins.FFSerli.FeatureFlags")
}

// Plugin qui transforme la requête pour y injecter les feature flags
class FeatureFlagsPlugin extends NgRequestTransformer {

  private val flagsCache: Cache[String, JsValue] = Scaffeine()
    .expireAfter[String, JsValue](
      create = (_, _) => 5.minutes,
      update = (_, _, _) => 5.minutes,
      read   = (_, _, current) => current
    )
    .maximumSize(1000)
    .build()

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations, NgPluginCategory.Custom("Feature Flipping SERLI"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean            = true
  override def core: Boolean                     = true
  override def name: String                      = "FF-Serli"
  override def description: Option[String]       = "Injecte les feature flags dans la requête sortante".some
  override def defaultConfigObject: Option[NgPluginConfig] = FeatureFlagsConfig.default.some

  override def noJsForm: Boolean = true
  override def configFlow: Seq[String] = Seq("ttl", "api_domain", "api_key", "project_id")
  override def configSchema: Option[JsObject] = Some(Json.obj(
    "ttl"       -> Json.obj("type" -> "number", "label" -> "Durée cache (ms)"),
    "api_domain"-> Json.obj("type" -> "string", "label" -> "Domaine de l'API FF-SERLI"),
    "api_key"   -> Json.obj("type" -> "string", "label" -> "API Key de l'organisation"),
    "project_id"-> Json.obj("type" -> "string", "label" -> "Cle du projet")
  ))

  override def start(env: Env): Future[Unit] = {
    env.logger.info("[FF-SERLI] Plugin Feature Flags Serli demarre")
    ().vfuture
  }

  override def transformRequest(
                                 ctx: NgTransformerRequestContext
                               )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val config = ctx.cachedConfig(internalName)(FeatureFlagsConfig.format)
      .getOrElse(FeatureFlagsConfig.default)

    if (config.apiKey.nonEmpty && config.projectId.nonEmpty && config.apiDomain.nonEmpty) {
      fetchFlags(config).map { flags =>
        // Stockage des flags dans le contexte
        ctx.attrs.put(FeatureFlagsManager.FeatureFlagsKey -> flags)

        // Récupération de la requête originale
        val original = ctx.otoroshiRequest
        // Enrichissement des headers
        val enriched: Map[String, String] = original.headers + ("X-Feature-Flags" -> Json.stringify(flags))
        // Renvoi de la requête transformée
        Right(original.copy(headers = enriched))
      }
    } else {
      // Config incomplète : on renvoie la requête inchangée
      if (config.apiDomain.isEmpty) {
        env.logger.warn("[FF-SERLI] Domaine de l'API non configure - plugin desactive")
      }
      Future.successful(Right(ctx.otoroshiRequest))
    }
  }

  // Méthode interne pour récupérer ou mettre en cache les flags
  private def fetchFlags(config: FeatureFlagsConfig)
                        (implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    val key = config.projectId
    flagsCache.getIfPresent(key) match {
      case Some(flags) => Future.successful(flags)
      case None        =>
        // Normalisation du domaine pour éviter les double slashes
        val normalizedDomain = config.apiDomain.stripSuffix("/")
        val url = s"$normalizedDomain/ff-serli-api/v1/projects/${config.projectId}/flags"
        env.Ws.url(url)
          .withFollowRedirects(true)
          .withRequestTimeout(5.seconds)
          .withHttpHeaders(
            "Authorization" -> s"Bearer ${config.apiKey}",
            "Content-Type"  -> "application/json"
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              val flagsResponse = resp.json
              // On extrait la propriété "flags" de la réponse
              val flags = (flagsResponse \ "flags").asOpt[JsObject].getOrElse(Json.obj())
              env.logger.info(s"[FF-SERLI] Flags recuperes avec succes pour le projet ${config.projectId}: ${flags.keys.mkString(", ")}")
              flagsCache.put(key, flags)
              flags
            } else {
              // En cas d'erreur, retourner un objet JSON vide
              env.logger.error(s"[FF-SERLI] Echec lors de la recuperation des flags: ${resp.status} ${resp.body}")
              Json.obj("error" -> s"Echec lors de la recuperation des flags: ${resp.status}, pour le projet : ${config.projectId} avec raison : ${resp.body}")
            }
          }
          .recover { case e =>
            env.logger.error("[FF-SERLI] Erreur recuperation flags", e)
            Json.obj()
          }
    }
  }
}
