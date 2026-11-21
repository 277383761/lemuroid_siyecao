package com.swordfish.lemuroid.app.shared.settings

import android.content.Context
import com.swordfish.lemuroid.lib.library.GameSystem
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
import kotlinx.coroutines.withContext

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
                val excludedSystems = NetworkStoragePreferences.loadExcludedSystemIds(appContext)
                val result = syncer.sync(excludedSystems) { progress ->
                    mutableState.value = State.Running(progress)
                }
                mutableState.value = State.Running(
                    NetworkRomSyncer.Progress(NetworkRomSyncer.Stage.INDEXING)
                )
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

    /** Scans the remote directory and reports the systems it contains (null when unreachable). */
    fun loadAvailableSystems(onResult: (List<GameSystem>?) -> Unit) {
        scope.launch {
            val systems = runCatching { syncer.listAvailableSystems() }.getOrNull()
            withContext(Dispatchers.Main) { onResult(systems) }
        }
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
