package de.tutao.tutanota

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.MailTo
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import android.view.ContextMenu
import android.view.ContextMenu.ContextMenuInfo
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.webkit.WebView.HitTestResult
import android.widget.Toast
import androidx.annotation.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import de.tutao.tutanota.alarms.AlarmNotificationsManager
import de.tutao.tutanota.alarms.SystemAlarmFacade
import de.tutao.tutanota.data.*
import de.tutao.tutanota.push.LocalNotificationsFacade
import de.tutao.tutanota.push.PushNotificationService
import de.tutao.tutanota.push.SseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class MainActivity : FragmentActivity() {
	lateinit var webView: WebView
		private set
	lateinit var sseStorage: SseStorage
	lateinit var nativeImpl: Native

	private val permissionsRequests: MutableMap<Int, Continuation<Unit>> = ConcurrentHashMap()
	private val activityRequests: MutableMap<Int, Continuation<ActivityResult>> = ConcurrentHashMap()

	private var firstLoaded = false

	@SuppressLint("SetJavaScriptEnabled", "StaticFieldLeak")
	override fun onCreate(savedInstanceState: Bundle?) {
		Log.d(TAG, "App started")
		val keyStoreFacade = createAndroidKeyStoreFacade(this)
		sseStorage = SseStorage(AppDatabase.getDatabase(this,  /*allowMainThreadAccess*/false),
				keyStoreFacade)

		val alarmNotificationsManager = AlarmNotificationsManager(sseStorage,
				Crypto(this), SystemAlarmFacade(this), LocalNotificationsFacade(this))

		nativeImpl = Native(this, sseStorage, alarmNotificationsManager)

		doApplyTheme(nativeImpl.themeManager.currentThemeWithFallback)

		super.onCreate(savedInstanceState)

		setupPushNotifications()

		webView = WebView(this)
		webView.setBackgroundColor(Color.TRANSPARENT)

		setContentView(webView)
		if (BuildConfig.DEBUG) {
			WebView.setWebContentsDebuggingEnabled(true)
		}

		webView.settings.apply {
			javaScriptEnabled = true
			domStorageEnabled = true
			javaScriptCanOpenWindowsAutomatically = false
			allowUniversalAccessFromFileURLs = true
		}

		// Reject cookies by external content
		CookieManager.getInstance().setAcceptCookie(false)
		CookieManager.getInstance().removeAllCookies(null)

		if (!firstLoaded) {
			handleIntent(intent)
		}
		firstLoaded = true
		webView.post { // use webView.post to switch to main thread again to be able to observe sseStorage
			sseStorage.observeUsers().observe(this@MainActivity, { userInfos ->
				if (userInfos!!.isEmpty()) {
					Log.d(TAG, "invalidateAlarms")
					lifecycleScope.launchWhenCreated {
						nativeImpl.sendRequest(JsRequest.invalidateAlarms, arrayOf())
					}
				}
			})
		}


		webView.webViewClient = object : WebViewClient() {
			override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
				val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
				try {
					startActivity(intent)
				} catch (e: ActivityNotFoundException) {
					Toast.makeText(this@MainActivity, "Could not open link: $url", Toast.LENGTH_SHORT)
							.show()
				}
				return true
			}
		}

		// Handle long click on links in the WebView
		registerForContextMenu(webView)

		val queryParameters = mutableMapOf<String, String>()
		// If opened from notifications, tell Web app to not login automatically, we will pass
		// mailbox later when loaded (in handleIntent())
		if (intent != null && (OPEN_USER_MAILBOX_ACTION == intent.action || OPEN_CALENDAR_ACTION == intent.action)) {
			queryParameters["noAutoLogin"] = "true"
		}
		startWebApp(queryParameters)
	}

	override fun onStart() {
		super.onStart()
		Log.d(TAG, "onStart")
		lifecycleScope.launchWhenCreated {
			nativeImpl.sendRequest(JsRequest.visibilityChange, arrayOf(true))
		}
	}

	override fun onStop() {
		Log.d(TAG, "onStop")
		lifecycleScope.launch { nativeImpl.sendRequest(JsRequest.visibilityChange, arrayOf(false)) }
		super.onStop()
	}

	@MainThread
	private fun startWebApp(parameters: MutableMap<String, String>) {
		webView.loadUrl(getInitialUrl(parameters))
		nativeImpl.setup()
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntent(intent)
	}

	private fun handleIntent(intent: Intent) = lifecycleScope.launchWhenCreated {

		// When we redirect to the app from outside, for example after doing payment verification,
		// we don't want to do any kind of intent handling
		val data = intent.data
		if (data != null && data.toString().startsWith("tutanota://")) {
			return@launchWhenCreated
		}

		if (intent.action != null) {
			when (intent.action) {
				Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> share(intent)
				OPEN_USER_MAILBOX_ACTION -> openMailbox(intent)
				OPEN_CALENDAR_ACTION -> openCalendar(intent)
			}
		}
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		webView.saveState(outState)
	}

	fun applyTheme() {
		val theme = nativeImpl.themeManager.currentThemeWithFallback
		runOnUiThread { doApplyTheme(theme) }
	}

	private fun doApplyTheme(theme: Theme) {
		Log.d(TAG, "changeTheme: " + theme["themeId"])
		@ColorInt val backgroundColor = parseColor(theme["content_bg"]!!)
		window.setBackgroundDrawable(ColorDrawable(backgroundColor))

		val decorView = window.decorView
		val headerBg = theme["header_bg"]!!

		@ColorInt val statusBarColor = parseColor(headerBg)
		val isStatusBarLight = headerBg.isLightHexColor()
		var visibilityFlags = 0
		if (atLeastOreo()) {
			window.navigationBarColor = statusBarColor
			if (isStatusBarLight) {
				visibilityFlags = visibilityFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
			}
		}

		// Changing status bar color
		// Before Android M there was no flag to use lightStatusBar (so that text is white or
		// black). As our primary color is red, Android thinks that the status bar color text
		// should be white. So we cannot use white status bar color.
		// So for Android M and above we alternate between white and dark status bar colors and
		// we change lightStatusBar flag accordingly.
		window.statusBarColor = statusBarColor
		if (isStatusBarLight) {
			visibilityFlags = visibilityFlags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
		}

		decorView.systemUiVisibility = visibilityFlags
	}

	suspend fun askBatteryOptinmizationsIfNeeded() {
		val powerManager = getSystemService(POWER_SERVICE) as PowerManager
		val preferences = PreferenceManager.getDefaultSharedPreferences(this)

		if (
				!preferences.getBoolean(ASKED_BATTERY_OPTIMIZTAIONS_PREF, false)
				&& !powerManager.isIgnoringBatteryOptimizations(packageName)
		) {

			nativeImpl.sendRequest(JsRequest.showAlertDialog, arrayOf("allowPushNotification_msg"))

			withContext(Dispatchers.Main) {
				saveAskedBatteryOptimizations(preferences)
				@SuppressLint("BatteryLife")
				val intent = Intent(
						Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
						Uri.parse("package:$packageName")
				)
				startActivity(intent)
			}
		}
	}

	private fun saveAskedBatteryOptimizations(preferences: SharedPreferences) {
		preferences.edit().putBoolean(ASKED_BATTERY_OPTIMIZTAIONS_PREF, true).apply()
	}

	private fun getInitialUrl(parameters: MutableMap<String, String>): String {
		val theme = nativeImpl.themeManager.currentTheme
		if (theme != null) {
			parameters["theme"] = JSONObject.wrap(theme)!!.toString()
		}
		parameters["platformId"] = "android"
		val queryBuilder = StringBuilder()
		for ((key, value) in parameters) {
			try {
				val escapedValue = URLEncoder.encode(value, "UTF-8")
				if (queryBuilder.length == 0) {
					queryBuilder.append("?")
				} else {
					queryBuilder.append("&")
				}
				queryBuilder.append(key)
				queryBuilder.append('=')
				queryBuilder.append(escapedValue)
			} catch (e: UnsupportedEncodingException) {
				throw RuntimeException(e)
			}
		}
		// additional path information like app.html/login are not handled properly by the webview
		// when loaded from local file system. so we are just adding parameters to the Url e.g. ../app.html?noAutoLogin=true.
		return baseUrl + queryBuilder.toString()
	}

	private val baseUrl: String
		get() = BuildConfig.RES_ADDRESS


	private fun hasPermission(permission: String): Boolean {
		return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
	}

	suspend fun getPermission(permission: String) = suspendCoroutine<Unit> { continuation ->
		if (hasPermission(permission)) {
			continuation.resume(Unit)
		} else {
			val requestCode = getNextRequestCode()
			permissionsRequests[requestCode] = continuation
			requestPermissions(arrayOf(permission), requestCode)
		}
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		val continuation = permissionsRequests.remove(requestCode)
		if (continuation == null) {
			Log.w(TAG, "No deferred for the permission request$requestCode")
			return
		}
		if (grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			continuation.resume(Unit)
		} else {
			continuation.resumeWithException(SecurityException("Permission missing: " + Arrays.toString(permissions)))
		}
	}

	suspend fun startActivityForResult(@RequiresPermission intent: Intent?): ActivityResult = suspendCoroutine { continuation ->
		val requestCode = getNextRequestCode()
		activityRequests[requestCode] = continuation
		// deprecated but we need requestCode to identify the request which is not possible with new API
		super.startActivityForResult(intent, requestCode)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		val continuation = activityRequests.remove(requestCode)
		if (continuation != null) {
			continuation.resume(ActivityResult(resultCode, data!!))
		} else {
			Log.w(TAG, "No deferred for activity request$requestCode")
		}
	}

	fun setupPushNotifications() {
		startService(PushNotificationService.startIntent(this, "MainActivity#setupPushNotifications"))
		val jobScheduler = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
		jobScheduler.schedule(
				JobInfo.Builder(1, ComponentName(this, PushNotificationService::class.java))
						.setPeriodic(TimeUnit.MINUTES.toMillis(15))
						.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
						.setPersisted(true).build())
	}

	/**
	 * The sharing activity. Either invoked from MainActivity (if the app was not active when the
	 * share occurred) or from onCreate.
	 */
	suspend fun share(intent: Intent) {
		val action = intent.action
		val clipData = intent.clipData
		val files: JSONArray
		var text: String? = null
		val addresses = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)
		val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
		if (Intent.ACTION_SEND == action) {
			// Try to read text from the clipboard before fall back on intent.getStringExtra
			if (clipData != null && clipData.itemCount > 0) {
				if (clipData.description.getMimeType(0).startsWith("text")) {
					text = clipData.getItemAt(0).htmlText
				}
				if (text == null && clipData.getItemAt(0).text != null) {
					text = clipData.getItemAt(0).text.toString()
				}
			}
			if (text == null) {
				text = intent.getStringExtra(Intent.EXTRA_TEXT)
			}
			files = getFilesFromIntent(intent)
		} else if (Intent.ACTION_SEND_MULTIPLE == action) {
			files = getFilesFromIntent(intent)
		} else {
			files = JSONArray()
		}
		val mailToUrlString: String?
		mailToUrlString = if (intent.data != null && MailTo.isMailTo(intent.dataString)) {
			intent.dataString
		} else {
			null
		}
		var jsonAddresses: JSONArray? = null
		if (addresses != null) {
			jsonAddresses = JSONArray()
			for (address in addresses) {
				jsonAddresses.put(address)
			}
		}

		// Satisfy Java's lambda requirements
		val fText = text
		val fJsonAddresses = jsonAddresses
		nativeImpl.sendRequest(JsRequest.createMailEditor, arrayOf(files, fText, fJsonAddresses, subject, mailToUrlString))
	}

	private fun getFilesFromIntent(intent: Intent): JSONArray {
		val clipData = intent.clipData
		val filesArray = JSONArray()
		if (clipData != null) {
			for (i in 0 until clipData.itemCount) {
				val item = clipData.getItemAt(i)
				val uri = item.uri
				if (uri != null) {
					filesArray.put(uri.toString())
				}
			}
		} else {
			// Intent documentation claims that data is copied to ClipData if it's not there
			// but we want to be sure
			if (Intent.ACTION_SEND_MULTIPLE == intent.action) {
				val uris = intent.extras!![Intent.EXTRA_STREAM] as ArrayList<Uri>?
				if (uris != null) {
					for (uri in uris) {
						filesArray.put(uri.toString())
					}
				}
			} else if (intent.hasExtra(Intent.EXTRA_STREAM)) {
				val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
				filesArray.put(uri.toString())
			} else if (intent.data != null) {
				val uri = intent.data
				filesArray.put(uri.toString())
			} else {
				Log.w(TAG, "Did not find files in the intent")
			}
		}
		return filesArray
	}

	suspend fun openMailbox(intent: Intent) {
		val userId = intent.getStringExtra(OPEN_USER_MAILBOX_USERID_KEY)
		val address = intent.getStringExtra(OPEN_USER_MAILBOX_MAILADDRESS_KEY)
		val isSummary = intent.getBooleanExtra(IS_SUMMARY_EXTRA, false)
		if (userId == null || address == null) {
			return
		}
		val addresses = ArrayList<String>(1)
		addresses.add(address)
		startService(LocalNotificationsFacade.notificationDismissedIntent(this, addresses,
				"MainActivity#openMailbox", isSummary))

		nativeImpl.sendRequest(JsRequest.openMailbox, arrayOf(userId, address))
	}

	suspend fun openCalendar(intent: Intent) {
		val userId = intent.getStringExtra(OPEN_USER_MAILBOX_USERID_KEY) ?: return
		nativeImpl.sendRequest(JsRequest.openCalendar, arrayOf(userId))
	}

	override fun onBackPressed() {
		if (nativeImpl.webAppInitialized.isCompleted) {
			lifecycleScope.launchWhenCreated {
				val result = nativeImpl.sendRequest(JsRequest.handleBackPress, arrayOfNulls<Any>(0))
				try {
					if (!(result as JSONObject).getBoolean("value")) {
						goBack()
					}
				} catch (e: JSONException) {
					Log.e(TAG, "error parsing response", e)
				}
			}
		} else {
			goBack()
		}
	}

	private fun goBack() {
		moveTaskToBack(false)
	}

	fun reload(parameters: MutableMap<String, String>) {
		runOnUiThread { startWebApp(parameters) }
	}

	override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenuInfo?) {
		super.onCreateContextMenu(menu, v, menuInfo)
		val hitTestResult = webView.hitTestResult
		if (hitTestResult.type == HitTestResult.SRC_ANCHOR_TYPE) {
			val link = hitTestResult.extra ?: return
			if (link.startsWith(baseUrl)) {
				return
			}
			menu.setHeaderTitle(link)
			menu.add(0, 0, 0, "Copy link").setOnMenuItemClickListener { item: MenuItem? ->
				(getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
						.setPrimaryClip(ClipData.newPlainText(link, link))
				true
			}
			menu.add(0, 2, 0, "Share").setOnMenuItemClickListener { item: MenuItem? ->
				val intent = Intent(Intent.ACTION_SEND)
				intent.putExtra(Intent.EXTRA_TEXT, link)
				intent.setTypeAndNormalize("text/plain")
				this.startActivity(Intent.createChooser(intent, "Share link"))
				true
			}
		}
	}

	companion object {
		const val OPEN_USER_MAILBOX_ACTION = "de.tutao.tutanota.OPEN_USER_MAILBOX_ACTION"
		const val OPEN_CALENDAR_ACTION = "de.tutao.tutanota.OPEN_CALENDAR_ACTION"
		const val OPEN_USER_MAILBOX_MAILADDRESS_KEY = "mailAddress"
		const val OPEN_USER_MAILBOX_USERID_KEY = "userId"
		const val IS_SUMMARY_EXTRA = "isSummary"
		private const val ASKED_BATTERY_OPTIMIZTAIONS_PREF = "askedBatteryOptimizations"
		private const val TAG = "MainActivity"
		private var requestId = 0

		@Synchronized
		private fun getNextRequestCode(): Int {
			requestId++
			if (requestId < 0) {
				requestId = 0
			}
			return requestId
		}
	}
}