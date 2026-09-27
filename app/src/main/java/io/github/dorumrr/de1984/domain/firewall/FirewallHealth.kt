package io.github.dorumrr.de1984.domain.firewall

/**
 * Whether the firewall is actually enforcing rules, in a form the UI can render.
 *
 * This is deliberately typed rather than a message string. The app ships seven locales, so the
 * data layer must not decide the wording - it reports what happened and the UI translates it.
 *
 * Distinct from `FirewallManager.FirewallState`, which tracks the backend lifecycle
 * (Stopped/Starting/Running/Error). Two states can be Error for very different reasons; this type
 * carries the reason, and therefore what the user can do about it.
 */
sealed interface FirewallHealth {

    data object Healthy : FirewallHealth

    /**
     * The firewall is not enforcing anything. Every app has full network access right now.
     *
     * The user always wanted it on in this state - a deliberate stop reports [Healthy] instead.
     */
    data class Down(
        val reason: Reason,
        val backend: FirewallBackendType?
    ) : FirewallHealth {

        enum class Reason {

            NO_FALLBACK_PLAN,

            FALLBACK_FAILED,

            VPN_CONFLICT,

            VPN_PERMISSION_REQUIRED,

            /**
             * A start attempt failed outright, so protection was never obtained.
             *
             * Covers boot restore, a backend switch from Settings, and the toggle itself.
             */
            START_FAILED,
        }
    }

    /**
     * Rules are still being enforced, but through VPN after the preferred backend failed.
     *
     * Protection is intact, so this is informational.
     *
     * It persists until the firewall is stopped or restarted. The only path that sets it,
     * FirewallManager.startVpnFallback, stops health monitoring and never restarts it, so there is
     * no "next successful check" to clear it.
     */
    data class SwitchedToVpn(
        val failedBackend: FirewallBackendType,
        val fromManualMode: Boolean
    ) : FirewallHealth

    /**
     * The user asked to stop the firewall and the running backend refused to tear down.
     *
     * The mirror image of [Down]: there, the user wants blocking and is getting none; here, the user
     * wants none and may still be getting some. Apps can be offline with every control showing OFF,
     * which is why this needs to be said out loud rather than logged.
     *
     * Not critical - nothing is unprotected - so it is styled as a warning, not as FIREWALL DOWN.
     */
    data class StopFailed(
        val backend: FirewallBackendType?
    ) : FirewallHealth

    /** The backend runs, but its last pass left some rules unwritten, so some rows show a rule not in force. */
    data class ApplyFailed(
        val backend: FirewallBackendType
    ) : FirewallHealth
}

/** An apply pass that wrote every rule it could but failed on [failedCount] of them. */
class PartialApplyException(val failedCount: Int) :
    Exception("$failedCount rule(s) could not be applied")
