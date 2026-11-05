package com.example.myapplication.presentation

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.Locale

/**
 * Activity labels. The sensors never stop for a label: a label is just two lines in the
 * diary (events.log) that say "from here to here, the person was doing X".
 *
 *   LABEL_START    id=12 activity=walking participant=P03 wrist=left
 *   LABEL_END      id=12 activity=walking reason=user
 *   LABEL_DISCARD  id=12                    (only if the person tapped "Discard")
 *
 * Every diary line also has the sensor clock time, so the laptop can match labels
 * to readings exactly.
 */
object Labels {

    val DEFAULTS = listOf(
        "walking", "running", "sitting", "standing", "lying_down",
        "stairs_up", "stairs_down", "cycling", "eating", "typing", "brushing_teeth",
    )

    data class Active(val id: Long, val activity: String, val startWallMs: Long)

    private fun diary(ctx: Context): EventLog {
        val boot = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
        return EventLog(File(ctx.filesDir, "har/events.log"), boot)
    }

    /** "Stairs up!" -> "stairs_up": safe to write in the diary and to use as a column value. */
    fun slug(name: String): String =
        name.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)

    /** "stairs_up" -> "Stairs up": for the screen. */
    fun pretty(slug: String): String =
        slug.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** Every activity to offer, the most recently used first. */
    fun choices(ctx: Context): List<String> =
        (Prefs.recentActivities(ctx) + DEFAULTS + Prefs.customActivities(ctx)).distinct()

    fun active(ctx: Context): Active? = Prefs.activeLabel(ctx)

    fun start(ctx: Context, activity: String) {
        if (active(ctx) != null) stop(ctx, "replaced")       // never two labels at once
        val id = Prefs.takeNextLabelId(ctx)
        val who = Prefs.participant(ctx) ?: "unknown"
        diary(ctx).log("LABEL_START", "id=$id activity=$activity participant=$who wrist=${Prefs.wrist(ctx)}")
        Prefs.setActiveLabel(ctx, Active(id, activity, System.currentTimeMillis()))
        Prefs.useActivity(ctx, activity, custom = activity !in DEFAULTS)
    }

    /** Ends the current label. reason: user, recording_stopped or replaced. */
    fun stop(ctx: Context, reason: String = "user"): Active? {
        val a = active(ctx) ?: return null
        diary(ctx).log("LABEL_END", "id=${a.id} activity=${a.activity} reason=$reason")
        Prefs.setActiveLabel(ctx, null)
        return a
    }

    /** Marks a label as a mistake. The sensor data itself is never deleted. */
    fun discard(ctx: Context, id: Long) {
        diary(ctx).log("LABEL_DISCARD", "id=$id")
    }

    fun setProfile(ctx: Context, participant: String, wrist: String) {
        Prefs.setProfile(ctx, participant, wrist)
        diary(ctx).log("PROFILE", "participant=$participant wrist=$wrist")
    }
}