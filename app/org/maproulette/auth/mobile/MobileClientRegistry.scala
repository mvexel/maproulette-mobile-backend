package org.maproulette.auth.mobile

import anorm._
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference
import javax.inject.{Inject, Singleton}
import play.api.db.Database

/**
  * The approved mobile clients. Only enabled clients can authorize, exchange codes, refresh or
  * authenticate; [[known]] also includes disabled ones (revocation stays possible).
  */
@com.google.inject.ImplementedBy(classOf[DbMobileClientRegistry])
trait MobileClientRegistry {

  /** Every client, enabled or not, ordered by id. */
  def all: Seq[MobileClient]
  def known(id: String): Option[MobileClient] = all.find(_.id == id)
  def get(id: String): Option[MobileClient]   = known(id).filter(_.enabled)

  /** The grant's scopes while its client is enabled and still allows all of them. */
  def allowedScopes(clientId: String, scope: String): Option[Set[String]] =
    MobileClientRegistry.allowedScopes(get(clientId), scope)

  /** Drop any cached copy after a write. */
  def invalidate(): Unit = ()
}

object MobileClientRegistry {
  def allowedScopes(client: Option[MobileClient], scope: String): Option[Set[String]] =
    for {
      value  <- client if value.enabled
      scopes <- MobileScopes.parse(scope) if scopes.subsetOf(value.scopes)
    } yield scopes
}

/** The configured clients only, without a database (unit tests). */
class StaticMobileClientRegistry(settings: MobileOAuthSettings) extends MobileClientRegistry {
  def all: Seq[MobileClient] = settings.clients.values.toSeq.sortBy(_.id)
}

/** Row mapping and SQL for `mobile_oauth_clients` (evolution 131). */
object MobileClientRows {
  private val parser: RowParser[MobileClient] = RowParser { row =>
    Success(
      MobileClient(
        row[String]("id"),
        row[String]("name"),
        row[Array[String]]("redirect_uris").toSet,
        MobileScopes.parseClient(row[String]("scopes")).getOrElse(Set.empty),
        row[Boolean]("enabled")
      )
    )
  }

  def list(implicit c: Connection): Seq[MobileClient] =
    SQL("SELECT * FROM mobile_oauth_clients ORDER BY id").as(parser.*)

  def lock(id: String)(implicit c: Connection): Option[MobileClient] =
    SQL("SELECT * FROM mobile_oauth_clients WHERE id={id} FOR UPDATE")
      .on("id" -> id)
      .as(parser.singleOpt)

  def insert(client: MobileClient, actor: Long)(implicit c: Connection): Boolean =
    SQL("""INSERT INTO mobile_oauth_clients
      (id,name,redirect_uris,scopes,enabled,created_by,updated_by)
      VALUES ({id},{name},CAST({uris} AS text[]),{scopes},{enabled},{actor},{actor})
      ON CONFLICT (id) DO NOTHING""")
      .on(values(client) :+ NamedParameter("actor", actor): _*)
      .executeUpdate() == 1

  def update(client: MobileClient, actor: Long)(implicit c: Connection): Unit = {
    SQL("""UPDATE mobile_oauth_clients SET name={name},redirect_uris=CAST({uris} AS text[]),
      scopes={scopes},enabled={enabled},updated_by={actor},updated_at=NOW() WHERE id={id}""")
      .on(values(client) :+ NamedParameter("actor", actor): _*)
      .executeUpdate()
    ()
  }

  private def values(client: MobileClient): Seq[NamedParameter] = Seq(
    "id"      -> client.id,
    "name"    -> client.name,
    "uris"    -> client.redirectUris.toArray.sorted,
    "scopes"  -> MobileScopes.format(client.scopes),
    "enabled" -> client.enabled
  )

  /**
    * Seeds from `mobileOAuth.clients`, once per process on first use. Precedence:
    *   1. A config client without a row is inserted (`created_by` NULL).
    *   2. A row that came from config and was never edited through the admin API
    *      (`created_by` and `updated_by` NULL) follows config: name, redirects, scopes, enabled.
    *   3. A row created or edited through the admin API is never changed by config.
    *   4. A config-seeded, never-edited row whose id left the config is disabled (not deleted),
    *      as removing a client from config did before this table existed.
    * Returns the config ids that were not applied because an admin owns the row.
    */
  def seed(clients: Seq[MobileClient])(implicit c: Connection): Seq[String] = {
    // Serializes concurrent seeding by several backend processes.
    SQL("SELECT pg_advisory_xact_lock(hashtext('mobile_oauth_clients_seed'))").execute()
    clients.foreach { client =>
      SQL(
        """INSERT INTO mobile_oauth_clients (id,name,redirect_uris,scopes,enabled)
        VALUES ({id},{name},CAST({uris} AS text[]),{scopes},{enabled})
        ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, redirect_uris=EXCLUDED.redirect_uris,
        scopes=EXCLUDED.scopes, enabled=EXCLUDED.enabled, updated_at=NOW()
        WHERE mobile_oauth_clients.created_by IS NULL AND mobile_oauth_clients.updated_by IS NULL
        AND (mobile_oauth_clients.name, mobile_oauth_clients.redirect_uris,
          mobile_oauth_clients.scopes, mobile_oauth_clients.enabled)
        IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.redirect_uris, EXCLUDED.scopes, EXCLUDED.enabled)"""
      ).on(values(client): _*)
        .executeUpdate()
    }
    SQL("""UPDATE mobile_oauth_clients SET enabled=false, updated_at=NOW()
      WHERE created_by IS NULL AND updated_by IS NULL AND enabled
      AND NOT (id = ANY(CAST({ids} AS text[])))""")
      .on("ids" -> clients.map(_.id).toArray)
      .executeUpdate()
    val stored = list.map(client => client.id -> client).toMap
    clients.filter(client => !stored.get(client.id).contains(client)).map(_.id)
  }
}

/**
  * Clients from `mobile_oauth_clients`, cached for [[DbMobileClientRegistry.CacheMillis]]. The
  * table is seeded from config once per process, on first use. Admin writes on this process
  * invalidate the cache at once; other processes see them within the cache period.
  */
@Singleton
class DbMobileClientRegistry @Inject() (db: Database, settings: MobileOAuthSettings)
    extends MobileClientRegistry {
  private val logger = play.api.Logger(getClass)
  private case class Snapshot(loadedAt: Long, clients: Seq[MobileClient])
  private val cache = new AtomicReference[Option[Snapshot]](None)
  // Bumped by invalidate(): a load that started before a write must not install its old rows.
  private val generation       = new java.util.concurrent.atomic.AtomicLong()
  @volatile private var seeded = false

  def all: Seq[MobileClient] =
    if (!settings.enabled) Seq.empty
    else {
      val now = System.nanoTime()
      cache.get() match {
        case Some(snapshot)
            if now - snapshot.loadedAt < DbMobileClientRegistry.CacheMillis * 1000000L =>
          snapshot.clients
        case current =>
          val started = generation.get()
          seedOnce()
          val loaded = db.withConnection(implicit c => MobileClientRows.list)
          if (generation.get() == started) cache.compareAndSet(current, Some(Snapshot(now, loaded)))
          loaded
      }
    }

  override def invalidate(): Unit = { generation.incrementAndGet(); cache.set(None) }

  private def seedOnce(): Unit = if (!seeded) synchronized {
    if (!seeded) {
      val skipped =
        db.withTransaction(implicit c => MobileClientRows.seed(settings.clients.values.toSeq))
      skipped.foreach { id =>
        logger.info(
          s"Mobile client $id: config entry not applied, the row is managed through the admin API"
        )
      }
      seeded = true
    }
  }
}

object DbMobileClientRegistry {
  val CacheMillis = 10000L
}
