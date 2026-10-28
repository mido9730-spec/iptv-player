package com.iptvplayer.tv

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val userAgent = "Mozilla/5.0 (Linux; Android 11; TV) IPTVPlayer/1.0"
    private val accent = Color.parseColor("#FFB547")

    private lateinit var prefs: SharedPreferences
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var panel: LinearLayout
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var countView: TextView
    private lateinit var statusView: TextView
    private lateinit var bannerView: TextView

    private var all: List<Channel> = emptyList()
    private var filtered: List<Channel> = emptyList()
    private var groupFilter: String? = null
    private var query: String = ""
    private var current: Channel? = null
    private var triedHls = false

    // Activation (only when the build has Supabase settings).
    private val activationMode = BuildConfig.SUPABASE_URL.isNotBlank()
    private var activeUrl: String? = null
    private lateinit var gate: LinearLayout
    private lateinit var gateMsg: TextView
    private lateinit var gateMac: TextView
    private lateinit var gateButton: Button
    private lateinit var gateCode: Button
    private lateinit var gateM3u: Button
    private lateinit var gateXt: Button
    private lateinit var gateClose: Button
    private var allowOwn = false
    private val recheck = Runnable { checkActivation(true) }

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { bannerView.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("iptv", MODE_PRIVATE)
        allowOwn = prefs.getBoolean("allow_own", false)

        buildPlayer()
        buildUi()

        if (activationMode) {
            checkActivation(false)
        } else {
            val saved = prefs.getString("url", null)
            if (saved.isNullOrBlank()) {
                status("Add your playlist link to start")
                showPanel()
                playerView.post { askPlaylist() }
            } else {
                loadPlaylist(saved, autoplay = true)
            }
        }
    }

    // ---------- Player ----------

    private fun buildPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> status("Buffering…")
                    Player.STATE_READY -> status(null)
                    Player.STATE_ENDED -> status("Stream ended")
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val ch = current
                if (ch != null && !triedHls) {
                    // Many providers serve HLS from URLs with no .m3u8 extension. Retry as HLS once.
                    triedHls = true
                    player.setMediaItem(
                        MediaItem.Builder()
                            .setUri(ch.url)
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .build()
                    )
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    status("Can't play this channel (${error.errorCodeName})")
                }
            }
        })
    }

    private fun play(ch: Channel) {
        current = ch
        triedHls = false
        prefs.edit().putString("last", ch.url).apply()
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.playWhenReady = true
        showBanner(ch.name)
    }

    private fun step(delta: Int) {
        val pool = if (filtered.isNotEmpty()) filtered else all
        if (pool.isEmpty()) return
        val idx = pool.indexOfFirst { it.url == current?.url }
        val next = if (idx < 0) 0 else (idx + delta + pool.size) % pool.size
        play(pool[next])
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(color)
    }

    private fun styledButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 16f
        b.isFocusable = true
        b.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(Color.BLACK, Color.WHITE)
            )
        )
        b.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(accent))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(accent))
            addState(intArrayOf(), rounded(Color.parseColor("#2A323B")))
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        playerView = PlayerView(this)
        playerView.useController = false
        playerView.player = player
        root.addView(
            playerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Channel name shown briefly when zapping.
        bannerView = label("", 26f, Color.WHITE, bold = true)
        bannerView.setPadding(dp(18), dp(10), dp(18), dp(10))
        bannerView.background = rounded(Color.parseColor("#CC0F1318"))
        bannerView.visibility = View.GONE
        root.addView(
            bannerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(dp(32), dp(32), dp(32), dp(32)) }
        )

        // Loading / error messages.
        statusView = label("", 20f, Color.WHITE)
        statusView.setPadding(dp(18), dp(10), dp(18), dp(10))
        statusView.background = rounded(Color.parseColor("#CC0F1318"))
        statusView.visibility = View.GONE
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(dp(32), dp(32), dp(32), dp(32)) }
        )

        // Channel panel on the left.
        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.parseColor("#EE0F1318"))
        panel.setPadding(dp(20), dp(20), dp(20), dp(16))

        panel.addView(label("IPTV Player", 28f, Color.WHITE, bold = true))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val rowParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(0, 0, dp(8), 0) }
        if (activationMode) {
            row.addView(styledButton("Login") { openLogin() }, rowParams)
        } else {
            row.addView(styledButton("Playlist") { askPlaylist() }, rowParams)
        }
        row.addView(styledButton("Group") { askGroup() }, rowParams)
        row.addView(styledButton("Search") { askSearch() }, rowParams)
        panel.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(14), 0, dp(8)) }
        )

        countView = label("", 14f, Color.parseColor("#9AA5B1"))
        panel.addView(
            countView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, dp(8)) }
        )

        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList<String>()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.textSize = 20f
                v.maxLines = 1
                v.ellipsize = TextUtils.TruncateAt.END
                v.setPadding(dp(14), dp(10), dp(14), dp(10))
                return v
            }
        }
        listView = ListView(this)
        listView.adapter = adapter
        listView.divider = null
        listView.selector = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_selected), ColorDrawable(Color.parseColor("#88FFB547")))
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.parseColor("#88FFB547")))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            filtered.getOrNull(pos)?.let {
                play(it)
                hidePanel()
            }
        }
        panel.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        panel.addView(
            label("OK: play    Right: hide list    Back: exit", 13f, Color.parseColor("#9AA5B1")),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(8), 0, 0) }
        )

        val panelWidth = (resources.displayMetrics.widthPixels * 0.42f).toInt()
        root.addView(panel, FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT))

        // Activation screen, covers everything until the device is activated.
        gate = LinearLayout(this)
        gate.orientation = LinearLayout.VERTICAL
        gate.gravity = Gravity.CENTER
        gate.setBackgroundColor(Color.parseColor("#FF0F1318"))
        gate.isClickable = true
        gate.visibility = View.GONE
        gate.setPadding(dp(48), dp(24), dp(48), dp(24))

        gate.addView(label("IPTV Player", 30f, Color.WHITE, bold = true))

        gateMsg = label("", 20f, Color.parseColor("#9AA5B1"))
        gateMsg.gravity = Gravity.CENTER
        gate.addView(
            gateMsg,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(16), 0, dp(28)) }
        )

        gate.addView(label("Device ID", 16f, Color.parseColor("#9AA5B1")))
        gateMac = label("", 40f, accent, bold = true)
        gateMac.typeface = Typeface.MONOSPACE
        gate.addView(
            gateMac,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, dp(32)) }
        )

        val btnParams = {
            LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        gateCode = styledButton("Activation code") { askCode() }
        gateM3u = styledButton("M3U link") { askOwnM3u() }
        gateXt = styledButton("Xtream login") { askXtream() }
        gateButton = styledButton("Check again") { checkActivation(false) }
        gateClose = styledButton("Close") { hideGate() }
        gateClose.visibility = View.GONE
        for (b in listOf(gateCode, gateM3u, gateXt, gateButton, gateClose)) gate.addView(b, btnParams())

        root.addView(
            gate,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        setContentView(root)
    }

    private fun status(msg: String?) {
        if (msg == null) {
            statusView.visibility = View.GONE
        } else {
            statusView.text = msg
            statusView.visibility = View.VISIBLE
        }
    }

    private fun showBanner(name: String) {
        bannerView.text = name
        bannerView.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 3500)
    }

    private fun showPanel() {
        refreshLabels()
        panel.visibility = View.VISIBLE
        val idx = filtered.indexOfFirst { it.url == current?.url }
        listView.post {
            if (idx >= 0) listView.setSelection(idx)
            listView.requestFocus()
        }
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
    }

    // ---------- Channel list ----------

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val g = groupFilter
        filtered = all.filter { ch ->
            (g == null || ch.group == g) && (q.isEmpty() || ch.name.lowercase().contains(q))
        }
        refreshLabels()
        val parts = ArrayList<String>()
        parts.add("${filtered.size} channels")
        parts.add(groupFilter ?: "All groups")
        if (q.isNotEmpty()) parts.add("\"$q\"")
        countView.text = parts.joinToString("  ·  ")
        listView.setSelection(0)
    }

    private fun refreshLabels() {
        val cur = current?.url
        val labels = filtered.map { (if (it.url == cur) "▶  " else "     ") + it.name }
        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
    }

    // ---------- Playlist loading ----------

    private fun loadPlaylist(url: String, autoplay: Boolean) {
        status("Loading playlist…")
        Thread {
            try {
                val text = download(url)
                val list = M3uParser.parse(text)
                runOnUiThread { onPlaylist(url, list, autoplay) }
            } catch (e: Exception) {
                runOnUiThread {
                    status("Could not load playlist: ${e.message ?: e.javaClass.simpleName}")
                    showPanel()
                }
            }
        }.start()
    }

    private fun onPlaylist(url: String, list: List<Channel>, autoplay: Boolean) {
        if (list.isEmpty()) {
            status("No channels found in that playlist")
            showPanel()
            return
        }
        prefs.edit().putString("url", url).apply()
        all = list
        groupFilter = null
        query = ""
        status(null)
        applyFilter()

        val last = prefs.getString("last", null)
        val lastChannel = if (last != null) all.firstOrNull { it.url == last } else null
        if (autoplay && lastChannel != null) {
            play(lastChannel)
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun download(start: String): String {
        var url = URL(start)
        repeat(6) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                url = URL(url, loc)
                c.disconnect()
            } else if (code == 200) {
                return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                throw IOException("HTTP $code")
            }
        }
        throw IOException("Too many redirects")
    }

    // ---------- Dialogs ----------

    private fun inputContainer(edit: EditText): FrameLayout {
        val box = FrameLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun askPlaylist() {
        val edit = EditText(this)
        edit.hint = "http://example.com/playlist.m3u"
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        edit.setSingleLine(true)
        edit.setText(prefs.getString("url", ""))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Playlist link (M3U)")
            .setView(inputContainer(edit))
            .setPositiveButton("Load") { _, _ ->
                val url = edit.text.toString().trim()
                if (url.isNotEmpty()) loadPlaylist(url, autoplay = false)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun askSearch() {
        val edit = EditText(this)
        edit.hint = "Channel name"
        edit.inputType = InputType.TYPE_CLASS_TEXT
        edit.setSingleLine(true)
        edit.setText(query)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Search channels")
            .setView(inputContainer(edit))
            .setPositiveButton("Search") { _, _ ->
                query = edit.text.toString()
                applyFilter()
                listView.requestFocus()
            }
            .setNeutralButton("Clear") { _, _ ->
                query = ""
                applyFilter()
                listView.requestFocus()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun askGroup() {
        if (all.isEmpty()) return
        val counts = all.groupingBy { it.group }.eachCount()
        val names = counts.keys.sorted()
        val items = ArrayList<String>()
        items.add("All groups (${all.size})")
        for (n in names) items.add("$n (${counts[n]})")

        AlertDialog.Builder(this)
            .setTitle("Group")
            .setItems(items.toTypedArray()) { _, which ->
                groupFilter = if (which == 0) null else names[which - 1]
                applyFilter()
                listView.requestFocus()
            }
            .show()
    }

    // ---------- Activation ----------

    // Android no longer lets apps read the real hardware MAC address, so the app
    // derives a stable MAC-style ID from the device's ANDROID_ID instead.
    private fun deviceMac(): String {
        val saved = prefs.getString("mac", null)
        if (saved != null) return saved
        val aid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(("iptvplayer:$aid").toByteArray())
        val b = hash.copyOfRange(0, 6)
        b[0] = ((b[0].toInt() or 0x02) and 0xFE).toByte()
        val mac = b.joinToString(":") { "%02X".format(it) }
        prefs.edit().putString("mac", mac).apply()
        return mac
    }

    private fun rpc(name: String, body: JSONObject): JSONObject {
        val key = BuildConfig.SUPABASE_ANON_KEY
        val base = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        val c = URL("$base/rest/v1/rpc/$name").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.doOutput = true
        c.setRequestProperty("apikey", key)
        if (key.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code != 200) throw IOException("Server $code")
        val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(text)
    }

    private fun fetchActivation(): JSONObject = rpc("get_device", JSONObject().put("p_mac", deviceMac()))

    private fun checkActivation(silent: Boolean) {
        if (!silent) status("Checking activation…")
        Thread {
            try {
                val r = fetchActivation()
                runOnUiThread { onActivation(r) }
            } catch (e: Exception) {
                runOnUiThread { onActivationOffline() }
            }
        }.start()
    }

    private fun playUrl(url: String) {
        hideGate()
        if (url != activeUrl) {
            activeUrl = url
            loadPlaylist(url, autoplay = true)
        } else {
            status(null)
        }
        scheduleRecheck()
    }

    private fun onActivation(r: JSONObject) {
        val st = r.optString("status")
        if (r.has("allow_own")) {
            allowOwn = r.optBoolean("allow_own", false)
            prefs.edit().putBoolean("allow_own", allowOwn).apply()
        }
        when (st) {
            "active" -> {
                val url = if (r.isNull("playlist_url")) "" else r.optString("playlist_url", "")
                if (url.isBlank()) {
                    showGate("No playlist is assigned to this device yet.")
                    return
                }
                val exp = if (r.isNull("expires_at")) 0L else parseIso(r.optString("expires_at"))
                prefs.edit().putString("act_url", url).putLong("act_exp", exp).apply()
                playUrl(url)
            }
            "invalid_code" -> gateMsg.text = "That code is not valid. Check it and try again."
            "used_code" -> gateMsg.text = "That code has already been used."
            "disabled" -> showGate("This device is disabled. Contact your provider.", ownAllowed = false, codeAllowed = false)
            "pending", "expired" -> {
                val own = prefs.getString("own_url", null)
                if (allowOwn && !own.isNullOrBlank()) {
                    playUrl(own)
                } else if (st == "expired") {
                    showGate("Your subscription has expired. Enter a new code or contact your provider.")
                } else {
                    showGate("Enter your activation code, or send the Device ID below to your provider.")
                }
            }
            else -> showGate("Activation error. Try again in a moment.")
        }
    }

    // No connection to the activation server: keep working from the last good answer until it expires.
    private fun onActivationOffline() {
        val actUrl = prefs.getString("act_url", null)
        val exp = prefs.getLong("act_exp", 0L)
        val actValid = actUrl != null && (exp == 0L || exp > System.currentTimeMillis())
        val own = prefs.getString("own_url", null)
        val ownOk = prefs.getBoolean("allow_own", false) && !own.isNullOrBlank()
        val url: String? = if (actValid) actUrl else if (ownOk) own else null
        val gateShown = gate.visibility == View.VISIBLE
        if (url != null && !gateShown) {
            playUrl(url)
        } else if (gateShown) {
            gateMsg.text = "Can't reach the activation server. Retrying…"
            scheduleRecheck()
        } else {
            showGate("Can't reach the activation server. Check the internet connection.")
        }
    }

    private fun showGate(msg: String, ownAllowed: Boolean = allowOwn, codeAllowed: Boolean = true) {
        activeUrl = null
        player.stop()
        hidePanel()
        status(null)
        gateMsg.text = msg
        gateMac.text = deviceMac()
        gateCode.visibility = if (codeAllowed) View.VISIBLE else View.GONE
        gateM3u.visibility = if (ownAllowed) View.VISIBLE else View.GONE
        gateXt.visibility = if (ownAllowed) View.VISIBLE else View.GONE
        gateClose.visibility = View.GONE
        gate.visibility = View.VISIBLE
        (if (codeAllowed) gateCode else gateButton).requestFocus()
        scheduleRecheck()
    }

    // Opened from the channel list so a client can switch to a different login.
    private fun openLogin() {
        gateMsg.text = "Sign in with an activation code or your own playlist."
        gateMac.text = deviceMac()
        gateCode.visibility = View.VISIBLE
        gateM3u.visibility = if (allowOwn) View.VISIBLE else View.GONE
        gateXt.visibility = if (allowOwn) View.VISIBLE else View.GONE
        gateClose.visibility = View.VISIBLE
        gate.visibility = View.VISIBLE
        gateCode.requestFocus()
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun useOwn(url: String) {
        prefs.edit().putString("own_url", url).putString("last", null).apply()
        activeUrl = null
        playUrl(url)
    }

    private fun redeem(code: String) {
        status("Checking code…")
        Thread {
            try {
                val r = rpc("redeem_code", JSONObject().put("p_mac", deviceMac()).put("p_code", code))
                runOnUiThread {
                    status(null)
                    if (r.optString("status") == "active") activeUrl = null
                    onActivation(r)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status(null)
                    gateMsg.text = "Can't reach the server. Try again."
                }
            }
        }.start()
    }

    private fun askCode() {
        val edit = EditText(this)
        edit.hint = "XXXX-XXXX-XXXX"
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        edit.setSingleLine(true)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Activation code")
            .setView(inputContainer(edit))
            .setPositiveButton("Activate") { _, _ ->
                val code = edit.text.toString().trim()
                if (code.isNotEmpty()) redeem(code)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun askOwnM3u() {
        val edit = EditText(this)
        edit.hint = "http://example.com/playlist.m3u"
        edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        edit.setSingleLine(true)
        edit.setText(prefs.getString("own_m3u", ""))

        val dialog = AlertDialog.Builder(this)
            .setTitle("M3U link")
            .setView(inputContainer(edit))
            .setPositiveButton("Sign in") { _, _ ->
                val url = edit.text.toString().trim()
                if (url.isNotEmpty()) {
                    prefs.edit().putString("own_m3u", url).apply()
                    useOwn(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun askXtream() {
        fun field(hint: String, type: Int, value: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.inputType = type
            e.setSingleLine(true)
            e.setText(value)
            return e
        }
        val server = field("Server  http://host:port", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, prefs.getString("xt_server", "") ?: "")
        val user = field("Username", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, prefs.getString("xt_user", "") ?: "")
        val pass = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, prefs.getString("xt_pass", "") ?: "")

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(8), dp(24), 0)
        for (e in listOf(server, user, pass)) {
            box.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Xtream login")
            .setView(box)
            .setPositiveButton("Sign in") { _, _ ->
                var sv = server.text.toString().trim().trimEnd('/')
                val u = user.text.toString().trim()
                val p = pass.text.toString().trim()
                if (sv.isNotEmpty() && u.isNotEmpty() && p.isNotEmpty()) {
                    if (!sv.startsWith("http://", true) && !sv.startsWith("https://", true)) sv = "http://$sv"
                    prefs.edit().putString("xt_server", sv).putString("xt_user", u).putString("xt_pass", p).apply()
                    useOwn("$sv/get.php?username=${enc(u)}&password=${enc(p)}&type=m3u_plus&output=ts")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun hideGate() {
        gate.visibility = View.GONE
    }

    private fun scheduleRecheck() {
        handler.removeCallbacks(recheck)
        val delay = if (gate.visibility == View.VISIBLE) 20_000L else 15 * 60_000L
        handler.postDelayed(recheck, delay)
    }

    private fun parseIso(s: String): Long {
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(s.take(19))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ---------- Remote control ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gate.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (panel.visibility == View.VISIBLE) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && listView.hasFocus() && current != null) {
                    hidePanel()
                    return true
                }
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_PAGE_UP -> {
                        step(-1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_PAGE_DOWN -> {
                        step(1)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_BACK -> {
                        showPanel()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- Lifecycle ----------

    override fun onStart() {
        super.onStart()
        if (current != null) player.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player.playWhenReady = false
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideBanner)
        handler.removeCallbacks(recheck)
        player.release()
        super.onDestroy()
    }
}
