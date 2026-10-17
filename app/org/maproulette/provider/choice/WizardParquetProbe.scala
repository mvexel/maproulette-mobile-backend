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
  */
@Singleton
class WizardParquetProbe @Inject() (ws: WSClient, config: Config)(implicit ec: ExecutionContext) {
  private val logger = play.api.Logger(getClass)

  protected def url: String =
    config.config
      .getOptional[String]("mobileWizard.parquetProbeUrl")
      .filter(_.nonEmpty)
      .getOrElse(WizardParquetProbe.DefaultUrl)

  def probe(): Future[String] = {
    val target = url
    Future(WizardOverpass.resolved(target)).flatMap { addresses =>
      val started = System.nanoTime()
      def ms      = (System.nanoTime() - started) / 1000000
      ws.url(target)
        .withRequestTimeout(10.seconds)
        .withHttpHeaders("User-Agent" -> WizardOverpass.UserAgent, "Range" -> "bytes=-8")
        .get()
        .map { response =>
          val bytes = response.bodyAsBytes
          val tail =
            if (response.status == 206 && bytes.takeRight(4).utf8String == "PAR1") "Parquet footer"
            else s"${bytes.length} bytes"
          val size = response.header("Content-Range").fold("")(r => s", $r")
          s"Parquet host answered: HTTP ${response.status} in $ms ms, $tail$size ($target, $addresses)"
        }
        .recover {
          case e: Exception =>
            s"Parquet host not reachable: ${e.getClass.getSimpleName} after $ms ms ($target, $addresses)"
        }
    }
  }

  probe().foreach(line => logger.info(line))
}

object WizardParquetProbe {
  val DefaultUrl = "https://data.openstreetmap.us/layercake/pois.parquet"
}
