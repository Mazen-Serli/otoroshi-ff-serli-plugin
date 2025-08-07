// Package declaration for the Otoroshi plugin
package otoroshi_plugins.com.serli.otoroshi.plugins.FFSerli

// Import statements for required dependencies
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

/**
 * Configuration case class for the Feature Flags plugin
 * Contains all necessary parameters to connect to the FF-SERLI API
 *
 * @param ttl Time To Live for cached feature flags
 * @param apiDomain Domain/URL of the FF-SERLI API
 * @param projectId Unique identifier for the project in FF-SERLI
 * @param apiKey Authentication token for API access
 */
case class FeatureFlagsConfig(
                               ttl: FiniteDuration,
                               apiDomain: String,
                               projectId: String,
                               apiKey: String
                             ) extends NgPluginConfig {
  // Converts the configuration to JSON format for serialization
  def json: JsValue = FeatureFlagsConfig.format.writes(this)
}

/**
 * Companion object for FeatureFlagsConfig
 * Provides default configuration and JSON serialization/deserialization
 */
private object FeatureFlagsConfig {
  // Default configuration with empty values that need to be filled by the user
  val default: FeatureFlagsConfig = FeatureFlagsConfig(
    ttl = 10.minutes,        // Default cache duration of 10 minutes
    apiDomain = "",          // Must be configured by user
    apiKey = "",             // Must be configured by user
    projectId = ""           // Must be configured by user
  )

  // JSON format handler for reading and writing configuration
  val format: Format[FeatureFlagsConfig] = new Format[FeatureFlagsConfig] {
    // Deserializes JSON to FeatureFlagsConfig object
    override def reads(json: JsValue): JsResult[FeatureFlagsConfig] = Try {
      FeatureFlagsConfig(
        // Parse TTL from milliseconds, default to 10 minutes if not specified
        ttl = json.select("ttl").asOpt[Long].map(m => FiniteDuration(m, TimeUnit.MILLISECONDS)).getOrElse(10.minutes),
        apiDomain = json.select("api_domain").asString,
        apiKey = json.select("api_key").asString,
        projectId = json.select("project_id").asString
      )
    } match {
      case Failure(e) => JsError(e.getMessage)    // Return error if parsing fails
      case Success(cfg) => JsSuccess(cfg)         // Return successful configuration
    }

    // Serializes FeatureFlagsConfig object to JSON
    override def writes(o: FeatureFlagsConfig): JsValue = Json.obj(
      "ttl" -> o.ttl.toMillis,           // Convert duration to milliseconds
      "api_domain" -> o.apiDomain,
      "api_key" -> o.apiKey,
      "project_id" -> o.projectId
    )
  }
}

/**
 * Manager object for feature flags context storage
 * Provides a typed key for storing feature flags in the request context
 */
object FeatureFlagsManager {
  // Typed key used to store feature flags in the Otoroshi request context
  // This allows other parts of the application to access the feature flags
  val FeatureFlagsKey: TypedKey[JsValue] = TypedKey[JsValue]("com.serli.otoroshi.plugins.FFSerli.FeatureFlags")
}

/**
 * Main plugin class that implements the Feature Flags functionality
 * Extends NgRequestTransformer to intercept and modify HTTP requests
 *
 * This plugin:
 * 1. Fetches feature flags from FF-SERLI API
 * 2. Caches the flags to reduce API calls
 * 3. Injects flags into outgoing requests as HTTP headers
 * 4. Stores flags in request context for other components to use
 */
class FeatureFlagsPlugin extends NgRequestTransformer {

  // In-memory cache for feature flags using Scaffeine (Scala wrapper for Caffeine)
  // Cache configuration:
  // - Expires after 5 minutes of creation, update, or access
  // - Maximum size of 1000 entries to prevent memory issues
  // - Key: project ID, Value: JSON object containing feature flags
  private val flagsCache: Cache[String, JsValue] = Scaffeine()
    .expireAfter[String, JsValue](
      create = (_, _) => 5.minutes,      // Expire 5 minutes after creation
      update = (_, _, _) => 5.minutes,   // Expire 5 minutes after update
      read   = (_, _, current) => current // Keep current expiration on read
    )
    .maximumSize(1000)                   // Limit cache size
    .build()

  // Plugin metadata and configuration
  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest)  // Only transform requests
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Transformations, NgPluginCategory.Custom("Feature Flipping SERLI"))
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand  // Available to users
  override def multiInstance: Boolean            = true   // Allow multiple instances
  override def core: Boolean                     = true   // Core plugin
  override def name: String                      = "FF-Serli"  // Plugin display name
  override def description: Option[String]       = "Injecte les feature flags dans la requête sortante".some  // French description
  override def defaultConfigObject: Option[NgPluginConfig] = FeatureFlagsConfig.default.some

  // UI configuration for the Otoroshi admin interface
  override def noJsForm: Boolean = true  // Use simple form instead of JavaScript form
  override def configFlow: Seq[String] = Seq("ttl", "api_domain", "api_key", "project_id")  // Order of fields in UI

  // JSON schema for the configuration form in Otoroshi admin UI
  override def configSchema: Option[JsObject] = Some(Json.obj(
    "ttl"       -> Json.obj("type" -> "number", "label" -> "Durée cache (ms)"),      // Cache duration in milliseconds
    "api_domain"-> Json.obj("type" -> "string", "label" -> "Domaine de l'API FF-SERLI"),  // API domain URL
    "api_key"   -> Json.obj("type" -> "string", "label" -> "API Key de l'organisation"),  // Organization API key
    "project_id"-> Json.obj("type" -> "string", "label" -> "Cle du projet")              // Project identifier
  ))

  /**
   * Plugin startup method called when Otoroshi initializes the plugin
   * Logs that the plugin has started successfully
   */
  override def start(env: Env): Future[Unit] = {
    env.logger.info("[FF-SERLI] Plugin Feature Flags Serli demarre")  // Log startup in French
    ().vfuture  // Return successful future
  }

  /**
   * Main request transformation method
   * Called for every request that passes through this plugin
   *
   * @param ctx The request transformation context containing request data and configuration
   * @return Either an HTTP Result (to stop processing) or a transformed NgPluginHttpRequest
   */
  override def transformRequest(
                                 ctx: NgTransformerRequestContext
                               )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    // Retrieve plugin configuration from cache, fallback to default if not found
    val config = ctx.cachedConfig(internalName)(FeatureFlagsConfig.format)
      .getOrElse(FeatureFlagsConfig.default)

    // Only proceed if all required configuration fields are provided
    if (config.apiKey.nonEmpty && config.projectId.nonEmpty && config.apiDomain.nonEmpty) {
      // Fetch feature flags (from cache or API)
      fetchFlags(config).map { flags =>
        // Store feature flags in request context for use by other components
        ctx.attrs.put(FeatureFlagsManager.FeatureFlagsKey -> flags)

        // Get the original HTTP request
        val original = ctx.otoroshiRequest

        // Add feature flags as a custom HTTP header (X-Feature-Flags)
        // This allows the target service to access the feature flags
        val enriched: Map[String, String] = original.headers + ("X-Feature-Flags" -> Json.stringify(flags))

        // Return the modified request with the new header
        Right(original.copy(headers = enriched))
      }
    } else {
      // Configuration is incomplete - log warning and pass request unchanged
      if (config.apiDomain.isEmpty) {
        env.logger.warn("[FF-SERLI] Domaine de l'API non configure - plugin desactive")  // Warn about missing API domain
      }
      // Return original request without modification
      Future.successful(Right(ctx.otoroshiRequest))
    }
  }

  /**
   * Internal method to fetch feature flags from cache or API
   * Implements caching strategy to reduce API calls and improve performance
   *
   * @param config Plugin configuration containing API credentials and settings
   * @return Future containing JSON object with feature flags
   */
  private def fetchFlags(config: FeatureFlagsConfig)
                        (implicit env: Env, ec: ExecutionContext): Future[JsValue] = {
    val key = config.projectId  // Use project ID as cache key

    // Check if flags are already cached
    flagsCache.getIfPresent(key) match {
      case Some(flags) =>
        // Cache hit - return cached flags immediately
        Future.successful(flags)
      case None        =>
        // Cache miss - fetch from API

        // Normalize API domain URL (remove trailing slash to avoid double slashes)
        val normalizedDomain = config.apiDomain.stripSuffix("/")

        // Construct FF-SERLI API endpoint URL
        val url = s"$normalizedDomain/ff-serli-api/v1/projects/${config.projectId}/flags"

        // Make HTTP GET request to FF-SERLI API
        env.Ws.url(url)
          .withFollowRedirects(true)                    // Follow HTTP redirects
          .withRequestTimeout(5.seconds)                // 5 second timeout
          .withHttpHeaders(
            "Authorization" -> s"Bearer ${config.apiKey}",  // Bearer token authentication
            "Content-Type"  -> "application/json"           // Expect JSON response
          )
          .get()
          .map { resp =>
            if (resp.status == 200) {
              // Successful API response
              val flagsResponse = resp.json

              // Extract the "flags" property from the API response
              // API response format: {"flags": {"flag1": true, "flag2": false, ...}}
              val flags = (flagsResponse \ "flags").asOpt[JsObject].getOrElse(Json.obj())

              // Log successful flag retrieval with flag names
              env.logger.info(s"[FF-SERLI] Flags recuperes avec succes pour le projet ${config.projectId}: ${flags.keys.mkString(", ")}")

              // Store flags in cache for future requests
              flagsCache.put(key, flags)
              flags
            } else {
              // API returned error status
              env.logger.error(s"[FF-SERLI] Echec lors de la recuperation des flags: ${resp.status} ${resp.body}")

              // Return error information in JSON format instead of empty object
              // This allows debugging of API issues
              Json.obj("error" -> s"Echec lors de la recuperation des flags: ${resp.status}, pour le projet : ${config.projectId} avec raison : ${resp.body}")
            }
          }
          .recover { case e =>
            // Handle network errors, timeouts, or other exceptions
            env.logger.error("[FF-SERLI] Erreur recuperation flags", e)

            // Return empty JSON object on error to avoid breaking the request
            Json.obj()
          }
    }
  }
}
