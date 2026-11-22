package cc.huhm.splinklez.announce

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.R
import cc.huhm.splinklez.assistant.Palette
import cc.huhm.splinklez.data.ApiException
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.data.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class AnnLink(val label: String, val url: String)

data class Announcement(
    val id: String,
    val title: String,
    val body: String,
    val images: List<String>,
    val links: List<AnnLink>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val date: String get() = dateOf(createdAt)

    companion object {
        private val fmt = DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日")
        fun dateOf(ms: Long): String = fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Taipei")))

        fun from(o: JSONObject): Announcement {
            val imgs = o.optJSONArray("images") ?: JSONArray()
            val links = o.optJSONArray("links") ?: JSONArray()
            return Announcement(
                id = o.getString("id"),
                title = o.optString("title"),
                body = o.optString("body"),
                images = (0 until imgs.length()).map { AnnApi.abs(imgs.getString(it)) },
                links = (0 until links.length()).mapNotNull { i ->
                    links.optJSONObject(i)?.let { AnnLink(it.optString("label").ifBlank { it.optString("url") }, it.optString("url")) }
                }.filter { it.url.isNotBlank() },
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }
}

class AnnList(val items: List<Announcement>, val popup: Announcement?, val isAdmin: Boolean)

/** /api/announcements: list, popup, seen; admin create/edit/delete and image upload. */
object AnnApi {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun abs(url: String) = if (url.startsWith("/")) BuildConfig.API_BASE + url else url

    private fun req(path: String): Request.Builder {
        val b = Request.Builder().url(BuildConfig.API_BASE + path)
            .header("accept", "application/json")
            .header("user-agent", "Splinklez-Android/" + BuildConfig.VERSION_NAME)
        val token = runCatching { Graph.session.session.value?.token }.getOrNull()
        if (token != null) b.header("authorization", "Bearer $token")
        return b
    }

    private suspend fun call(r: Request): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val j = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (res.code == 401) throw UnauthorizedException()
            if (!res.isSuccessful) throw ApiException(res.code, j.optString("error").ifBlank { "伺服器暫時沒有回應" })
            j
        }
    }

    suspend fun list(): AnnList {
        val j = call(req("/api/announcements").get().build())
        val arr = j.optJSONArray("items") ?: JSONArray()
        return AnnList(
            items = (0 until arr.length()).map { Announcement.from(arr.getJSONObject(it)) },
            popup = j.optJSONObject("popup")?.let { Announcement.from(it) },
            isAdmin = j.optBoolean("isAdmin"),
        )
    }

    suspend fun get(id: String): Announcement = Announcement.from(call(req("/api/announcements/${Uri.encode(id)}").get().build()).getJSONObject("announcement"))

    suspend fun seen(id: String) {
        if (runCatching { Graph.session.session.value }.getOrNull() == null) return
        runCatching { call(req("/api/announcements/${Uri.encode(id)}/seen").post(ByteArray(0).toRequestBody(null)).build()) }
    }

    private fun bodyOf(title: String, body: String, images: List<String>, links: List<AnnLink>): RequestBody = JSONObject()
        .put("title", title)
        .put("body", body)
        .put("images", JSONArray(images))
        .put("links", JSONArray(links.map { JSONObject().put("label", it.label).put("url", it.url) }))
        .toString().toRequestBody(JSON)

    suspend fun create(title: String, body: String, images: List<String>, links: List<AnnLink>): Announcement =
        Announcement.from(call(req("/api/announcements").post(bodyOf(title, body, images, links)).build()).getJSONObject("announcement"))

    suspend fun update(id: String, title: String, body: String, images: List<String>, links: List<AnnLink>): Announcement =
        Announcement.from(call(req("/api/announcements/${Uri.encode(id)}").patch(bodyOf(title, body, images, links)).build()).getJSONObject("announcement"))

    suspend fun delete(id: String) {
        call(req("/api/announcements/${Uri.encode(id)}").delete().build())
    }

    /** Returns the absolute URL of the stored image. */
    suspend fun upload(jpeg: ByteArray): String =
        abs(call(req("/api/announcements/images").post(jpeg.toRequestBody("image/jpeg".toMediaType())).build()).getString("url"))

    suspend fun bitmap(url: String, maxPx: Int): Bitmap? {
        Images.cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = http.newCall(Request.Builder().url(url).header("user-agent", "Splinklez-Android/" + BuildConfig.VERSION_NAME).build())
                    .execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return@runCatching null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= maxPx || o.outHeight / (s * 2) >= maxPx) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()?.also { Images.cache.put(url, it) }
        }
    }
}

object Images {
    val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
}

/** Which announcements already popped up on this phone (also covers signed-out use). */
object SeenStore {
    private fun prefs(c: Context) = c.getSharedPreferences("spz_announce", Context.MODE_PRIVATE)
    fun has(c: Context, id: String) = prefs(c).getStringSet("seen", emptySet())!!.contains(id)
    fun add(c: Context, id: String) {
        val s = prefs(c).getStringSet("seen", emptySet())!!.toMutableSet()
        s.add(id)
        prefs(c).edit().putStringSet("seen", s).apply()
    }
}

/** Small view helpers in the app's paper/ink look. */
class Ui(val ctx: Context, val palette: Palette = Palette.load(ctx)) {
    val bg: Int = if (palette.dark) ctx.getColor(R.color.ink) else ctx.getColor(R.color.paper)
    val onAccent: Int = if (palette.dark) ctx.getColor(R.color.ink) else 0xffffffff.toInt()
    val hair: Int = (palette.text and 0x00ffffff) or (0x2E shl 24)

    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
    fun px(v: Float) = dp(v).toInt()

    fun text(s: CharSequence, size: Float, color: Int = palette.text, bold: Boolean = false, serif: Boolean = false) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        setLineSpacing(dp(3f), 1f)
        typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun kicker(label: String, rest: String) = text("", 11f, palette.dim).apply {
        val sb = android.text.SpannableStringBuilder()
        sb.append(label, android.text.style.ForegroundColorSpan(palette.accent), 0)
        if (rest.isNotEmpty()) sb.append("  ·  ").append(rest)
        text = sb
        letterSpacing = 0.12f
    }

    fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        val p = px(10f)
        setPadding(p * 2, p, p * 2, p)
        minHeight = px(44f)
        if (filled) {
            setTextColor(onAccent)
            background = GradientDrawable().apply { setColor(palette.accent); cornerRadius = dp(22f) }
        } else {
            setTextColor(palette.text)
            background = GradientDrawable().apply { setStroke(px(1f), hair); cornerRadius = dp(22f) }
        }
        setOnClickListener { onClick() }
        isClickable = true
    }

    fun image(): ImageView = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(hair)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(6f)) }
        }
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Float = 0f) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = px(top) }

    fun styleWindow(a: Activity) {
        a.window.statusBarColor = bg
        a.window.navigationBarColor = bg
        if (!palette.dark) {
            @Suppress("DEPRECATION")
            a.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        }
    }
}

fun openLink(c: Context, url: String) {
    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AnnApi.abs(url))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
