package com.swordfish.lemuroid.app.tv.settings

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.InputDevice
import android.view.View
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.library.PendingOperationsMonitor
import com.swordfish.lemuroid.app.shared.settings.AdvancedSettingsPreferences
import com.swordfish.lemuroid.app.shared.settings.BiosPreferences
import com.swordfish.lemuroid.app.shared.settings.CoresSelectionPreferences
import com.swordfish.lemuroid.app.shared.settings.GamePadPreferencesHelper
import com.swordfish.lemuroid.app.shared.settings.SaveSyncPreferences
import com.swordfish.lemuroid.app.shared.settings.SettingsInteractor
import com.swordfish.lemuroid.app.shared.systems.DisabledSystemsPreferences
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.kotlin.NTuple2
import com.swordfish.lemuroid.lib.library.MetaSystemID
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.savesync.SaveSyncManager
import com.swordfish.lemuroid.lib.storage.network.NetworkClientFactory
import com.swordfish.lemuroid.lib.storage.network.NetworkStoragePreferences
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

class TVSettingsFragment : LeanbackPreferenceFragmentCompat() {

    @Inject
    lateinit var settingsInteractor: SettingsInteractor

    @Inject
    lateinit var biosPreferences: BiosPreferences

    @Inject
    lateinit var gamePadPreferencesHelper: GamePadPreferencesHelper

    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    @Inject
    lateinit var coresSelectionPreferences: CoresSelectionPreferences

    @Inject
    lateinit var saveSyncManager: SaveSyncManager

    lateinit var saveSyncPreferences: SaveSyncPreferences

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)

        saveSyncPreferences = SaveSyncPreferences(saveSyncManager)

        super.onAttach(context)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        launchOnState(Lifecycle.State.CREATED) {
            val gamePadStatus = combine(
                inputDeviceManager.getGamePadsObservable(),
                inputDeviceManager.getEnabledInputsObservable(),
                ::NTuple2
            )

            gamePadStatus
                .distinctUntilChanged()
                .collect { (pads, enabledPads) -> addGamePadBindingsScreen(pads, enabledPads) }
        }

        launchOnState(Lifecycle.State.RESUMED) {
            inputDeviceManager.getEnabledInputsObservable()
                .distinctUntilChanged()
                .collect { refreshGamePadBindingsScreen(it) }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore =
            SharedPreferencesHelper.getSharedPreferencesDataStore(requireContext())
        setPreferencesFromResource(R.xml.tv_settings, rootKey)

        getCoresSelectionScreen()?.let {
            coresSelectionPreferences.addCoresSelectionPreferences(it)
        }

        getBiosInfoPreferenceScreen()?.let {
            biosPreferences.addBiosPreferences(it)
        }

        getAdvancedSettingsPreferenceScreen()?.let {
            AdvancedSettingsPreferences.updateCachePreferences(it)
        }

        getSaveSyncScreen()?.let {
            if (saveSyncManager.isSupported()) {
                saveSyncPreferences.addSaveSyncPreferences(it)
            }
            it.isVisible = saveSyncManager.isSupported()
        }
    }

    override fun onResume() {
        super.onResume()

        refreshSaveSyncScreen()

        getSaveSyncScreen()?.let { screen ->
            PendingOperationsMonitor(requireContext())
                .anySaveOperationInProgress()
                .observe(this) { syncInProgress ->
                    saveSyncPreferences.updatePreferences(screen, syncInProgress)
                }
        }
    }

    private fun getGamePadPreferenceScreen(): PreferenceScreen? {
        return findPreference(resources.getString(R.string.pref_key_open_gamepad_settings))
    }

    private fun getSaveSyncScreen(): PreferenceScreen? {
        return findPreference(resources.getString(R.string.pref_key_open_save_sync_settings))
    }

    private fun getCoresSelectionScreen(): PreferenceScreen? {
        return findPreference(resources.getString(R.string.pref_key_open_cores_selection))
    }

    private fun getBiosInfoPreferenceScreen(): PreferenceScreen? {
        return findPreference(resources.getString(R.string.pref_key_display_bios_info))
    }

    private fun getAdvancedSettingsPreferenceScreen(): PreferenceScreen? {
        return findPreference(resources.getString(R.string.pref_key_advanced_settings))
    }

    private fun addGamePadBindingsScreen(
        gamePads: List<InputDevice>,
        enabledGamePads: List<InputDevice>
    ) {
        lifecycleScope.launch {
            getGamePadPreferenceScreen()?.let {
                it.removeAll()
                gamePadPreferencesHelper.addGamePadsPreferencesToScreen(
                    requireActivity(),
                    it,
                    gamePads,
                    enabledGamePads
                )
            }
        }
    }

    private fun refreshGamePadBindingsScreen(enabledGamePads: List<InputDevice>) {
        lifecycleScope.launch {
            getGamePadPreferenceScreen()?.let {
                gamePadPreferencesHelper.refreshGamePadsPreferencesToScreen(it, enabledGamePads)
            }
        }
    }

    private fun refreshSaveSyncScreen() {
        getSaveSyncScreen()?.let {
            saveSyncPreferences.updatePreferences(it, false)
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (saveSyncPreferences.onPreferenceTreeClick(activity, preference)) {
            return true
        }

        when (preference.key) {
            getString(R.string.pref_key_reset_gamepad_bindings) -> lifecycleScope.launch {
                handleResetGamePadBindings()
            }
            getString(R.string.pref_key_reset_settings) -> handleResetSettings()
            getString(R.string.pref_key_network_storage_test_connection) -> handleNetworkTestConnection()
            getString(R.string.pref_key_network_storage_clear_cache) -> handleNetworkClearCache()
            getString(R.string.pref_key_disabled_systems) -> showDisabledSystemsDialog()
            getString(R.string.pref_key_donate) -> showDonateDialog()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun handleNetworkTestConnection() {
        val context = requireContext()
        val config = NetworkStoragePreferences.load(context)
        if (config.hostname.isBlank()) {
            Toast.makeText(context, R.string.network_storage_test_failed, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = NetworkClientFactory.create(config)
                    client.use { it.testConnection() }
                }
            }
            val detail = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            val message = when {
                result.getOrDefault(false) -> context.getString(R.string.network_storage_test_success)
                detail != null -> context.getString(R.string.network_storage_test_failed_detail, detail)
                else -> context.getString(R.string.network_storage_test_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDonateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_donate, null)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.donate_dialog_title)
            .setMessage(R.string.donate_dialog_message)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Checked = enabled: unchecking a system disables it (same as the long press menu). */
    private fun showDisabledSystemsDialog() {
        val metaSystems = MetaSystemID.values().filter { it != MetaSystemID.ALL }
        val names = metaSystems.map { getString(it.titleResId) }.toTypedArray()
        val disabled = DisabledSystemsPreferences.disabledSystemIds(requireContext())
        val checked = BooleanArray(metaSystems.size) { index ->
            metaSystems[index].systemIDs.none { it.dbname in disabled }
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_title_disabled_systems)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                DisabledSystemsPreferences.setMetaSystemDisabled(
                    requireContext(),
                    metaSystems[which],
                    !isChecked
                )
            }
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun handleNetworkClearCache() {
        NetworkStoragePreferences.clearCache(requireContext())
        Toast.makeText(
            requireContext(),
            R.string.network_storage_cache_cleared,
            Toast.LENGTH_SHORT
        ).show()
    }

    private suspend fun handleResetGamePadBindings() {
        inputDeviceManager.resetAllBindings()
        refreshGamePadBindingsScreen(inputDeviceManager.getGamePadsObservable().first())
    }

    private fun handleResetSettings() {
        settingsInteractor.resetAllSettings()
        activity?.finish()
    }

    @dagger.Module
    class Module
}
