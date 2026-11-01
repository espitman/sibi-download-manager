package com.espitman.sdm.ui.tutorial

internal enum class TourChapter(val title: String, val count: Int) {
    Downloads("Downloads",5), Details("Details",7), Torrents("Torrents",4), Browser("Browser",5), Files("Files",4), Settings("Settings",8)
}
internal data class TourStep(val id: String, val chapter: TourChapter, val number: Int, val title: String, val body: String)
internal object TourCatalog {
    private fun chapter(c:TourChapter,vararg entries:Pair<String,String>)=entries.mapIndexed { i,e -> TourStep("${c.name.lowercase()}-${i+1}",c,i+1,e.first,e.second) }
    val steps = chapter(TourChapter.Downloads,
        "Everything in one place" to "The top card shows total speed and connections. Filter all downloads, your queue or completed files.",
        "Paste one link or several" to "Use one link per line. These sample links never start a real download.",
        "Review before starting" to "Choose a destination, then start now or add the file to your queue.",
        "Manage a selected group" to "Select files for pause, resume, priority or removal. Keeping a file differs from deleting it.",
        "Put important files first" to "Drag the grip to reorder a file or selected group. Move to top is also available.") +
        chapter(TourChapter.Details,
        "See how your download is doing" to "Track progress, speed, time remaining and actual connections. Speed history covers 60 seconds.",
        "Pause without losing progress" to "Pause keeps downloaded bytes. Cancel asks for confirmation and retains the partial file.",
        "Use the download shortcuts" to "Set priority, copy the link or reveal a completed file in its folder.",
        "Check a saved file" to "Rename a completed file or compare its SHA-256. A mismatch warns that the bytes differ.",
        "Download at the right time" to "Set a one-time start or a daily window using the phone time zone.",
        "Limit just this download" to "Choose a rate or Unlimited. The orange indicator identifies an individual limit.",
        "Keep the progress you have" to "Check the new link first. Matching identity preserves progress; a different file needs explicit restart consent.") +
        chapter(TourChapter.Torrents,
        "Add a torrent or magnet" to "Paste a magnet link or choose a .torrent file. Metadata is reviewed before adding a download.",
        "Download only what you need" to "Select files or folders and review their total size. Seeding is optional.",
        "See files and peers" to "Overview, Files and Peers show verified progress, receive/upload rates and connected peers.",
        "Control sharing" to "Pause and resume retain progress. Stop seeding ends sharing while keeping completed files.") +
        chapter(TourChapter.Browser,
        "Browse and find a file" to "Enter an address or search. Back, forward and reload control the current tab.",
        "Choose your browsing mode" to "Private browsing does not save history. It does not hide your IP address.",
        "Keep pages in tabs" to "Create, switch or close tabs. Normal and private tabs remain separate.",
        "Find a page again" to "Use history for normal visits and bookmarks for pages you choose to keep.",
        "Send a download to SDM" to "Select an eligible download link, review its destination and add it to the queue.") +
        chapter(TourChapter.Files,
        "Find a saved file" to "Search, filter by type, change sorting and select multiple files.",
        "Open or share a saved file" to "Open, share or preview supported files. This tour uses isolated examples.",
        "Choose a new home" to "Rename or move files into a folder. Name collisions never overwrite an existing file.",
        "Review before deleting" to "Deleting a file is permanent. Removing a download record can keep the completed file.") +
        chapter(TourChapter.Settings,
        "Keep downloads organized" to "Choose a save folder, map categories or rename the folder safely. Android access needs your action.",
        "Choose how downloads run" to "Connections is a per-file maximum. Simultaneous downloads controls how many run at once.",
        "Retry temporary failures" to "Set a retry count and delay, up to 3 minutes. Manual pauses and permanent errors are not retried.",
        "Control network usage" to "Wi-Fi only pauses on mobile data. The global speed limit is shared by active downloads.",
        "Set your daily window" to "Choose resume and pause times in the phone time zone, including an overnight window.",
        "Make SDM comfortable" to "Choose alerts and theme. Language is currently English only; Reset settings asks for confirmation.",
        "Review before restoring" to "Import or export links and backups. Review duplicates and settings before restoring.",
        "Control SDM from your browser" to "Enable the panel, choose its port and approve a browser on this phone. Downloads stay on the phone.")
    fun step(chapter: TourChapter, number: Int)=steps.first { it.chapter==chapter && it.number==number }
}
internal enum class TourPhase { Hidden, Welcome, Running, Resume, Chapters, Complete }
internal data class TourState(val phase:TourPhase=TourPhase.Welcome,val chapter:TourChapter=TourChapter.Downloads,val number:Int=1,val completed:Set<TourChapter> = emptySet()) {
    val step get()=TourCatalog.step(chapter,number.coerceIn(1,chapter.count))
    fun start(chapter:TourChapter=this.chapter)=copy(phase=TourPhase.Running,chapter=chapter,number=1)
    fun next():TourState = if(number<chapter.count) copy(number=number+1) else {
        val done=completed+chapter
        copy(phase=if(done.size==TourChapter.entries.size)TourPhase.Complete else TourPhase.Chapters,completed=done)
    }
    fun nextChapter():TourState {
        val chapters=TourChapter.entries
        val next=(1..chapters.size).map{chapters[(chapter.ordinal+it)%chapters.size]}.firstOrNull{it !in completed}
        return if(next==null) copy(phase=TourPhase.Complete) else start(next)
    }
    fun back()=if(number>1)copy(number=number-1)else copy(phase=TourPhase.Chapters)
    fun close()=copy(phase=TourPhase.Hidden)
    fun forRelaunch()=if(phase==TourPhase.Running)copy(phase=TourPhase.Resume)else this
}
