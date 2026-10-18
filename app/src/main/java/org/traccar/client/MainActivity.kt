package org.traccar.client

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val dataUris = if (result.data?.data != null) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else if (cameraPhotoUri != null) {
                arrayOf(cameraPhotoUri!!)
            } else {
                null
            }
            fileUploadCallback?.onReceiveValue(dataUris)
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startTrackingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide() // Hilangkan header hijau native
        setContentView(R.layout.main)

        setupDefaultPreferences()
        requestAppPermissions()
        BatteryOptimizationHelper().requestException(this) // Bypass Doze Mode / Penghemat Baterai
        setupWebView()
    }

    private fun setupDefaultPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putString("url", "http://gps.mrdsolution.my.id:5055")
            .putString("interval", "10")   // Kirim posisi tiap 10 detik
            .putString("distance", "0")    // Kirim terus meski diam (biar tetap Online di MRD Track)
            .putString("angle", "0")
            .putString("accuracy", "high") // WAJIB Satelit GPS agar akurat & hidup saat layar mati
            .putBoolean("wakelock", true)  // CPU terjaga saat layar HP dikunci
            .putBoolean("status", true)
            .apply()
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

    fun stopTrackingService() {
        try {
            stopService(Intent(this, TrackingService::class.java))
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

                return try {
                    val photoFile = File.createTempFile("kurir_photo_", ".jpg", cacheDir)
                    cameraPhotoUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "$packageName.fileprovider",
                        photoFile
                    )

                    val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    if (fileChooserParams?.isCaptureEnabled == true) {
                        fileChooserLauncher.launch(takePictureIntent)
                    } else {
                        val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "image/*"
                        }
                        val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                            putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePictureIntent))
                        }
                        fileChooserLauncher.launch(chooserIntent)
                    }
                    true
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    false
                }
            }
        }

        // Buka Portal Kurir AMS Pro
        webView.loadUrl("https://ams.mrdsolution.my.id/kurir")
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

                // Restart service di Main Thread dengan jeda aman agar tidak crash di Xiaomi
                runOnUiThread {
                    stopTrackingService()
                    webView.postDelayed({
                        startTrackingService()
                    }, 500)
                }
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
