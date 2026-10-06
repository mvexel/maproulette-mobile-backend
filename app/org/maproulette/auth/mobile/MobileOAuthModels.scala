package org.maproulette.auth.mobile

import java.time.Instant

/** Pending browser authorization. No upstream OSM token or raw mobile credential is persisted. */
case class MobileInteraction(
    idHash: String,
    browserHash: String,
    clientId: String,
    redirectUri: String,
    scope: String,
    clientState: String,
    codeChallenge: String,
    expiresAt: Instant,
    userId: Option[Long] = None,
    csrfHash: Option[String] = None
)

/** Immutable authorization context used by the OAuth library and the resource-server gate. */
case class MobileGrant(
    familyId: String,
    userId: Long,
    clientId: String,
    scope: String,
    redirectUri: String,
    codeChallenge: String
)

case class TokenHashes(
    accessHash: String,
    refreshHash: String,
    accessExpiresAt: Instant,
    refreshExpiresAt: Instant
)

/** Implementations commit replay revocation before returning None. */
@com.google.inject.ImplementedBy(classOf[MobileOAuthRepository])
trait MobileOAuthStore {
  def createInteraction(interaction: MobileInteraction): Unit
  def getInteraction(idHash: String, browserHash: String, now: Instant): Option[MobileInteraction]
  def claimLogin(idHash: String, browserHash: String, now: Instant): Option[MobileInteraction]
  def completeLogin(
      idHash: String,
      browserHash: String,
      userId: Long,
      csrfHash: String,
      now: Instant
  ): Boolean
  def approveInteraction(
      idHash: String,
      browserHash: String,
      csrfHash: String,
      codeHash: String,
      familyId: String,
      codeExpiresAt: Instant,
      now: Instant
  ): Option[MobileGrant]
  def declineInteraction(
      idHash: String,
      browserHash: String,
      csrfHash: String,
      now: Instant
  ): Option[MobileInteraction]
  def findCode(codeHash: String, now: Instant): Option[MobileGrant]
  def findRefresh(refreshHash: String, now: Instant): Option[MobileGrant]
  def redeemCode(
      codeHash: String,
      clientId: String,
      redirectUri: String,
      pkceChallenge: String,
      pair: TokenHashes,
      now: Instant
  ): Option[MobileGrant]
  def rotate(
      refreshHash: String,
      clientId: String,
      pair: TokenHashes,
      now: Instant
  ): Option[MobileGrant]
  def authenticate(accessHash: String, now: Instant): Option[MobileGrant]
  def revoke(tokenHash: String, clientId: String, now: Instant): Unit

  /** Holds the sealed OSM token on a logged-in, unconsumed interaction until consent. */
  def attachOsmToken(
      idHash: String,
      browserHash: String,
      userId: Long,
      token: SealedOsmToken,
      osmScope: String,
      now: Instant
  ): Boolean
  def osmToken(familyId: String): Option[StoredOsmToken]
  def deleteOsmToken(familyId: String): Unit
}

/** An `osm:tagfix` grant family's OSM token. Never logged; opened only to call OSM. */
case class StoredOsmToken(familyId: String, userId: Long, token: SealedOsmToken, osmScope: String)
