package com.artemchep.keyguard.common

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.UpdateVersionLog
import com.artemchep.keyguard.platform.lifecycle.LeLifecycleState
import com.artemchep.keyguard.platform.lifecycle.onState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.kodein.di.DirectDI
import org.kodein.di.direct
import org.kodein.di.instance

class AppWorkerIm(
    private val getVaultSession: GetVaultSession,
    private val updateVersionLog: UpdateVersionLog,
) : AppWorker {
    constructor(directDI: DirectDI) : this(
        getVaultSession = directDI.instance(),
        updateVersionLog = directDI.instance(),
    )

    override fun launch(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ): Job = scope.launch {
        // The app should maintain the sync status
        // while it is visible to a user.
        flow
            .onState(LeLifecycleState.STARTED) {
                launchSyncManagerWhenAvailable(this)
            }
            .launchIn(this)
        // The app should keep a log of last installed versions,
        // so we can show a nice changelog to a user.
        flow
            .onState(LeLifecycleState.STARTED) {
                updateVersionLog()
                    .attempt()
                    .launchIn(this)
            }
            .take(1) // no need to restart, the version won't change
            .launchIn(this)
    }

    private fun launchSyncManagerWhenAvailable(scope: CoroutineScope) = getVaultSession()
        .map { session ->
            val key = session as? MasterSession.Key
            key?.di?.direct?.instance<NotificationsWorker>()
        }
        .distinctUntilChanged { old, new -> old === new }
        .mapLatest { syncManager ->
            if (syncManager == null) {
                return@mapLatest
            }

            // Launch the sync manager forever until the
            // sync manager changes.
            coroutineScope {
                syncManager.launch(this)
            }
        }
        .launchIn(scope)
}

interface AppWorker {
    enum class Feature {
        SYNC,
    }

    fun launch(
        scope: CoroutineScope,
        flow: Flow<LeLifecycleState>,
    ): Job
}
