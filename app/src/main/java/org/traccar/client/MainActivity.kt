package org.traccar.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val dataUris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileUploadCallback?.onReceiveValue(dataUris)
        fileUploadCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startTrackingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide() // 👈 Hilangkan header hijau native
        setContentView(R.layout.main)

        setupDefaultPreferences()
        requestAppPermissions()
        setupWebView()
    }

    private fun setupDefaultPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val editor = prefs.edit()
        
        if (!prefs.contains("url")) {
            editor.putString("url", "http://gps.mrdsolution.my.id:5055")
        }
        if (!prefs.contains("interval")) {
            editor.putString("interval", "10")
        }
        editor.putBoolean("status", true)
        editor.apply()
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startTrackingService() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        } catch (_: Exception) {}
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("tel:") || url.startsWith("whatsapp:") || url.startsWith("https://wa.me/")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                fileChooserLauncher.launch(intent)
                return true
            }
        }

        // Buka Portal Kurir AMS Pro
        webView.loadUrl("https://ams.mrdsolution.my.id/kurir")
    }

    fun stopTrackingService() {
        try {
            stopService(Intent(this, TrackingService::class.java))
        } catch (_: Exception) {}
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun setStaffId(staffId: String) {
            setStaffInfo(staffId, "")
        }

        @JavascriptInterface
        fun setStaffInfo(staffId: String, clientName: String) {
            if (staffId.isNotBlank()) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                prefs.edit()
                    .putString("id", staffId.trim())
                    .putString("rental_name", clientName.trim())
                    .apply()

                // 🔄 Restart service agar langsung reload Device ID baru & online!
                stopTrackingService()
                startTrackingService()
            }
        }

        @JavascriptInterface
        fun logoutKurir() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit().remove("id").remove("rental_name").apply()
            stopTrackingService()
        }

        @JavascriptInterface
        fun getDeviceId(): String {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getString("id", "") ?: ""
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
