package com.swordfish.lemuroid.app.shared.settings

import android.app.Dialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.textfield.TextInputLayout
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.storage.network.NetworkProtocol
import com.swordfish.lemuroid.lib.storage.network.NetworkStoragePreferences

class NetworkStorageConfigDialogFragment : DialogFragment() {

    private lateinit var fields: List<EditText>
    private lateinit var protocolGroup: RadioGroup
    private lateinit var hostLayout: TextInputLayout
    private lateinit var portLayout: TextInputLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val config = NetworkStoragePreferences.load(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_network_storage_config, null)
        protocolGroup = view.findViewById(R.id.network_protocol_group)
        hostLayout = view.findViewById(R.id.network_host_layout)
        portLayout = view.findViewById(R.id.network_port_layout)
        fields = listOf(
            view.findViewById(R.id.network_host_input),
            view.findViewById(R.id.network_port_input),
            view.findViewById(R.id.network_username_input),
            view.findViewById(R.id.network_password_input),
            view.findViewById(R.id.network_remote_path_input)
        )

        protocolGroup.check(
            when (config.protocol) {
                NetworkProtocol.SMB -> R.id.network_protocol_smb
                NetworkProtocol.FTP -> R.id.network_protocol_ftp
                NetworkProtocol.WEBDAV -> R.id.network_protocol_webdav
            }
        )
        val values = listOf(
            config.host,
            config.port.takeIf { it > 0 }?.toString() ?: "0",
            config.username,
            config.password,
            config.remotePath
        )
        fields.zip(values).forEach { (field, value) ->
            field.setText(value)
            field.setSelection(value.length)
        }
        configureSequentialInput()

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.network_storage_config_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.network_storage_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (saveConfig()) dismiss()
        }
        dialog?.window?.let { window ->
            val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.90f).toInt()
            window.setLayout(width, height)
        }
    }

    private fun configureSequentialInput() {
        fields.forEachIndexed { index, field ->
            val advance: () -> Unit = {
                if (index < fields.lastIndex) {
                    fields[index + 1].requestFocus()
                    fields[index + 1].setSelection(fields[index + 1].text?.length ?: 0)
                } else if (saveConfig()) {
                    dismiss()
                }
            }
            field.setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                val expectedAction = if (index == fields.lastIndex) {
                    EditorInfo.IME_ACTION_DONE
                } else {
                    EditorInfo.IME_ACTION_NEXT
                }
                if (actionId == expectedAction || enterPressed) {
                    advance()
                    true
                } else {
                    false
                }
            }
            field.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BUTTON_START && event.action == KeyEvent.ACTION_DOWN) {
                    advance()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun saveConfig(): Boolean {
        val host = fields[0].text.toString().trim()
        if (host.isEmpty()) {
            hostLayout.error = getString(R.string.network_storage_host_required)
            fields[0].requestFocus()
            return false
        }
        hostLayout.error = null

        val port = fields[1].text.toString().trim().toIntOrNull()
        if (port == null || port !in 0..65535) {
            portLayout.error = getString(R.string.network_storage_port_invalid)
            fields[1].requestFocus()
            return false
        }
        portLayout.error = null

        val protocol = when (protocolGroup.checkedRadioButtonId) {
            R.id.network_protocol_ftp -> NetworkProtocol.FTP
            R.id.network_protocol_webdav -> NetworkProtocol.WEBDAV
            else -> NetworkProtocol.SMB
        }
        val current = NetworkStoragePreferences.load(requireContext())
        NetworkStoragePreferences.save(
            requireContext().applicationContext,
            current.copy(
                protocol = protocol,
                host = host,
                port = port,
                username = fields[2].text.toString().trim(),
                password = fields[3].text.toString(),
                remotePath = fields[4].text.toString().trim()
            )
        )
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle.EMPTY)
        Toast.makeText(requireContext(), R.string.network_storage_saved, Toast.LENGTH_SHORT).show()
        return true
    }

    companion object {
        const val RESULT_KEY = "network_storage_config_saved"
    }
}
