package cc.huhm.splinklez.announce

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import cc.huhm.splinklez.assistant.Speech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Admin: write or edit an announcement (title, text, up to 4 pictures, up to 5 links). */
class AnnouncementEditActivity : ComponentActivity() {
    private lateinit var ui: Ui
    private lateinit var title: EditText
    private lateinit var body: EditText
    private lateinit var imageRow: LinearLayout
    private lateinit var linkBox: LinearLayout
    private lateinit var save: TextView
    private lateinit var status: TextView
    private val images = ArrayList<String>()
    private val linkRows = ArrayList<Pair<EditText, EditText>>()
    private var editId: String? = null
    private var busy = false

    private val pick = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> upload(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        ui.styleWindow(this)
        editId = intent.getStringExtra(AnnouncementActivity.EXTRA_ID)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = ui.px(22f)
            setPadding(p, ui.px(20f), p, ui.px(28f))
        }
        col.addView(ui.text("管理員", 11f, ui.palette.accent).apply { letterSpacing = 0.12f })
        col.addView(ui.text(if (editId == null) "新增公告" else "編輯公告", 26f, bold = true, serif = true), ui.lp(top = 4f))
        title = field("標題", 60, multi = false)
        body = field("內容", 4000, multi = true)
        col.addView(label("標題")); col.addView(title, ui.lp(top = 4f))
        col.addView(label("內容")); col.addView(body, ui.lp(top = 4f))

        col.addView(label("圖片（最多 4 張）"))
        imageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(imageRow) }, ui.lp(top = 6f))

        col.addView(label("連結（最多 5 個）"))
        linkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(linkBox, ui.lp(top = 2f))
        col.addView(ui.text("＋ 連結", 14f, ui.palette.accent).apply {
            setPadding(0, ui.px(10f), ui.px(12f), ui.px(10f))
            setOnClickListener { if (linkRows.size < 5) addLink("", "") }
        }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT))

        status = ui.text("", 14f, ui.palette.accent)
        col.addView(status, ui.lp(top = 8f))
        save = ui.button(if (editId == null) "發布公告" else "儲存修改", true) { submit() }
        col.addView(save, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 12f))

        val scroll = ScrollView(this).apply { setBackgroundColor(ui.bg); addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        renderImages()

        editId?.let { id ->
            setBusy(true, "載入中…")
            lifecycleScope.launch {
                try {
                    val a = AnnApi.get(id)
                    title.setText(a.title)
                    body.setText(a.body)
                    images.clear(); images.addAll(a.images)
                    renderImages()
                    a.links.forEach { addLink(it.label, it.url) }
                    setBusy(false, "")
                } catch (e: Exception) {
                    setBusy(false, Speech.friendlyError(e))
                }
            }
        }
    }

    private fun label(s: String) = ui.text(s, 13f, ui.palette.dim).apply {
        layoutParams = ui.lp(top = 16f)
    }

    private fun field(hint: String, max: Int, multi: Boolean) = EditText(this).apply {
        this.hint = hint
        setTextColor(ui.palette.text)
        setHintTextColor(ui.palette.dim)
        textSize = 16f
        filters = arrayOf(InputFilter.LengthFilter(max))
        val p = ui.px(12f)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply { setStroke(ui.px(1f), ui.hair); cornerRadius = ui.dp(10f) }
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
    }

    private fun addLink(labelText: String, url: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val l = field("連結文字（選填）", 40, multi = false).apply { setText(labelText) }
        val u = field("https://…", 500, multi = false).apply { setText(url); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val pair = l to u
        row.addView(l, ui.lp(top = 8f))
        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        urlRow.addView(u, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        urlRow.addView(ui.text("移除", 14f, ui.palette.dim).apply {
            setPadding(ui.px(12f), ui.px(10f), 0, ui.px(10f))
            setOnClickListener { linkBox.removeView(row); linkRows.remove(pair) }
        })
        row.addView(urlRow, ui.lp(top = 4f))
        linkRows.add(pair)
        linkBox.addView(row)
    }

    private fun renderImages() {
        imageRow.removeAllViews()
        val size = ui.px(84f)
        for (url in images.toList()) {
            val frame = FrameLayout(this)
            val iv = ui.image()
            frame.addView(iv, FrameLayout.LayoutParams(size, size))
            frame.addView(ui.text("×", 14f, ui.onAccent).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(ui.palette.accent); cornerRadius = ui.dp(12f) }
                contentDescription = "移除圖片"
                setOnClickListener { images.remove(url); renderImages() }
            }, FrameLayout.LayoutParams(ui.px(24f), ui.px(24f), Gravity.TOP or Gravity.END))
            imageRow.addView(frame, LinearLayout.LayoutParams(size, size).apply { marginEnd = ui.px(10f) })
            lifecycleScope.launch { AnnApi.bitmap(url, 300)?.let { iv.setImageBitmap(it) } }
        }
        if (images.size < 4) {
            imageRow.addView(ui.text("＋ 圖片", 13f, ui.palette.dim).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setStroke(ui.px(1f), ui.hair); cornerRadius = ui.dp(6f) }
                setOnClickListener { if (!busy) pick.launch("image/*") }
            }, LinearLayout.LayoutParams(size, size))
        }
    }

    private fun setBusy(b: Boolean, msg: String) {
        busy = b
        save.alpha = if (b) 0.5f else 1f
        status.text = msg
    }

    private fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            for (uri in uris.take(4 - images.size)) {
                setBusy(true, "上傳圖片中…")
                try {
                    val jpeg = withContext(Dispatchers.Default) { shrink(uri) }
                    images.add(AnnApi.upload(jpeg))
                    renderImages()
                    setBusy(false, "")
                } catch (e: Exception) {
                    setBusy(false, if (e is IllegalStateException) e.message ?: "圖片處理失敗" else Speech.friendlyError(e))
                    break
                }
            }
        }
    }

    /** ≤1280 px JPEG under 600 KB (the server's limit), with the photo turned the right way up. */
    private fun shrink(uri: Uri): ByteArray {
        val bm: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                val s = info.size
                val scale = minOf(1f, 1280f / maxOf(s.width, s.height))
                d.setTargetSize((s.width * scale).toInt().coerceAtLeast(1), (s.height * scale).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1280) sample *= 2
            val raw = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: throw IllegalStateException("讀不到這張圖片")
            val scale = minOf(1f, 1280f / maxOf(raw.width, raw.height))
            if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
        }
        for (q in intArrayOf(86, 75, 62, 50)) {
            val out = ByteArrayOutputStream()
            bm.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= 600 * 1024) return out.toByteArray()
        }
        throw IllegalStateException("圖片太大，換一張小一點的")
    }

    private fun submit() {
        if (busy) return
        val t = title.text.toString().trim()
        val b = body.text.toString().trim()
        if (t.isEmpty()) { status.text = "請填標題"; return }
        if (b.isEmpty()) { status.text = "請填內容"; return }
        val links = linkRows.map { (l, u) -> AnnLink(l.text.toString().trim(), u.text.toString().trim()) }.filter { it.url.isNotEmpty() }
        setBusy(true, if (editId == null) "發布中…" else "儲存中…")
        lifecycleScope.launch {
            try {
                val a = editId?.let { AnnApi.update(it, t, b, images, links) } ?: AnnApi.create(t, b, images, links)
                // The author has seen it; don't pop it up on this phone.
                SeenStore.add(this@AnnouncementEditActivity, a.id)
                Toast.makeText(this@AnnouncementEditActivity, if (editId == null) "公告已發布" else "已儲存", Toast.LENGTH_SHORT).show()
                if (editId == null) startActivity(Intent(this@AnnouncementEditActivity, AnnouncementActivity::class.java).putExtra(AnnouncementActivity.EXTRA_ID, a.id))
                finish()
            } catch (e: Exception) {
                setBusy(false, Speech.friendlyError(e))
            }
        }
    }
}
