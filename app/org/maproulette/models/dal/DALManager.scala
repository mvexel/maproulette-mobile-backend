/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.models.dal

import javax.inject.{Inject, Singleton}
import org.maproulette.data._
import org.maproulette.exception.NotFoundException

/**
  * Factory that contains references to all the DAL's in the system
  *
  * @author cuthbertm
  */
@Singleton
class DALManager @Inject() (
    taskDAL: TaskDAL,
    challengeDAL: ChallengeDAL,
    virtualChallengeDAL: VirtualChallengeDAL,
    actionManager: ActionManager,
    dataManager: DataManager,
    statusActionManager: StatusActionManager
) {
  def task: TaskDAL = taskDAL

  def challenge: ChallengeDAL = challengeDAL

  def virtualChallenge: VirtualChallengeDAL = virtualChallengeDAL

  def action: ActionManager = actionManager

  def data: DataManager = dataManager

  def statusAction: StatusActionManager = statusActionManager

  def getManager(itemType: ItemType): BaseDAL[Long, _] = {
    itemType match {
      case ChallengeType()        => challengeDAL
      case VirtualChallengeType() => virtualChallengeDAL
      case TaskType()             => taskDAL
      case _                      => throw new NotFoundException("No manager of that type found.")
    }
  }
}
