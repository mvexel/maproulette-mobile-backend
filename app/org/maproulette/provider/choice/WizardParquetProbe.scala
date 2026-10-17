package org.maproulette.provider.choice

import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import play.api.libs.ws.WSClient
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Spike (architecture/wizard-parquet.md): whether this server reaches the Layercake GeoParquet
  * host. One ranged GET of the file's last 8 bytes, which end in the Parquet magic `PAR1`, logged
  * at startup like [[WizardOverpassProbe]]. `mobileWizard.parquetProbeUrl` names the file.
  * It also reads the first 16 bytes of the OSM extract a nightly load would fetch
  * (`mobileWizard.extractProbeUrl`), which hold the PBF `OSMHeader` block name.
  */
@Singleton
class WizardParquetProbe @Inject() (ws: WSClient, config: Config)(implicit ec: ExecutionContext) {
  private val logger = play.api.Logger(getClass)

  protected def url: String =
    config.config
      .getOptional[String]("mobileWizard.parquetProbeUrl")
      .filter(_.nonEmpty)
      .getOrElse(WizardParquetProbe.DefaultUrl)

  protected def extractUrl: String =
    config.config
      .getOptional[String]("mobileWizard.extractProbeUrl")
      .filter(_.nonEmpty)
      .getOrElse(WizardParquetProbe.DefaultExtractUrl)

  def probe(): Future[String] =
    read("Parquet host", url, "bytes=-8") { (status, bytes) =>
      if (status == 206 && bytes.takeRight(4).utf8String == "PAR1") "Parquet footer"
      else s"${bytes.length} bytes"
    }

  def extractProbe(): Future[String] =
    read("Extract host", extractUrl, "bytes=0-15") { (status, bytes) =>
      if (status == 206 && bytes.utf8String.contains("OSMHeader")) "PBF header"
      else s"${bytes.length} bytes"
    }

  private def read(label: String, target: String, range: String)(
      describe: (Int, akka.util.ByteString) => String
  ): Future[String] =
    Future(WizardOverpass.resolved(target)).flatMap { addresses =>
      val started = System.nanoTime()
      def ms      = (System.nanoTime() - started) / 1000000
      ws.url(target)
        .withRequestTimeout(10.seconds)
        .withHttpHeaders("User-Agent" -> WizardOverpass.UserAgent, "Range" -> range)
        .get()
        .map { response =>
          val tail = describe(response.status, response.bodyAsBytes)
          val size = response.header("Content-Range").fold("")(r => s", $r")
          s"$label answered: HTTP ${response.status} in $ms ms, $tail$size ($target, $addresses)"
        }
        .recover {
          case e: Exception =>
            s"$label not reachable: ${e.getClass.getSimpleName} after $ms ms ($target, $addresses)"
        }
    }

  probe().foreach(line => logger.info(line))
  extractProbe().foreach(line => logger.info(line))
}

object WizardParquetProbe {
  val DefaultUrl        = "https://data.openstreetmap.us/layercake/pois.parquet"
  val DefaultExtractUrl = "https://download.geofabrik.de/north-america/us/utah-latest.osm.pbf"
}
