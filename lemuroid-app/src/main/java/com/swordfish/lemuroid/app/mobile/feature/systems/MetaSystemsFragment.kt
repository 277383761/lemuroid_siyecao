package com.swordfish.lemuroid.app.mobile.feature.systems

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.DynamicGridLayoutManager
import com.swordfish.lemuroid.app.mobile.shared.GridSpaceDecoration
import com.swordfish.lemuroid.app.mobile.shared.RecyclerViewFragment
import com.swordfish.lemuroid.app.shared.systems.DisabledSystemsPreferences
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import javax.inject.Inject

class MetaSystemsFragment : RecyclerViewFragment() {

    @Inject
    lateinit var retrogradeDb: RetrogradeDatabase

    private var metaSystemsAdapter: MetaSystemsAdapter? = null

    private lateinit var metaSystemsViewModel: MetaSystemsViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val factory = MetaSystemsViewModel.Factory(retrogradeDb, requireContext().applicationContext)
        metaSystemsViewModel = ViewModelProvider(this, factory)[MetaSystemsViewModel::class.java]

        metaSystemsAdapter = MetaSystemsAdapter(
            onSystemClick = { navigateToGames(it) },
            onSystemLongClick = { showSystemMenu(it) }
        )

        launchOnState(Lifecycle.State.CREATED) {
            metaSystemsViewModel.availableMetaSystems.collect {
                metaSystemsAdapter?.submitList(it)
                emptyView?.isVisible = it.isEmpty()
            }
        }

        recyclerView?.apply {
            this.adapter = metaSystemsAdapter
            this.layoutManager = DynamicGridLayoutManager(context, 2)

            val spacingInPixels = resources.getDimensionPixelSize(R.dimen.grid_spacing)
            GridSpaceDecoration.setSingleGridSpaceDecoration(this, spacingInPixels)
        }
    }

    private fun navigateToGames(system: MetaSystemID) {
        if (DisabledSystemsPreferences.isMetaSystemDisabled(requireContext(), system)) {
            Toast.makeText(requireContext(), R.string.system_disabled_hint, Toast.LENGTH_SHORT).show()
            return
        }

        val enabledDbNames = DisabledSystemsPreferences.enabledDbNames(requireContext())
        val dbNames = system.systemIDs
            .filter { it.dbname in enabledDbNames }
            .map { it.dbname }
            .toTypedArray()
        if (dbNames.isEmpty()) return

        val action = MetaSystemsFragmentDirections.actionNavigationSystemsToNavigationGames(dbNames)
        findNavController().navigate(action)
    }

    /** Long press on a system tile: allows hiding/restoring systems that cannot run here. */
    private fun showSystemMenu(system: MetaSystemID) {
        if (system == MetaSystemID.ALL) return

        val isDisabled = DisabledSystemsPreferences.isMetaSystemDisabled(requireContext(), system)
        val action = if (isDisabled) R.string.system_enable_action else R.string.system_disable_action

        AlertDialog.Builder(requireContext())
            .setTitle(getString(system.titleResId))
            .setItems(arrayOf(getString(action))) { _, _ ->
                DisabledSystemsPreferences.setMetaSystemDisabled(requireContext(), system, !isDisabled)
                metaSystemsViewModel.refreshDisabledSystems()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @dagger.Module
    class Module
}
