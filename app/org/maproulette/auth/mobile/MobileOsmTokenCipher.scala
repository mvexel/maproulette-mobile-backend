package org.maproulette.auth.mobile

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import javax.inject.{Inject, Singleton}
import scala.util.Try

case class SealedOsmToken(ciphertext: Array[Byte], nonce: Array[Byte])

/**
  * AES-256-GCM for OSM access tokens held for mobile `osm:tagfix` grants. The user id is bound as
  * associated data, so a row copied to another user does not decrypt. Without a configured key
  * nothing is sealed or opened.
  */
@Singleton
class MobileOsmTokenCipher @Inject() (settings: MobileOAuthSettings) {
  private val random = new SecureRandom()
  private def aad(userId: Long): Array[Byte] =
    s"maproulette-mobile-osm-token:v1:$userId".getBytes(StandardCharsets.UTF_8)
  private def cipher(mode: Int, key: Array[Byte], nonce: Array[Byte], userId: Long): Cipher = {
    val value = Cipher.getInstance("AES/GCM/NoPadding")
    value.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce))
    value.updateAAD(aad(userId))
    value
  }

  def available: Boolean = settings.osmTokenKey.isDefined

  def seal(userId: Long, token: String): Option[SealedOsmToken] = settings.osmTokenKey.map { key =>
    val nonce = new Array[Byte](12)
    random.nextBytes(nonce)
    SealedOsmToken(
      cipher(Cipher.ENCRYPT_MODE, key, nonce, userId)
        .doFinal(token.getBytes(StandardCharsets.UTF_8)),
      nonce
    )
  }

  /**
    * The token, or why it cannot be opened: "no_key" (nothing configured; the row may still be
    * good), or "unreadable" (tampered, sealed for another user, or under another key).
    */
  def open(userId: Long, value: SealedOsmToken): Either[String, String] =
    settings.osmTokenKey match {
      case None => Left("no_key")
      case Some(key) =>
        Try(
          new String(
            cipher(Cipher.DECRYPT_MODE, key, value.nonce, userId).doFinal(value.ciphertext),
            StandardCharsets.UTF_8
          )
        ).toOption.toRight("unreadable")
    }
}
