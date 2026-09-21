package com.espitman.sdm.notification

/**
 * Restorable state handoff from a user Download tap through the
 * notification permission result. State transitions are idempotent;
 * callers submit when they observe [Phase.Submitting]. Cancellation
 * does not consume, so a later composition may call submit again.
 *
 * Restorable as two strings ([savedPhase], [savedPendingUrl]) for
 * rememberSaveable / process recreation. This type never starts a transfer:
 * callers observe transitions and perform submission themselves.
 *
 * Waiting stays waiting across recreation, so a missing permission result
 * cannot be mistaken for readiness. The pending URL is retained through
 * [Phase.Submitting] and cleared only at [Phase.Consumed].
 */
internal data class NotificationPermissionHandoff(
    val phase: Phase,
    val pendingUrl: String?,
) {
    enum class Phase(val savedName: String) {
        WaitingForPermission("waiting"),
        ReadyToSubmit("ready"),
        Submitting("submitting"),
        Consumed("consumed"),
        ;

        companion object {
            fun fromSaved(savedName: String): Phase =
                entries.firstOrNull { it.savedName == savedName }
                    ?: throw IllegalArgumentException("Unknown handoff phase: $savedName")
        }
    }

    init {
        if (phase == Phase.Consumed) {
            require(pendingUrl == null) { "Consumed handoff has no pending URL" }
        } else {
            require(!pendingUrl.isNullOrBlank()) { "In-flight handoff requires a pending URL" }
        }
    }

    val savedPhase: String get() = phase.savedName

    /** Empty when [phase] is [Phase.Consumed]. */
    val savedPendingUrl: String get() = pendingUrl.orEmpty()

    /**
     * Starts a handoff from [Phase.Consumed]. Duplicate taps while a handoff
     * is already in flight return this state unchanged.
     */
    fun onDownloadTap(url: String, awaitPermission: Boolean): NotificationPermissionHandoff {
        if (phase != Phase.Consumed) return this
        return if (awaitPermission) awaitingPermission(url) else readyToSubmit(url)
    }

    /** Grant and deny are the same outcome: the download may proceed once. */
    fun onSystemResult(): NotificationPermissionHandoff = becomeReady()

    /** Prompt launch threw or was unavailable. A later system result is ignored. */
    fun onLaunchFailed(): NotificationPermissionHandoff = becomeReady()

    /** Moves [Phase.ReadyToSubmit] to [Phase.Submitting] once, keeping the URL. */
    fun markSubmitting(): NotificationPermissionHandoff {
        if (phase != Phase.ReadyToSubmit) return this
        return copy(phase = Phase.Submitting)
    }

    /** Terminal state after submission finishes. Further results and taps are inert until a new tap. */
    fun consume(): NotificationPermissionHandoff {
        if (phase != Phase.Submitting) return this
        return consumed()
    }

    private fun becomeReady(): NotificationPermissionHandoff {
        if (phase != Phase.WaitingForPermission) return this
        return copy(phase = Phase.ReadyToSubmit)
    }

    companion object {
        fun consumed(): NotificationPermissionHandoff =
            NotificationPermissionHandoff(Phase.Consumed, null)

        fun awaitingPermission(url: String): NotificationPermissionHandoff =
            NotificationPermissionHandoff(Phase.WaitingForPermission, url)

        fun readyToSubmit(url: String): NotificationPermissionHandoff =
            NotificationPermissionHandoff(Phase.ReadyToSubmit, url)

        fun restore(savedPhase: String, savedPendingUrl: String): NotificationPermissionHandoff =
            NotificationPermissionHandoff(
                phase = Phase.fromSaved(savedPhase),
                pendingUrl = savedPendingUrl.ifEmpty { null },
            )
    }
}
