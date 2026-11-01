package com.espitman.sdm.ui.tutorial

import android.content.Context

/** Only tutorial preferences are writable here. No download/settings repository is used. */
internal class TutorialStore(context:Context) {
    private val prefs=context.getSharedPreferences("sdm_tutorial_v1",Context.MODE_PRIVATE)
    fun read():TourState = runCatching {
        TourState(TourPhase.valueOf(prefs.getString("phase",TourPhase.Welcome.name)!!),
            TourChapter.valueOf(prefs.getString("chapter",TourChapter.Downloads.name)!!),
            prefs.getInt("number",1),prefs.getStringSet("completed",emptySet()).orEmpty().mapNotNull { name -> TourChapter.entries.find{it.name==name} }.toSet())
            .let { it.copy(number=it.number.coerceIn(1,it.chapter.count)) }.forRelaunch()
    }.getOrElse { TourState() }
    fun save(state:TourState) { check(prefs.edit().putString("phase",state.phase.name).putString("chapter",state.chapter.name).putInt("number",state.number)
        .putStringSet("completed",state.completed.map{it.name}.toSet()).commit()) }
}
