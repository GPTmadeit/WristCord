package com.wristcord.discord.state

import com.wristcord.discord.model.Channel
import com.wristcord.discord.model.Overwrite
import com.wristcord.discord.model.Role

/**
 * Discord's permission resolution, the documented algorithm: @everyone, then member roles, then
 * channel overwrites (@everyone, role, member). READY sends every channel of a server, including
 * ones the account cannot open, so the channel list has to filter them itself.
 */
object Permissions {
    const val ADMINISTRATOR = 1L shl 3
    const val VIEW_CHANNEL = 1L shl 10
    const val SEND_MESSAGES = 1L shl 11
    const val ATTACH_FILES = 1L shl 15
    const val READ_MESSAGE_HISTORY = 1L shl 16
    const val CONNECT = 1L shl 20
    const val ADD_REACTIONS = 1L shl 6

    private const val ALL = -1L

    fun base(guildId: Long, ownerId: Long?, userId: Long, memberRoles: Collection<Long>, roles: Map<Long, Role>): Long {
        if (ownerId == userId) return ALL
        var perms = roles[guildId]?.permissions ?: 0L
        for (r in memberRoles) perms = perms or (roles[r]?.permissions ?: 0L)
        return if (perms and ADMINISTRATOR != 0L) ALL else perms
    }

    fun channel(
        base: Long,
        guildId: Long,
        userId: Long,
        memberRoles: Collection<Long>,
        overwrites: List<Overwrite>,
    ): Long {
        if (base == ALL) return ALL
        var perms = base
        overwrites.firstOrNull { it.id == guildId }?.let { perms = (perms and it.deny.inv()) or it.allow }
        var allow = 0L
        var deny = 0L
        for (o in overwrites) {
            if (o.type == 0 && o.id != guildId && o.id in memberRoles) {
                allow = allow or o.allow
                deny = deny or o.deny
            }
        }
        perms = (perms and deny.inv()) or allow
        overwrites.firstOrNull { it.type == 1 && it.id == userId }?.let { perms = (perms and it.deny.inv()) or it.allow }
        return perms
    }

    /**
     * Channel visibility, including the category rule: a channel inside a category the user
     * cannot see is still visible if its own overwrites allow it, which [channel] already covers
     * because overwrites are per channel.
     */
    fun canView(
        channel: Channel,
        guildId: Long,
        ownerId: Long?,
        userId: Long,
        memberRoles: Collection<Long>,
        roles: Map<Long, Role>,
    ): Boolean {
        val base = base(guildId, ownerId, userId, memberRoles, roles)
        val perms = channel(base, guildId, userId, memberRoles, channel.overwrites)
        return perms and VIEW_CHANNEL != 0L
    }

    fun has(perms: Long, flag: Long) = perms == ALL || perms and flag != 0L
}
