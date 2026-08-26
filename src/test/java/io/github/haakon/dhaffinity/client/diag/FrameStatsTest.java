package io.github.haakon.dhaffinity.client.diag;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameStatsTest {

	private static FrameStats fresh() throws Exception {
		Constructor<FrameStats> c = FrameStats.class.getDeclaredConstructor();
		c.setAccessible(true);
		return c.newInstance();
	}

	private static void frame(FrameStats s, long nanos) throws Exception {
		Method m = FrameStats.class.getDeclaredMethod("onFrame", long.class);
		m.setAccessible(true);
		m.invoke(s, nanos);
	}

	@Test
	void countsFramesHitchesAndWorst() throws Exception {
		FrameStats s = fresh();
		long t = 10_000_000_000L;
		frame(s, t);
		for (int i = 0; i < 100; i++) {
			t += 16_000_000L;
			frame(s, t);
		}
		t += 48_000_000L; // one 48 ms frame: > 2.5x the ~16 ms average and > 33 ms
		frame(s, t);
		t += 80_000_000L; // one 80 ms frame: > 4x average and > 50 ms
		frame(s, t);
		FrameStats.Summary sum = s.summary(t);
		assertEquals(102, sum.frames());
		assertTrue(sum.seconds() >= 1 && sum.seconds() <= 3, "about 1.7 s of data: " + sum.seconds());
		assertTrue(sum.fps() > 30, "fps is per populated second, not per 60 s: " + sum.fps());
		assertEquals(2, sum.hitches());
		assertEquals(1, sum.severe());
		assertEquals(80.0, sum.worstMs(), 0.01);
		assertEquals(2, sum.spikes(), "both slow frames are spikes too");
	}

	@Test
	void spikesCatchWhatAFrametimeGraphShows() throws Exception {
		FrameStats s = fresh();
		long t = 10_000_000_000L;
		frame(s, t);
		for (int i = 0; i < 100; i++) {
			t += 12_000_000L; // ~83 fps, a fast machine
			frame(s, t);
		}
		t += 28_000_000L; // 28 ms: a clear blip on the graph, but under the 33 ms hitch floor
		frame(s, t);
		t += 27_000_000L; // 27 ms: still > 2x the (slightly raised) average
		frame(s, t);
		t += 15_000_000L; // 15 ms: not a spike (< 2x)
		frame(s, t);
		FrameStats.Summary sum = s.summary(t);
		assertEquals(0, sum.hitches());
		assertEquals(2, sum.spikes());
	}

	@Test
	void aSustainedSlowerRegimeIsLearnedNotCountedAsEndlessSpikes() throws Exception {
		FrameStats s = fresh();
		long t = 10_000_000_000L;
		frame(s, t);
		for (int i = 0; i < 100; i++) {
			t += 10_000_000L; // 100 fps
			frame(s, t);
		}
		int spikesBefore = s.summary(t).spikes();
		for (int i = 0; i < 100; i++) {
			t += 24_000_000L; // shaders on: a steady 2.4x slower pace
			frame(s, t);
		}
		int spikesDuring = s.summary(t).spikes() - spikesBefore;
		assertTrue(spikesDuring < 40, "the new pace is learned within a few dozen frames, got " + spikesDuring + " spikes");
	}

	@Test
	void hitchThresholdFollowsTheAverageFrameTime() throws Exception {
		FrameStats s = fresh();
		long t = 10_000_000_000L;
		frame(s, t);
		for (int i = 0; i < 200; i++) {
			t += 31_000_000L; // a steady ~32 fps game (shaders): 31 ms frames are normal, not hitches
			frame(s, t);
		}
		t += 40_000_000L; // 40 ms: above 33 ms but only 1.3x the average
		frame(s, t);
		t += 90_000_000L; // 90 ms: 2.9x the average
		frame(s, t);
		FrameStats.Summary sum = s.summary(t);
		assertEquals(202, sum.frames());
		assertEquals(1, sum.hitches(), "only the 90 ms frame is a hitch at 32 fps");
		assertEquals(0, sum.severe());
	}

	@Test
	void windowForgetsOldSeconds() throws Exception {
		FrameStats s = fresh();
		long t = 10_000_000_000L;
		frame(s, t);
		frame(s, t + 16_000_000L);
		assertEquals(1, s.summary(t + 16_000_000L).frames());
		// 61 seconds later, without frames in between (e.g. paused), the old bucket is out of the window.
		long later = t + 61_000_000_000L;
		frame(s, later);
		assertEquals(0, s.summary(later).frames(), "gap > 5 s is not counted as a frame; old bucket expired");
	}

	@Test
	void gcDeltasAreAttributedToTheWindow() throws Exception {
		FrameStats s = fresh();
		long t = 10_000_000_000L;
		Method rec = FrameStats.class.getDeclaredMethod("recordGc", long.class, long.class, long.class);
		rec.setAccessible(true);
		frame(s, t);
		rec.invoke(s, t, 3L, 120L);
		FrameStats.Summary sum = s.summary(t + 1_000_000_000L);
		assertEquals(3, sum.gcCollections());
		assertEquals(120, sum.gcMillis());
		assertTrue(s.statusLines(t + 1_000_000_000L).get(1).startsWith("GC (last 60 s): 3 collections, 120 ms"), s.statusLines(t + 1_000_000_000L).toString());
	}
}
