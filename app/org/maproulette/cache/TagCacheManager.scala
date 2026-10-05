/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.cache

import java.util.concurrent.locks.{ReadWriteLock, ReentrantReadWriteLock}

import anorm._
import javax.inject.{Inject, Singleton}
import org.maproulette.Config
import org.maproulette.framework.model.Tag
import org.maproulette.framework.repository.TagRepository
import play.api.db.Database

/**
  * This is not currently a real cache manager, it will just store elements that have been loaded into memory.
  * Using this too much will most likely end up in OutOfMemory exceptions, so this needs to be readdressed
  * prior to a live version of this service. The CacheStorage is the area that you would be required
  * to be modified.
  *
  * @author cuthbertm
  */
@Singleton
class TagCacheManager @Inject() (db: Database, config: Config)
    extends CacheManager[Long, Tag](config, Config.CACHE_ID_TAGS) {

  private val loadingLock: ReadWriteLock = new ReentrantReadWriteLock()

  def reloadTags: Unit = {
    this.loadingLock.writeLock().lock()
    try {
      db.withConnection { implicit c =>
        this.cache.clear()
        SQL"""SELECT * FROM tags""".as(TagRepository.parser.*).foreach(tag => cache.addObject(tag))
      }
    } finally {
      this.loadingLock.writeLock().unlock()
    }
  }
}
