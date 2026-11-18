package com.swordfish.lemuroid.app.mobile.feature.systems

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.swordfish.lemuroid.app.shared.systems.DisabledSystemsPreferences
import com.swordfish.lemuroid.app.shared.systems.MetaSystemInfo
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.metaSystemID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

class MetaSystemsViewModel(
    retrogradeDb: RetrogradeDatabase,
    private val appContext: Context
) : ViewModel() {

    class Factory(
        val retrogradeDb: RetrogradeDatabase,
        val appContext: Context
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MetaSystemsViewModel(retrogradeDb, appContext) as T
        }
    }

    private val disabledSystems = MutableStateFlow(
        DisabledSystemsPreferences.disabledSystemIds(appContext)
    )

    val availableMetaSystems: Flow<List<MetaSystemInfo>> = combine(
        retrogradeDb.gameDao().selectSystemsWithCount(),
        disabledSystems
    ) { systemCounts, disabled ->
        val metaSystems = systemCounts.asSequence()
            .filter { (_, count) -> count > 0 }
            .map { (systemId, count) -> GameSystem.findById(systemId).metaSystemID() to count }
            .groupBy { (metaSystemId, _) -> metaSystemId }
            .map { (metaSystemId, counts) ->
                val isDisabled = metaSystemId.systemIDs.all { it.dbname in disabled }
                MetaSystemInfo(metaSystemId, counts.sumOf { it.second }, isDisabled)
            }
            .sortedBy { it.getName(appContext) }
            .toList()

        // Disabled systems stay in the grid but their games are excluded from "All games".
        val visibleCount = metaSystems.filter { !it.isDisabled }.sumOf { it.count }
        listOf(MetaSystemInfo(MetaSystemID.ALL, visibleCount)) + metaSystems
    }

    /** Re-reads the disabled systems after the user hides one from the system grid. */
    fun refreshDisabledSystems() {
        disabledSystems.value = DisabledSystemsPreferences.disabledSystemIds(appContext)
    }
}
