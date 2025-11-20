package io.github.dorumrr.de1984.data.mapper

import io.github.dorumrr.de1984.data.database.entity.FirewallRuleEntity
import io.github.dorumrr.de1984.domain.model.FirewallRule

fun FirewallRuleEntity.toDomain(): FirewallRule {
    return FirewallRule(
        packageName = packageName,
        uid = uid,
        appName = appName,
        wifiBlocked = wifiBlocked,
        mobileBlocked = mobileBlocked,
        blockWhenBackground = blockWhenBackground,
        blockWhenRoaming = blockWhenRoaming,
        lanBlocked = lanBlocked,
        enabled = enabled,
        isSystemApp = isSystemApp,
        hasInternetPermission = hasInternetPermission,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun FirewallRule.toEntity(): FirewallRuleEntity {
    return FirewallRuleEntity(
        packageName = packageName,
        uid = uid,
        appName = appName,
        wifiBlocked = wifiBlocked,
        mobileBlocked = mobileBlocked,
        blockWhenBackground = blockWhenBackground,
        blockWhenRoaming = blockWhenRoaming,
        lanBlocked = lanBlocked,
        enabled = enabled,
        isSystemApp = isSystemApp,
        hasInternetPermission = hasInternetPermission,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun List<FirewallRuleEntity>.toDomain(): List<FirewallRule> {
    return map { it.toDomain() }
}

fun List<FirewallRule>.toEntity(): List<FirewallRuleEntity> {
    return map { it.toEntity() }
}

