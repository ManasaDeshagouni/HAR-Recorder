package com.example.myapplication.presentation

import android.content.Context
import java.util.UUID

object Prefs {
    private const val FILE = "har"
    private const val KEY_SEQ = "next_seq"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_SHOULD_RECORD = "should_record"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String {
        val p = sp(ctx)
        p.getString(KEY_DEVICE, null)?.let { return it }
        val id = UUID.randomUUID().toString().take(8)
        p.edit().putString(KEY_DEVICE, id).commit()
        return id
    }

    fun takeNextSeq(ctx: Context): Long {
        val p = sp(ctx)
        val seq = p.getLong(KEY_SEQ, 0L)
        p.edit().putLong(KEY_SEQ, seq + 1).commit()
        return seq
    }

    fun shouldRecord(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOULD_RECORD, false)

    fun setShouldRecord(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_SHOULD_RECORD, value).commit()
    }

    // ---------- live upload cursor ----------

    private const val KEY_LIVE_SEQ = "live_seq"
    private const val KEY_LIVE_OFFSET = "live_offset"
    private const val KEY_EVENTS_OFFSET = "events_offset"

    /** The next file number, without using it up (takeNextSeq would). */
    fun peekNextSeq(ctx: Context): Long = sp(ctx).getLong(KEY_SEQ, 0L)

    fun hasLiveCursor(ctx: Context): Boolean = sp(ctx).contains(KEY_LIVE_SEQ)
    fun liveSeq(ctx: Context): Long = sp(ctx).getLong(KEY_LIVE_SEQ, 0L)
    fun liveOffset(ctx: Context): Long = sp(ctx).getLong(KEY_LIVE_OFFSET, 0L)
    fun eventsOffset(ctx: Context): Long = sp(ctx).getLong(KEY_EVENTS_OFFSET, 0L)

    // apply() (not commit()) is fine here: if the cursor ends up slightly behind
    // after a crash, some data is just sent twice, and the laptop ignores repeats.
    fun setLiveCursor(ctx: Context, seq: Long, offset: Long) {
        sp(ctx).edit().putLong(KEY_LIVE_SEQ, seq).putLong(KEY_LIVE_OFFSET, offset).apply()
    }

    fun setEventsOffset(ctx: Context, offset: Long) {
        sp(ctx).edit().putLong(KEY_EVENTS_OFFSET, offset).apply()
    }

    // ---------- NEW: who wears the watch ----------

    private const val KEY_PARTICIPANT = "participant"
    private const val KEY_WRIST = "wrist"

    /** "P03", or null if nobody has been set yet. */
    fun participant(ctx: Context): String? = sp(ctx).getString(KEY_PARTICIPANT, null)
    fun wrist(ctx: Context): String = sp(ctx).getString(KEY_WRIST, null) ?: "left"
    fun hasProfile(ctx: Context): Boolean = participant(ctx) != null

    fun setProfile(ctx: Context, participant: String, wrist: String) {
        sp(ctx).edit().putString(KEY_PARTICIPANT, participant).putString(KEY_WRIST, wrist).commit()
    }

    // ---------- NEW: activity labels ----------

    private const val KEY_LABEL_NEXT = "label_next_id"
    private const val KEY_LABEL_ID = "label_id"
    private const val KEY_LABEL_ACTIVITY = "label_activity"
    private const val KEY_LABEL_START = "label_start_wall_ms"
    private const val KEY_RECENT = "recent_activities"
    private const val KEY_CUSTOM = "custom_activities"

    fun takeNextLabelId(ctx: Context): Long {
        val p = sp(ctx)
        val id = p.getLong(KEY_LABEL_NEXT, 1L)
        p.edit().putLong(KEY_LABEL_NEXT, id + 1).commit()
        return id
    }

    /** The activity being labeled right now (survives closing the app and reboots), or null. */
    fun activeLabel(ctx: Context): Labels.Active? {
        val p = sp(ctx)
        if (!p.contains(KEY_LABEL_ID)) return null
        return Labels.Active(
            p.getLong(KEY_LABEL_ID, 0L),
            p.getString(KEY_LABEL_ACTIVITY, null) ?: "unknown",
            p.getLong(KEY_LABEL_START, 0L),
        )
    }

    // commit(), not apply(): the label state must be on disk before we carry on
    fun setActiveLabel(ctx: Context, a: Labels.Active?) {
        val e = sp(ctx).edit()
        if (a == null) {
            e.remove(KEY_LABEL_ID).remove(KEY_LABEL_ACTIVITY).remove(KEY_LABEL_START)
        } else {
            e.putLong(KEY_LABEL_ID, a.id).putString(KEY_LABEL_ACTIVITY, a.activity)
                .putLong(KEY_LABEL_START, a.startWallMs)
        }
        e.commit()
    }

    private fun list(ctx: Context, key: String): List<String> =
        (sp(ctx).getString(key, null) ?: "").split(',').filter { it.isNotEmpty() }

    /** The last 3 activities used, newest first. */
    fun recentActivities(ctx: Context) = list(ctx, KEY_RECENT)

    /** Activities typed in with "Other…". */
    fun customActivities(ctx: Context) = list(ctx, KEY_CUSTOM)

    fun useActivity(ctx: Context, activity: String, custom: Boolean) {
        val recent = (listOf(activity) + recentActivities(ctx)).distinct().take(3)
        val e = sp(ctx).edit().putString(KEY_RECENT, recent.joinToString(","))
        if (custom) e.putString(KEY_CUSTOM, (customActivities(ctx) + activity).distinct().joinToString(","))
        e.apply()
    }
}