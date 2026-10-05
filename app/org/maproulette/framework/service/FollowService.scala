/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import javax.inject.{Inject, Singleton}
import org.maproulette.data.{UserType}
import org.maproulette.exception.{NotFoundException, InvalidException}
import org.maproulette.framework.model.{User, Follower, Group, MemberObject}
import org.maproulette.permissions.Permission
import org.maproulette.provider.websockets.{WebSocketMessages, WebSocketProvider}

/**
  * Service for handling following and followers for users
  *
  * @author nrotstan
  */
@Singleton
class FollowService @Inject() (
    serviceManager: ServiceManager,
    webSocketProvider: WebSocketProvider,
    permission: Permission
) {

  /**
    * Follow a user, updating the follower's "following" group and the
    * followed user's "followers" group
    *
    * @param follower The user who is to be the follower
    * @param followed The user to be followed
    * @param user     The user making the request
    */
  def follow(follower: User, followed: User, user: User): Unit = {
    this.permission.hasWriteAccess(UserType(), user)(follower.id)
    if (!followed.settings.allowFollowing.getOrElse(true)) {
      throw new InvalidException("User cannot be followed")
    }

    this.serviceManager.group.addGroupMember(
      this.getFollowingGroup(follower, user),
      MemberObject.user(followed.id)
    )

    this.serviceManager.group.addGroupMember(
      this.getFollowersGroup(followed, user),
      MemberObject.user(follower.id)
    )

    webSocketProvider.sendMessage(
      WebSocketMessages.followUpdate(
        WebSocketMessages.FollowUpdateData(Some(follower.id), Some(followed.id))
      )
    )

    this.serviceManager.notification.createFollowedNotification(follower, followed.id)
  }

  /**
    * Stop following a user, updating the follower's "following" group and the
    * followed user's "followers" group
    *
    * @param follower The user who is following
    * @param followed The user being followed
    * @param user     The user making the request
    */
  def stopFollowing(follower: User, followed: User, user: User): Unit = {
    this.permission.hasWriteAccess(UserType(), user)(followed.id)

    this.serviceManager.group.removeGroupMember(
      this.getFollowingGroup(follower, user),
      MemberObject.user(followed.id)
    )

    this.serviceManager.group.removeGroupMember(
      this.getFollowersGroup(followed, user),
      MemberObject.user(follower.id)
    )

    webSocketProvider.sendMessage(
      WebSocketMessages.followUpdate(
        WebSocketMessages.FollowUpdateData(Some(follower.id), Some(followed.id))
      )
    )
  }

  /**
    * Clear all of a user's followers
    *
    * @param followed The user for which followers are to be cleared
    * @param user     The user making the request
    */
  def clearFollowers(followed: User, user: User): Unit = {
    this.permission.hasWriteAccess(UserType(), user)(followed.id)
    this.serviceManager.group.clearGroupMembers(this.getFollowersGroup(followed, user))
    this.serviceManager.group.removeGroupMemberAcrossGroupType(
      MemberObject.user(followed.id),
      Group.GROUP_TYPE_FOLLOWING
    )

    webSocketProvider.sendMessage(
      WebSocketMessages.followUpdate(
        WebSocketMessages.FollowUpdateData(None, Some(followed.id))
      )
    )
  }

  /**
    * Block a follower from following a user, updating their status in the "followers"
    * group to BLOCKED
    *
    * @param follower The user to be blocked
    * @param followed The user being followed
    * @param user     The user making the request
    */
  def blockFollower(followerId: Long, followed: User, user: User): Unit = {
    this.permission.hasWriteAccess(UserType(), user)(followed.id)

    this.serviceManager.group.updateGroupMemberStatus(
      this.getFollowersGroup(followed, user),
      MemberObject.user(followerId),
      Follower.STATUS_BLOCKED
    )

    webSocketProvider.sendMessage(
      WebSocketMessages.followUpdate(
        WebSocketMessages.FollowUpdateData(Some(followerId), Some(followed.id))
      )
    )
  }

  /**
    * Unblock a follower, allowing them to follow a user again
    *
    * @param follower The user to be unblocked
    * @param followed The user being followed
    * @param user     The user making the request
    */
  def unblockFollower(followerId: Long, followed: User, user: User): Unit = {
    this.permission.hasWriteAccess(UserType(), user)(followed.id)

    this.serviceManager.group.updateGroupMemberStatus(
      this.getFollowersGroup(followed, user),
      MemberObject.user(followerId),
      Follower.STATUS_FOLLOWING
    )

    webSocketProvider.sendMessage(
      WebSocketMessages.followUpdate(
        WebSocketMessages.FollowUpdateData(Some(followerId), Some(followed.id))
      )
    )
  }

  /**
    * Retrieve list of Users being followed by a user
    *
    * @param follower The user following the desired users
    * @param user     The user making the request
    */
  def getUsersFollowedBy(followerId: Long, user: User): List[User] =
    this.getGroupUsers(
      this.getFollowingGroup(
        retrieveUserOrError(followerId),
        user
      )
    )

  /**
    * Retrieve list of Followers who are following a user
    *
    * @param followed The user being followed by the desired users
    * @param user     The user making the request
    */
  def getUserFollowers(followedId: Long, user: User): List[Follower] = {
    val userMembers = this.serviceManager.group.membersOfType(
      this.getFollowersGroup(retrieveUserOrError(followedId), user),
      UserType()
    )
    val users = this.serviceManager.user.retrieveListById(userMembers.map(_.memberId))

    userMembers
      .map(member => {
        users.find(u => u.id == member.memberId) match {
          case Some(user) => Some(Follower(member.id, user, member.status))
          case None       => None
        }
      })
      .flatten
  }

  /**
    * Retrieves the Following group for a User, creating it first if it doesn't
    * exist
    *
    * @param follower The user whose following group is desired
    * @param user     The user making the request
    */
  def getFollowingGroup(follower: User, user: User): Group = {
    this.serviceManager.group
      .retrieve(
        follower.followingGroupId match {
          case Some(groupId) => groupId
          case None =>
            this.serviceManager.user.addFollowingGroup(follower, user).get
        }
      )
      .get
  }

  /**
    * Retrieves the Followers group for a User, creating it first if it doesn't
    * exist
    *
    * @param followed The user whose followers group is desired
    * @param user     The user making the request
    */
  def getFollowersGroup(followed: User, user: User): Group = {
    this.serviceManager.group
      .retrieve(
        followed.followersGroupId match {
          case Some(groupId) => groupId
          case None =>
            this.serviceManager.user.addFollowersGroup(followed, user).get
        }
      )
      .get
  }

  /**
    * Retrieves a User or throws a NotFoundException if the user doesn't exist
    *
    * @param id The identifier for the object
    * @return An optional object, None if not found
    */
  private def retrieveUserOrError(userId: Long): User =
    this.serviceManager.user.retrieve(userId) match {
      case Some(u) => u
      case None    => throw new NotFoundException(s"No user with id ${userId} found")
    }

  /**
    * Retrieves User representations of user members of a group
    */
  private def getGroupUsers(group: Group): List[User] =
    this.serviceManager.user.retrieveListById(
      this.serviceManager.group.membersOfType(group, UserType()).map(_.memberId)
    )
}
