package com.pharrlabs.murmur

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import android.content.Context
import android.os.PowerManager

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var lockView: LinearLayout
    private var pendingAsk: String? = null
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var stoppedAt = 0L
    private var unlocked = false

    companion object {
        @Volatile var visible = false
        private const val REQ_FILE = 11
        private const val REQ_PERMS = 12
        private const val LOCK_AFTER_MS = 30_000L
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Cfg.paired(this)) {
            startActivity(Intent(this, PairActivity::class.java))
            finish()
            return
        }
        Notifier.channels(this)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#FCF6E6")) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val host = Uri.parse(Cfg.murmurUrl(this@MainActivity)).host
                    if (r.url.host == host) return false
                    startActivity(Intent(Intent.ACTION_VIEW, r.url))
                    return true
                }
                override fun onPageFinished(v: WebView, url: String) {
                    val t = pendingAsk
                    if (t != null && url.contains("/chat/")) {
                        pendingAsk = null
                        v.evaluateJavascript(sendScript(t), null)
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(req: PermissionRequest) {
                    runOnUiThread {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            req.grant(req.resources)
                        } else {
                            pendingWebPermission = req
                            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS)
                        }
                    }
                }
                override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                    chooser?.onReceiveValue(null)
                    chooser = cb
                    return try {
                        startActivityForResult(p.createIntent(), REQ_FILE); true
                    } catch (e: Exception) { chooser = null; false }
                }
            }
            setDownloadListener { url, _, _, _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        lockView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FCF6E6"))
            visibility = View.GONE
            isClickable = true
            addView(TextView(context).apply { text = "Murmur is locked"; textSize = 20f; setTextColor(Color.parseColor("#1A1A1F")); gravity = Gravity.CENTER })
            addView(Button(context).apply { text = "Unlock"; setOnClickListener { authenticate() } })
        }
        root.addView(lockView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
        }
        askBatteryExemptionOnce()
        try { PushService.start(this) } catch (e: Exception) { /* retried on next open */ }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else handle(intent, first = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent, first = false)
    }

    private fun handle(i: Intent, first: Boolean) {
        val base = Cfg.murmurUrl(this)
        when {
            i.action == Intent.ACTION_SEND && i.type == "text/plain" -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val title = i.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
                web.loadUrl(base + "/share?title=" + Uri.encode(title) + "&text=" + Uri.encode(text))
            }
            i.action == "com.pharrlabs.murmur.NEW_CHAT" -> web.loadUrl("$base/new")
            i.hasExtra("ask_text") -> {
                val agent = i.getStringExtra("ask_agent") ?: Cfg.profile(this)
                pendingAsk = i.getStringExtra("ask_text")
                web.loadUrl("$base/chat/${Uri.encode(agent)}/new")
            }
            i.hasExtra("url") -> web.loadUrl(i.getStringExtra("url")!!)
            i.hasExtra("agent") -> web.loadUrl("$base/chat/${Uri.encode(i.getStringExtra("agent"))}/new")
            first -> web.loadUrl(base + "/")
        }
    }

    private fun sendScript(text: String): String {
        val js = org.json.JSONObject.quote(text)
        return "(function(text){var tries=0;var t=setInterval(function(){tries++;" +
            "var ta=document.querySelector('textarea[aria-label=\"Message\"]');" +
            "if(ta){clearInterval(t);var set=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;" +
            "set.call(ta,text);ta.dispatchEvent(new Event('input',{bubbles:true}));" +
            "setTimeout(function(){var b=document.querySelector('button[aria-label=\"Send\"]');if(b)b.click();},500);}" +
            "else if(tries>80){clearInterval(t);}},250);})($js);"
    }

    private fun askBatteryExemptionOnce() {
        val p = Cfg.prefs(this)
        if (p.getBoolean("batteryAsked", false)) return
        p.edit().putBoolean("batteryAsked", true).apply()
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { /* Settings has a manual switch */ }
        }
    }

    // ---- biometric lock (optional, Settings) ----
    private fun authenticate() {
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("Unlock Murmur")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                unlocked = true
                lockView.visibility = View.GONE
            }
        })
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (Cfg.biometric(this) && (!unlocked || System.currentTimeMillis() - stoppedAt > LOCK_AFTER_MS)) {
            unlocked = false
            lockView.visibility = View.VISIBLE
            authenticate()
        } else if (!Cfg.biometric(this)) {
            lockView.visibility = View.GONE
        }
    }

    override fun onStop() {
        visible = false
        stoppedAt = System.currentTimeMillis()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        web.saveState(out)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == REQ_FILE) {
            chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data))
            chooser = null
        } else super.onActivityResult(req, res, data)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, results: IntArray) {
        val granted = results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED
        pendingWebPermission?.let { if (granted) it.grant(it.resources) else it.deny() }
        pendingWebPermission = null
    }
}
