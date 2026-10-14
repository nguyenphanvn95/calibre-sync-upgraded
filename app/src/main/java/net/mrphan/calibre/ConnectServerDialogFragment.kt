package net.mrphan.calibre

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chuyen tu Java sang Kotlin trong lan refactor bo AsyncTask (xem upgrade_1.md muc 3) -- ly do
 * duy nhat can chuyen ngon ngu o day la de dung duoc `viewLifecycleOwner.lifecycleScope` +
 * suspend function thay cho AsyncIPResolver (AsyncTask). Java co the goi ham Kotlin, nhung
 * khong the "launch" mot coroutine gon nhu the tu phia Java -- se phai boc qua callback tho, mat
 * het loi ich. Toan bo logic/hanh vi giu nguyen 1-1 so voi ban Java cu, chi doi co che chay nen.
 */
class ConnectServerDialogFragment : DialogFragment() {

    interface ConnectServerDialogListener {
        fun onConnectClick(ci: ConnectionInfo)
    }

    private lateinit var etHost: EditText
    private lateinit var etPassword: EditText
    private lateinit var etWirelessDevicePort: EditText
    private lateinit var etContentServerPort: EditText
    private lateinit var etCsUsername: EditText
    private lateinit var etCsPassword: EditText
    private lateinit var cbAutoConnect: MaterialSwitch
    private lateinit var swRememberPassword: MaterialSwitch
    private lateinit var pbConnect: ProgressBar
    private lateinit var bConnect: MaterialButton
    private lateinit var mCsCredentialStore: CsCredentialStore

    private var ci: ConnectionInfo? = null
    private var connectAfterResolve = false
    private var invalidPort = false
    private var mListener: ConnectServerDialogListener? = null

    companion object {
        // Giu dung hanh vi ban Java cu: mSettings la field static, dung chung giua cac lan hien
        // thi dialog (khong phai loi trong lan refactor nay, va sua no khong thuoc pham vi cua
        // thay doi "bo AsyncTask" -- xem upgrade_1.md muc 3, chi dong AsyncIPResolver).
        private var mSettings: SharedPreferences? = null
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onResume() {
        super.onResume()
        val args = arguments
        if (args != null) {
            etHost.setText(args.getString("host"))
            etWirelessDevicePort.setText(args.getString("wd_port"))
            etContentServerPort.setText(args.getString("cs_port"))
            etHost.isEnabled = false
            etWirelessDevicePort.isEnabled = false
            etContentServerPort.isEnabled = false
            connectAfterResolve = false
            resolveCI()
            etPassword.requestFocus()
        } else {
            etHost.requestFocus()
        }
    }

    // Override the Fragment.onAttach() method to instantiate the NoticeDialogListener
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        // Verify that the host activity implements the callback interface
        mListener = activity as? ConnectServerDialogListener
            ?: throw ClassCastException("$activity must implement ConnectServerDialogListener")
    }

    private fun resolveCI() {
        invalidPort = false
        val host = etHost.text.toString()
        val wdPortText = etWirelessDevicePort.text.toString()
        val csPortText = etContentServerPort.text.toString()
        onBeforeResolve()
        // viewLifecycleOwner (khong phai `this`/Fragment lifecycle): DialogFragment's view co
        // the bi huy (vi du dialog bi dismiss) truoc ca chinh Fragment, va cac EditText/ProgressBar
        // ben duoi deu thuoc view do -- dung dung scope nay dam bao coroutine tu huy ngay khi
        // view bien mat, thay vi phai tu kiem tra `getDialog() == null` sau khi resolve xong nhu
        // ban AsyncTask cu (van giu lai check do o onAfterResolve() ben duoi de an toan kep).
        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                resolveConnectionInfoBlocking(host, wdPortText, csPortText)
            }
            onAfterResolve(resolved)
        }
    }

    /** Chay tren Dispatchers.IO. Tuong duong AsyncIPResolver.doInBackground() cu: neu `ci` da
     * duoc resolve tu truoc thi tra ve luon, khong lam lai. */
    private fun resolveConnectionInfoBlocking(
        host: String,
        wdPortText: String,
        csPortText: String
    ): ConnectionInfo? {
        if (ci == null) {
            try {
                val wdPort = wdPortText.toIntOrNull() ?: 0
                var csPort = csPortText.toIntOrNull() ?: 0
                // The Content Server port field (cs_port) is hidden in the Manual Connection
                // dialog (visibility="gone" in dialog_connect.xml), so its EditText is always
                // empty in that flow and would otherwise always fail validation below. Fall
                // back to Calibre's standard content server port (8080) when it's blank/
                // unparsable, instead of treating it the same as a genuinely invalid
                // wd_port entry.
                if (csPort <= 0) {
                    csPort = 8080
                }
                // A blank/unparsable Wireless Device port used to silently fall back to 0,
                // which let the dialog proceed straight to connect() with an invalid port and
                // fail with a confusing raw socket stack trace instead of a clear message here.
                if (wdPort <= 0) {
                    invalidPort = true
                    ci = null
                    return null
                }
                val resolvedCi = ConnectionInfo(host, wdPort, csPort)
                resolvedCi.resolveAddresses()
                ci = resolvedCi
            } catch (e: Exception) {
                ci = null
                return null
            }
        }
        return ci
    }

    private fun onBeforeResolve() {
        pbConnect.visibility = View.VISIBLE
        etHost.isEnabled = false
        etWirelessDevicePort.isEnabled = false
        etContentServerPort.isEnabled = false
        etPassword.isEnabled = false
        cbAutoConnect.isEnabled = false
        swRememberPassword.isEnabled = false
        bConnect.isEnabled = false
    }

    private fun onAfterResolve(resolvedCi: ConnectionInfo?) {
        if (connectAfterResolve && resolvedCi != null && mListener != null) {
            connectClick()
            return
        }
        if (dialog == null) {
            return
        }

        pbConnect.visibility = View.INVISIBLE

        if (resolvedCi == null) {
            etHost.isEnabled = true
            etWirelessDevicePort.isEnabled = true
            etContentServerPort.isEnabled = true
            Toast.makeText(
                requireActivity().applicationContext,
                if (invalidPort) R.string.connection_invalid_port else R.string.connection_unresolved_ip,
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val settings = mSettings
        if (settings != null && resolvedCi.toString() == settings.getString("pref_wd_address", "")) {
            val rememberPassword = settings.getBoolean("pref_remember_wd_password", true)
            swRememberPassword.isChecked = rememberPassword
            if (rememberPassword) {
                etPassword.setText(settings.getString("pref_password", ""))
            }
            cbAutoConnect.isChecked = settings.getBoolean("pref_autocon", false)
        }

        val csPort = resolvedCi.contentServerPort.toInt()
        val savedCsUsername = mCsCredentialStore.getUsername(resolvedCi.host, csPort)
        if (savedCsUsername != null) {
            etCsUsername.setText(savedCsUsername)
            etCsPassword.setText(mCsCredentialStore.getPassword(resolvedCi.host, csPort))
        }
        etPassword.isEnabled = true
        cbAutoConnect.isEnabled = true
        swRememberPassword.isEnabled = true
        bConnect.isEnabled = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        dialog?.setTitle(R.string.dialog_title_connection_settings)
        val v = inflater.inflate(R.layout.dialog_connect, container)
        mSettings = PreferenceManager.getDefaultSharedPreferences(requireActivity().applicationContext)
        mCsCredentialStore = CsCredentialStore(requireActivity().applicationContext)

        etHost = v.findViewById(R.id.host)
        etPassword = v.findViewById(R.id.password)
        etWirelessDevicePort = v.findViewById(R.id.wd_port)
        etContentServerPort = v.findViewById(R.id.cs_port)
        etCsUsername = v.findViewById(R.id.csd_username)
        etCsPassword = v.findViewById(R.id.csd_password)
        cbAutoConnect = v.findViewById(R.id.autocon)
        swRememberPassword = v.findViewById(R.id.remember_password)
        pbConnect = v.findViewById(R.id.connect_dlg_progress)
        pbConnect.visibility = View.INVISIBLE

        bConnect = v.findViewById(R.id.connect_server_connect)
        val cancelButton: MaterialButton = v.findViewById(R.id.connect_server_cancel)

        bConnect.setOnClickListener {
            if (mListener != null) {
                if (ci != null) {
                    connectClick()
                } else {
                    connectAfterResolve = true
                    resolveCI()
                }
            }
        }
        cancelButton.setOnClickListener {
            dialog?.dismiss()
        }
        return v
    }

    private fun connectClick() {
        val currentCi = ci ?: return
        currentCi.password = etPassword.text.toString()
        val csUsername = etCsUsername.text.toString().trim()
        val csPassword = etCsPassword.text.toString()
        currentCi.csUsername = csUsername.ifEmpty { null }
        currentCi.csPassword = if (csUsername.isEmpty()) null else csPassword
        mCsCredentialStore.save(
            currentCi.host,
            currentCi.contentServerPort.toInt(),
            currentCi.csUsername,
            currentCi.csPassword
        )
        val rememberPassword = swRememberPassword.isChecked
        mSettings?.edit()?.apply {
            putString("pref_wd_address", currentCi.toString())
            putString("pref_cs_url", currentCi.contentServerURL.toString())
            putString("pref_password", if (rememberPassword) currentCi.password else "")
            putBoolean("pref_remember_wd_password", rememberPassword)
            putBoolean("pref_autocon", cbAutoConnect.isChecked)
            commit()
        }
        dialog?.dismiss()
        mListener?.onConnectClick(currentCi)
    }
}
