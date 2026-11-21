package com.swordfish.lemuroid.app.mobile.feature.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.settings.NetworkRomSyncManager
import com.swordfish.lemuroid.app.shared.settings.NetworkStorageSettingsUi
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.network.NetworkClientFactory
import com.swordfish.lemuroid.lib.storage.network.NetworkStoragePreferences
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkStorageSettingsFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var syncManager: NetworkRomSyncManager

    override fun onAttach(context: Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore =
            SharedPreferencesHelper.getSharedPreferencesDataStore(requireContext())
        setPreferencesFromResource(R.xml.network_storage_settings, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        NetworkStorageSettingsUi.initialize(this, syncManager)
    }

    override fun onResume() {
        super.onResume()
        NetworkStorageSettingsUi.refreshConnectionSummary(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            getString(R.string.pref_key_network_storage_test_connection) -> handleTestConnection()
            getString(R.string.pref_key_network_storage_clear_cache) -> handleClearCache()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun handleTestConnection() {
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
                result.getOrDefault(false) -> getString(R.string.network_storage_test_success)
                detail != null -> getString(R.string.network_storage_test_failed_detail, detail)
                else -> getString(R.string.network_storage_test_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleClearCache() {
        NetworkStoragePreferences.clearCache(requireContext())
        Toast.makeText(
            requireContext(),
            R.string.network_storage_cache_cleared,
            Toast.LENGTH_SHORT
        ).show()
    }

    @dagger.Module
    class Module
}
