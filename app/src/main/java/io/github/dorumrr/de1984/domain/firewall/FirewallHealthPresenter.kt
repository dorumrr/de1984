package io.github.dorumrr.de1984.domain.firewall

import android.content.Context
import androidx.annotation.StringRes
import io.github.dorumrr.de1984.R

enum class FirewallHealthAction(@StringRes val label: Int) {
    CHOOSE_BACKEND(R.string.firewall_down_action_choose_backend),

    RETRY(R.string.firewall_down_action_retry),

    ENABLE_VPN(R.string.firewall_down_action_enable_vpn),

    REPLACE_VPN(R.string.firewall_down_action_replace_vpn),

    RETRY_STOP(R.string.firewall_stop_failed_action_retry),

    // Not RETRY: startFirewall() returns early when the same backend is already running.
    REAPPLY_RULES(R.string.firewall_down_action_retry),
}

/**
 * Wording for the banner and the Down and StopFailed notifications; VPN conflict and VPN permission
 * keep their own notifications. In domain, not ui, because FirewallManager builds notifications from it.
 */
object FirewallHealthPresenter {

    fun title(context: Context, health: FirewallHealth): String? = when (health) {
        is FirewallHealth.Healthy -> null
        is FirewallHealth.Down -> context.getString(R.string.firewall_down_title)
        is FirewallHealth.SwitchedToVpn -> context.getString(R.string.firewall_switched_title)
        is FirewallHealth.StopFailed -> context.getString(R.string.firewall_stop_failed_title)
        is FirewallHealth.ApplyFailed -> context.getString(R.string.firewall_apply_failed_title)
    }

    fun message(context: Context, health: FirewallHealth): String? = when (health) {
        is FirewallHealth.Healthy -> null

        is FirewallHealth.Down -> when (health.reason) {
            FirewallHealth.Down.Reason.NO_FALLBACK_PLAN ->
                context.getString(R.string.firewall_down_reason_no_plan)
            FirewallHealth.Down.Reason.FALLBACK_FAILED ->
                context.getString(R.string.firewall_down_reason_fallback_failed)
            FirewallHealth.Down.Reason.VPN_CONFLICT ->
                context.getString(R.string.firewall_down_reason_vpn_conflict)
            FirewallHealth.Down.Reason.VPN_PERMISSION_REQUIRED ->
                context.getString(R.string.firewall_down_reason_vpn_permission)
            FirewallHealth.Down.Reason.START_FAILED ->
                context.getString(R.string.firewall_down_reason_start_failed)
        }

        is FirewallHealth.SwitchedToVpn -> {
            val backendName = health.failedBackend.displayName(context)
            if (health.fromManualMode) {
                context.getString(R.string.firewall_switched_message_manual, backendName)
            } else {
                context.getString(R.string.firewall_switched_message_auto, backendName)
            }
        }

        is FirewallHealth.StopFailed -> {
            val backendName = health.backend?.displayName(context)
            if (backendName != null) {
                context.getString(R.string.firewall_stop_failed_message, backendName)
            } else {
                context.getString(R.string.firewall_stop_failed_message_unknown)
            }
        }

        is FirewallHealth.ApplyFailed ->
            context.getString(R.string.firewall_apply_failed_message, health.backend.displayName(context))
    }

    fun action(health: FirewallHealth): FirewallHealthAction? = when (health) {
        is FirewallHealth.Healthy -> null

        is FirewallHealth.SwitchedToVpn -> null

        is FirewallHealth.StopFailed -> FirewallHealthAction.RETRY_STOP

        is FirewallHealth.ApplyFailed -> FirewallHealthAction.REAPPLY_RULES

        is FirewallHealth.Down -> when (health.reason) {
            FirewallHealth.Down.Reason.NO_FALLBACK_PLAN -> FirewallHealthAction.CHOOSE_BACKEND
            FirewallHealth.Down.Reason.FALLBACK_FAILED -> FirewallHealthAction.RETRY
            FirewallHealth.Down.Reason.VPN_CONFLICT -> FirewallHealthAction.REPLACE_VPN
            FirewallHealth.Down.Reason.VPN_PERMISSION_REQUIRED -> FirewallHealthAction.ENABLE_VPN
            FirewallHealth.Down.Reason.START_FAILED -> FirewallHealthAction.RETRY
        }
    }

    /**
     * True when nothing is being blocked right now.
     *
     * Drives the red styling and the DOWN badge. [FirewallHealth.SwitchedToVpn] is deliberately not
     * critical: protection is still on, just not on the backend the user had.
     */
    fun isCritical(health: FirewallHealth): Boolean = health is FirewallHealth.Down

    /**
     * True when this state's warning is carried by a notification as well as by the banner.
     *
     * Both of these usually happen while the app is closed, so the notification is the half that
     * actually reaches the user. If the OS is dropping notifications, the banner has to say so.
     *
     * Deliberately not [isCritical]: that means "nothing is being blocked", which is false for
     * [FirewallHealth.StopFailed] - and gating the warning on it left the one state whose
     * notification is the durable record as the one state that never mentioned notifications.
     */
    fun reliesOnNotification(health: FirewallHealth): Boolean =
        health is FirewallHealth.Down || health is FirewallHealth.StopFailed
}
