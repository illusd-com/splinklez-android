package cc.huhm.splinklez.data

import org.json.JSONArray
import org.json.JSONObject

// Community (社群) channels, messages and per-user settings.

data class ChannelInfo(
    val id: String,
    val kind: String,
    val name: String,
    val county: String?,
    val topic: String?,
    val status: String,
    val lastMessageAt: Long? = null,
    val joined: Boolean = false,
    val notify: Boolean = true,
    val role: String? = null,
    val mine: Boolean = false,
    val rules: String? = null,
)

data class ChannelMe(
    val userId: String,
    val role: String?,
    val isAdmin: Boolean,
    val ageGroup: String?,
    val joined: Boolean,
    val notify: Boolean,
    val mutedAt: Long?,
    val banned: Boolean,
    val perms: Map<String, Boolean>,
) {
    fun can(p: String): Boolean = perms[p] == true

    /** Moderators and admins. */
    val staff: Boolean get() = isAdmin || role != null
}

data class CmMessage(
    val id: String,
    val kind: String,
    val userId: String?,
    val author: String?,
    val authorRole: String?,
    val body: String?,
    val placeholder: String?,
    val state: String,
    val pinned: Boolean,
    val createdAt: Long,
    val rev: Long,
    val mine: Boolean,
    val original: String? = null,
)

data class ChannelPage(
    val channel: ChannelInfo,
    val me: ChannelMe,
    val pinnedIds: List<String>,
    val messages: List<CmMessage>,
    val cursor: Long,
)

data class ChannelPoll(
    val messages: List<CmMessage>,
    val cursor: Long,
    val mutedAt: Long?,
)

data class ChannelRequest(
    val id: String,
    val name: String,
    val topic: String,
    val status: String,
    val note: String?,
    val communityId: String?,
)

data class CommunityViewer(
    val name: String,
    val isAdmin: Boolean,
    val county: String?,
    val ageGroup: String?,
    val rulesAccepted: Boolean,
    val countyCooldownMs: Long,
    val bannedEverywhere: Boolean,
) {
    val onboarded: Boolean get() = rulesAccepted && ageGroup != null && county != null
}

data class CommunityHome(
    val viewer: CommunityViewer,
    val rules: List<String>,
    val channels: List<ChannelInfo>,
    val requests: List<ChannelRequest>,
)

data class TtsVoice(
    val id: String,
    val label: String,
)

val DEFAULT_TTS_VOICES: List<TtsVoice> = listOf(
    TtsVoice("zh-TW-YunJheNeural", "雲哲（男聲，預設）"),
    TtsVoice("zh-TW-HsiaoChenNeural", "曉臻（女聲）"),
    TtsVoice("zh-TW-HsiaoYuNeural", "曉雨（女聲）"),
)

data class UserSettings(
    val ttsVoice: String = "zh-TW-YunJheNeural",
    val ttsRate: Int = 0,
    val autoRead: Boolean = false,
    val cards: Map<String, Boolean> = emptyMap(),
    val tools: Map<String, Boolean> = emptyMap(),
) {
    fun cardOn(id: String): Boolean = cards[id] != false

    fun toolOn(id: String): Boolean = tools[id] != false
}

data class SettingsBundle(
    val settings: UserSettings,
    val voices: List<TtsVoice>,
    val persisted: Boolean,
)

internal object CommunityJson {
    private fun JSONObject.s(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k)!!.takeIf { it.isNotBlank() }

    private fun JSONObject.l(k: String): Long? =
        if (!has(k) || isNull(k)) null else optLong(k)

    private inline fun <T> JSONArray?.objs(f: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(f) }
    }

    fun channel(j: JSONObject): ChannelInfo = ChannelInfo(
        id = j.optString("id"),
        kind = j.optString("kind"),
        name = j.optString("name"),
        county = j.s("county"),
        topic = j.s("topic"),
        status = j.optString("status", "active"),
        lastMessageAt = j.l("lastMessageAt"),
        joined = j.optBoolean("joined"),
        notify = j.optBoolean("notify", true),
        role = j.s("role"),
        mine = j.optBoolean("mine"),
        rules = j.s("rules"),
    )

    fun message(j: JSONObject): CmMessage = CmMessage(
        id = j.optString("id"),
        kind = j.optString("kind"),
        userId = j.s("userId"),
        author = j.s("author"),
        authorRole = j.s("authorRole"),
        body = j.s("body"),
        placeholder = j.s("placeholder"),
        state = j.optString("state", "ok"),
        pinned = j.optBoolean("pinned"),
        createdAt = j.optLong("createdAt"),
        rev = j.optLong("rev"),
        mine = j.optBoolean("mine"),
        original = j.s("original"),
    )

    fun messages(a: JSONArray?): List<CmMessage> = a.objs(::message)

    fun home(j: JSONObject): CommunityHome {
        val v = j.optJSONObject("viewer") ?: JSONObject()
        val rules = j.optJSONArray("rules")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val viewer = CommunityViewer(
            name = v.optString("name", "讀者"),
            isAdmin = v.optBoolean("isAdmin"),
            county = v.s("county"),
            ageGroup = v.s("ageGroup"),
            rulesAccepted = v.optBoolean("rulesAccepted"),
            countyCooldownMs = v.optLong("countyCooldownMs"),
            bannedEverywhere = v.optBoolean("bannedEverywhere"),
        )
        val channels = j.optJSONArray("channels").objs(::channel)
        val requests = j.optJSONArray("requests").objs { r ->
            ChannelRequest(
                id = r.optString("id"),
                name = r.optString("name"),
                topic = r.optString("topic"),
                status = r.optString("status"),
                note = r.s("note"),
                communityId = r.s("communityId"),
            )
        }
        return CommunityHome(viewer, rules, channels, requests)
    }

    fun page(j: JSONObject): ChannelPage {
        val me = j.optJSONObject("me") ?: JSONObject()
        val perms = me.optJSONObject("perms")?.let { p -> p.keys().asSequence().associateWith { p.optBoolean(it) } } ?: emptyMap()
        val pinned = j.optJSONArray("pinnedIds")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return ChannelPage(
            channel = channel(j.optJSONObject("channel") ?: JSONObject()),
            me = ChannelMe(
                userId = me.optString("userId"),
                role = me.s("role"),
                isAdmin = me.optBoolean("isAdmin"),
                ageGroup = me.s("ageGroup"),
                joined = me.optBoolean("joined"),
                notify = me.optBoolean("notify", true),
                mutedAt = me.l("mutedAt"),
                banned = me.optBoolean("banned"),
                perms = perms,
            ),
            pinnedIds = pinned,
            messages = messages(j.optJSONArray("messages")),
            cursor = j.optLong("cursor"),
        )
    }

    fun settings(j: JSONObject): SettingsBundle {
        val o = j.optJSONObject("settings") ?: JSONObject()
        val voices = j.optJSONArray("voices").objs { v -> TtsVoice(v.optString("id"), v.optString("label")) }
            .ifEmpty { DEFAULT_TTS_VOICES }
        return SettingsBundle(
            settings = UserSettings(
                ttsVoice = o.optString("ttsVoice", DEFAULT_TTS_VOICES[0].id),
                ttsRate = o.optInt("ttsRate"),
                autoRead = o.optBoolean("autoRead"),
                cards = flags(o.optJSONObject("cards")),
                tools = flags(o.optJSONObject("tools")),
            ),
            voices = voices,
            persisted = j.optBoolean("persisted", true),
        )
    }

    /** Missing keys count as "on". */
    private fun flags(o: JSONObject?): Map<String, Boolean> {
        if (o == null) return emptyMap()
        val m = mutableMapOf<String, Boolean>()
        for (k in o.keys()) m[k] = o.optBoolean(k, true)
        return m
    }
}
