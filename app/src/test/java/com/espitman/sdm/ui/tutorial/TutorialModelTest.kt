package com.espitman.sdm.ui.tutorial

import org.junit.Assert.*
import org.junit.Test

class TutorialModelTest {
    @Test fun nextChapterStartsTheFollowingUnfinishedChapter() {
        val finished=TourState(TourPhase.Chapters,TourChapter.Downloads,5,setOf(TourChapter.Downloads))
        val next=finished.nextChapter()
        assertEquals(TourChapter.Details,next.chapter)
        assertEquals(1,next.number)
        assertEquals(TourPhase.Running,next.phase)
        assertEquals(finished.completed,next.completed)
    }
    @Test fun nextChapterSkipsCompletedChaptersAndWrapsWithoutLosingProgress() {
        val state=TourState(TourPhase.Chapters,TourChapter.Settings,8,setOf(TourChapter.Settings,TourChapter.Downloads))
        assertEquals(TourChapter.Details,state.nextChapter().chapter)
        assertEquals(state.completed,state.nextChapter().completed)
    }
    @Test fun nextChapterCannotFinishATourWithUnfinishedChapters() {
        val state=TourState(TourPhase.Chapters,TourChapter.Settings,8,TourChapter.entries.toSet()-TourChapter.Files)
        assertEquals(TourChapter.Files,state.nextChapter().chapter)
        assertEquals(TourPhase.Complete,state.copy(completed=TourChapter.entries.toSet()).nextChapter().phase)
    }
    @Test fun everyChapterHasItsCompleteDistinctSequence() {
        assertEquals(33,TourCatalog.steps.size)
        assertEquals(33,TourCatalog.steps.map{it.id}.distinct().size)
        TourChapter.entries.forEach { c -> assertEquals((1..c.count).toList(),TourCatalog.steps.filter{it.chapter==c}.map{it.number}) }
    }
    @Test fun aChapterCanBeCompletedIndependentlyWithoutCompletingOthers() {
        var state=TourState().start(TourChapter.Files)
        repeat(4){state=state.next()}
        assertEquals(TourPhase.Chapters,state.phase)
        assertEquals(setOf(TourChapter.Files),state.completed)
        assertEquals(TourChapter.Files,state.chapter)
        state=state.start(TourChapter.Downloads)
        assertEquals(1,state.number)
        assertEquals(setOf(TourChapter.Files),state.completed)
    }
    @Test fun allChaptersCompleteOnlyAfterAllTheirLastSteps() {
        var state=TourState()
        TourChapter.entries.forEach{c->state=state.start(c);repeat(c.count){state=state.next()}}
        assertEquals(TourPhase.Complete,state.phase)
        assertEquals(TourChapter.entries.toSet(),state.completed)
        assertEquals(TourPhase.Hidden,state.close().forRelaunch().phase)
    }
    @Test fun skipPreservesPositionAndNeverMarksUnfinishedChapterComplete() {
        val state=TourState().start(TourChapter.Details).next().next()
        val closed=state.close()
        assertEquals(3,closed.number);assertTrue(closed.completed.isEmpty())
        assertEquals(TourPhase.Hidden,closed.forRelaunch().phase)
    }
    @Test fun processRecoveryOffersResumeAtTheExactStep() {
        val state=TourState(TourPhase.Running,TourChapter.Torrents,3,setOf(TourChapter.Downloads))
        assertEquals(state.copy(phase=TourPhase.Resume),state.forRelaunch())
        assertEquals(state.forRelaunch(),state.forRelaunch().forRelaunch())
    }
    @Test fun backCannotGoBeforeTheFirstStep() {
        val first=TourState().start(TourChapter.Settings)
        assertEquals(TourPhase.Chapters,first.back().phase)
        assertEquals(1,first.next().back().number)
    }
    @Test fun aCoachThatCannotFitOnEitherSideRequiresSplitLayout() {
        assertFalse(canFloatCoach(30f,290f,240f,500f,12f))
        assertTrue(canFloatCoach(30f,180f,240f,500f,12f))
        assertTrue(canFloatCoach(300f,500f,240f,600f,12f))
    }
    @Test fun sampleStateIsFreshAndNotSharedBetweenTours() {
        val first=TourSamples();first.text="changed";first.count=8;first.toggle=true
        val second=TourSamples()
        assertEquals("https://example.com/sample.zip",second.text);assertEquals(2,second.count);assertFalse(second.toggle)
    }
}
