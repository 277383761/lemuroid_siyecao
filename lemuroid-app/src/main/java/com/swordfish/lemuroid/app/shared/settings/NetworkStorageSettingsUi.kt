package com.swordfish.lemuroid.app.shared.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.lib.storage.network.NetworkRomSyncer
import com.swordfish.lemuroid.lib.storage.network.NetworkStoragePreferences

object NetworkStorageSettingsUi {

    fun initialize(fragment: PreferenceFragmentCompat, syncManager: NetworkRomSyncManager) {
        val context = fragment.requireContext()
        val syncEnabledPreference = fragment.findPreference<TwoStatePreference>(
            context.getString(R.string.pref_key_network_storage_sync_enabled)
        )
        val storagePermissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        fun hasStoragePermissions(): Boolean {
            return Build.VERSION.SDK_INT > Build.VERSION_CODES.Q || storagePermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        val permissionLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (storagePermissions.all { grants[it] == true }) {
                syncEnabledPreference?.isChecked = true
                syncManager.start()
                Toast.makeText(context, R.string.network_storage_sync_started, Toast.LENGTH_SHORT).show()
            } else {
                syncEnabledPreference?.isChecked = false
                Toast.makeText(
                    context,
                    R.string.network_storage_sync_permission_required,
                    Toast.LENGTH_LONG
                ).show()
            }
            fragment.view?.post { updateSyncState(fragment, syncManager.state.value) }
        }
        fun startSync() {
            if (syncManager.isRunning) return
            if (hasStoragePermissions()) {
                syncManager.start()
                Toast.makeText(context, R.string.network_storage_sync_started, Toast.LENGTH_SHORT).show()
            } else {
                syncEnabledPreference?.isChecked = false
                permissionLauncher.launch(storagePermissions)
            }
        }

        fragment.childFragmentManager.setFragmentResultListener(
            NetworkStorageConfigDialogFragment.RESULT_KEY,
            fragment.viewLifecycleOwner
        ) { _, _ ->
            refreshConnectionSummary(fragment)
            updateSyncState(fragment, syncManager.state.value)
        }

        fragment.findPreference<Preference>(
            context.getString(R.string.pref_key_network_storage_configure)
        )?.setOnPreferenceClickListener {
            NetworkStorageConfigDialogFragment().show(
                fragment.childFragmentManager,
                NetworkStorageConfigDialogFragment::class.java.name
            )
            true
        }

        syncEnabledPreference?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) startSync() else syncManager.cancel()
            fragment.view?.post { updateSyncState(fragment, syncManager.state.value) }
            true
        }
        fragment.findPreference<TwoStatePreference>(
            context.getString(R.string.pref_key_network_storage_enabled)
        )?.setOnPreferenceChangeListener { _, _ ->
            fragment.view?.post { updateSyncState(fragment, syncManager.state.value) }
            true
        }

        fragment.findPreference<Preference>(
            context.getString(R.string.pref_key_network_storage_sync_now)
        )?.setOnPreferenceClickListener {
            startSync()
            true
        }

        fragment.findPreference<Preference>(
            context.getString(R.string.pref_key_network_storage_sync_cancel)
        )?.setOnPreferenceClickListener {
            syncManager.cancel()
            true
        }

        refreshConnectionSummary(fragment)
        fragment.launchOnState(Lifecycle.State.RESUMED) {
            syncManager.state.collect { state -> updateSyncState(fragment, state) }
        }
    }

    fun refreshConnectionSummary(fragment: PreferenceFragmentCompat) {
        val context = fragment.context ?: return
        val preference = fragment.findPreference<Preference>(
            context.getString(R.string.pref_key_network_storage_configure)
        ) ?: return
        val config = NetworkStoragePreferences.load(context)
        preference.summary = if (config.hostname.isBlank()) {
            context.getString(R.string.network_storage_config_not_set)
        } else {
            context.getString(
                R.string.network_storage_config_summary,
                config.protocol.name,
                config.authority,
                config.remotePath.ifBlank { context.getString(R.string.network_storage_root_path) }
            )
        }
    }

    private fun updateSyncState(
        fragment: PreferenceFragmentCompat,
        state: NetworkRomSyncManager.State
    ) {
        val context = fragment.context ?: return
        val syncNow = fragment.findPreference<Preference>(
            context.getString(R.string.pref_key_network_storage_sync_now)
        )
        val cancel = fragment.findPreference<Preference>(
            context.getString(R.string.pref_key_network_storage_sync_cancel)
        )
        val enabled = fragment.findPreference<TwoStatePreference>(
            context.getString(R.string.pref_key_network_storage_sync_enabled)
        )

        val running = state is NetworkRomSyncManager.State.Running
        val cancelling = state is NetworkRomSyncManager.State.Cancelling
        val syncAllowed = enabled?.isChecked == true &&
            NetworkStoragePreferences.load(context).enabled
        syncNow?.isEnabled = syncAllowed && !running && !cancelling
        cancel?.isVisible = running || cancelling
        cancel?.isEnabled = running

        syncNow?.summary = when (state) {
            is NetworkRomSyncManager.State.Idle,
            is NetworkRomSyncManager.State.Cancelling -> {
                if (cancelling) {
                    context.getString(R.string.network_storage_sync_cancelling)
                } else {
                    context.getString(R.string.network_storage_sync_idle)
                }
            }
            is NetworkRomSyncManager.State.Running -> formatProgress(context, state.progress)
            is NetworkRomSyncManager.State.Success -> context.getString(
                R.string.network_storage_sync_success,
                state.result.downloadedFiles,
                state.result.skippedFiles,
                state.result.deletedFiles
            )
            is NetworkRomSyncManager.State.Cancelled ->
                context.getString(R.string.network_storage_sync_cancelled)
            is NetworkRomSyncManager.State.Error ->
                context.getString(R.string.network_storage_sync_error, state.message)
        }
    }

    private fun formatProgress(context: android.content.Context, progress: NetworkRomSyncer.Progress): String {
        return when (progress.stage) {
            NetworkRomSyncer.Stage.SCANNING ->
                context.getString(R.string.network_storage_sync_scanning)
            NetworkRomSyncer.Stage.DOWNLOADING -> context.getString(
                R.string.network_storage_sync_downloading,
                progress.currentFile,
                progress.processedFiles,
                progress.totalFiles,
                NetworkRomSyncer.formatBytes(progress.downloadedBytes),
                NetworkRomSyncer.formatBytes(progress.totalBytes)
            )
            NetworkRomSyncer.Stage.INDEXING ->
                context.getString(R.string.network_storage_sync_indexing)
        }
    }
}
