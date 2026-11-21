package com.swordfish.lemuroid.app.shared.settings

import android.content.Context
import com.swordfish.lemuroid.app.shared.systems.DisabledSystemsPreferences
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.storage.network.NetworkRomSyncer
import com.swordfish.lemuroid.lib.storage.network.NetworkStoragePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NetworkRomSyncManager(
    context: Context,
    private val lemuroidLibrary: LemuroidLibrary
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncer = NetworkRomSyncer(appContext)
    private var syncJob: Job? = null

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState

    val isRunning: Boolean
        @Synchronized get() = syncJob?.isActive == true

    @Synchronized
    fun start() {
        if (isRunning) return
        mutableState.value = State.Running(NetworkRomSyncer.Progress(NetworkRomSyncer.Stage.SCANNING))
        syncJob = scope.launch {
            try {
                val disabledSystems = DisabledSystemsPreferences.disabledSystemIds(appContext)
                val result = syncer.sync(disabledSystems) { progress ->
                    mutableState.value = State.Running(progress)
                }
                mutableState.value = State.Running(
                    NetworkRomSyncer.Progress(NetworkRomSyncer.Stage.INDEXING)
                )
                // Local copies are now complete: index them only, so network entries do not
                // show up as duplicates alongside their synced counterparts.
                NetworkStoragePreferences.markSyncCompleted(appContext)
                lemuroidLibrary.indexLibrary()
                mutableState.value = State.Success(result)
            } catch (_: CancellationException) {
                mutableState.value = State.Cancelled
            } catch (error: Throwable) {
                mutableState.value = State.Error(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    @Synchronized
    fun cancel() {
        if (!isRunning) return
        syncJob?.cancel()
        mutableState.value = State.Cancelling
    }

    sealed class State {
        object Idle : State()
        data class Running(val progress: NetworkRomSyncer.Progress) : State()
        object Cancelling : State()
        data class Success(val result: NetworkRomSyncer.Result) : State()
        object Cancelled : State()
        data class Error(val message: String) : State()
    }
}
