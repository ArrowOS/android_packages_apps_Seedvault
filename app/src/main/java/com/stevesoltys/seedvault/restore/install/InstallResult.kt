package com.stevesoltys.seedvault.restore.install

import android.graphics.drawable.Drawable
import com.stevesoltys.seedvault.restore.install.ApkInstallState.FAILED
import com.stevesoltys.seedvault.restore.install.ApkInstallState.IN_PROGRESS
import com.stevesoltys.seedvault.restore.install.ApkInstallState.QUEUED
import java.util.concurrent.ConcurrentHashMap

internal interface InstallResult {
    /**
     * The number of packages already processed.
     */
    val progress: Int

    /**
     * The total number of packages to be considered for re-install.
     */
    val total: Int

    /**
     * Is true, if there is no packages to install and false otherwise.
     */
    val isEmpty: Boolean

    /**
     * Is true, if the installation is finished, either because all packages were processed
     * or because an unexpected error happened along the way.
     * Is false, if the installation is still ongoing.
     */
    val isFinished: Boolean

    /**
     * Is true when one or more packages failed to install.
     */
    val hasFailed: Boolean

    /**
     * Get all [ApkInstallResult]s that are not in state [QUEUED].
     */
    fun getNotQueued(): Collection<ApkInstallResult>

    /**
     * Set the set of all [ApkInstallResult]s that are still [QUEUED] to [FAILED].
     * This is useful after [isFinished] is true due to an error
     * and we need to treat all packages as failed that haven't been processed.
     */
    fun queuedToFailed()
}

internal class MutableInstallResult(override val total: Int) : InstallResult {

    private val installResults = ConcurrentHashMap<String, ApkInstallResult>(total)
    override val isEmpty get() = installResults.isEmpty()

    @Volatile
    override var isFinished = false
    override val progress
        get() = installResults.count {
            val state = it.value.state
            state != QUEUED && state != IN_PROGRESS
        }
    override val hasFailed get() = installResults.any { it.value.state == FAILED }

    override fun getNotQueued(): Collection<ApkInstallResult> {
        return installResults.filterValues { result -> result.state != QUEUED }.values
    }

    override fun queuedToFailed() {
        installResults.forEach { entry ->
            val result = entry.value
            if (result.state == QUEUED) installResults[entry.key] = result.copy(state = FAILED)
        }
    }

    operator fun get(packageName: String) = installResults[packageName]

    operator fun set(packageName: String, installResult: ApkInstallResult) {
        installResults[packageName] = installResult
        check(installResults.size <= total) { "Attempting to add more packages than total" }
    }

    fun update(
        packageName: String,
        updateFun: (ApkInstallResult) -> ApkInstallResult
    ): MutableInstallResult {
        val result = get(packageName)
        check(result != null) { "ApkRestoreResult for $packageName does not exist." }
        installResults[packageName] = updateFun(result)
        return this
    }

}

data class ApkInstallResult(
    val packageName: CharSequence,
    val progress: Int,
    val state: ApkInstallState,
    val name: CharSequence? = null,
    val icon: Drawable? = null,
    val installerPackageName: CharSequence? = null
) : Comparable<ApkInstallResult> {
    override fun compareTo(other: ApkInstallResult): Int {
        return other.progress.compareTo(progress)
    }
}

internal class FailedFirstComparator : Comparator<ApkInstallResult> {
    override fun compare(a1: ApkInstallResult, a2: ApkInstallResult): Int {
        return (if (a1.state == FAILED && a2.state != FAILED) -1
        else if (a2.state == FAILED && a1.state != FAILED) 1
        else a1.compareTo(a2))
    }
}

enum class ApkInstallState {
    QUEUED,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED
}