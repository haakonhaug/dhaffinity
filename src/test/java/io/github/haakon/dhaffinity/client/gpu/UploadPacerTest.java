package io.github.haakon.dhaffinity.client.gpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPacerTest {

	private static final long FRAME = 16_000_000L;

	/** Advance N smooth frames, admitting {@code admitPerFrame} sections in each. */
	private static long smoothFrames(UploadPacer p, long t, int frames, int admitPerFrame) {
		for (int i = 0; i < frames; i++) {
			t += FRAME;
			p.onFrame(t);
			for (int a = 0; a < admitPerFrame; a++) {
				p.tryAdmit(t);
			}
		}
		return t;
	}

	@Test
	void letsEverythingThroughWhenNothingIsRendering() {
		UploadPacer p = new UploadPacer();
		for (int i = 0; i < 100; i++) {
			assertTrue(p.tryAdmit(1_000_000_000L + i), "no frame clock yet");
		}
		long t = 10_000_000_000L;
		p.onFrame(t);
		assertTrue(p.tryAdmit(t + 2_000_000_000L), "frame clock stale (>1 s): not pacing");
	}

	@Test
	void limitsSectionsPerFrameAndReopensOnTheNextFrame() {
		UploadPacer p = new UploadPacer();
		long t = 10_000_000_000L;
		p.onFrame(t);
		int admitted = 0;
		while (p.tryAdmit(t + 1)) {
			admitted++;
			assertTrue(admitted < 100, "must stop at the budget");
		}
		assertEquals((int) UploadPacer.INITIAL_BUDGET, admitted);
		assertFalse(p.tryAdmit(t + 2));
		p.onFrame(t + FRAME);
		assertTrue(p.tryAdmit(t + FRAME + 1));
	}

	@Test
	void backsOffOnRepeatedHitchesOnlyWhenSectionsWerePublished() {
		UploadPacer p = new UploadPacer();
		long t = 10_000_000_000L;
		t = smoothFrames(p, t, 30, 0); // establish an average with nothing published
		double before = p.budget();
		// Two hitch frames with NO admissions in the window: unrelated stutter, do not throttle DH.
		t += 200_000_000L;
		p.onFrame(t);
		t += 200_000_000L;
		p.onFrame(t);
		assertEquals(before, p.budget(), "no publications -> no backoff");
		assertEquals(0, p.backoffs());

		// Now publish, then hitch twice: that is on us.
		t = smoothFrames(p, t, 2, 2);
		t += 200_000_000L;
		p.onFrame(t);
		t += 200_000_000L;
		p.onFrame(t);
		assertTrue(p.budget() < before, "halved after 2 hitches in 4 frames with publications");
		assertEquals(1, p.backoffs());
	}

	@Test
	void attributionGuardForgetsOldAdmissions() {
		UploadPacer p = new UploadPacer();
		long t = 10_000_000_000L;
		t = smoothFrames(p, t, 10, 3); // DH published a lot...
		t = smoothFrames(p, t, 20, 0); // ...then nothing for 20 frames
		double before = p.budget();
		t += 200_000_000L;
		p.onFrame(t);
		t += 200_000_000L;
		p.onFrame(t);
		assertEquals(before, p.budget(), "hitches long after the last publication are not DH's fault");
		assertEquals(0, p.backoffs());
	}

	@Test
	void sustainedStutterNeverLooksNormal() {
		UploadPacer p = new UploadPacer();
		long t = 10_000_000_000L;
		t = smoothFrames(p, t, 20, 2);
		// Alternate 16 ms / 300 ms with sections being published: the budget must sit at the floor
		// and never ramp back up while the game stutters every other frame.
		long increasesBefore = -1;
		for (int i = 0; i < 200; i++) {
			t += FRAME;
			p.onFrame(t);
			p.tryAdmit(t);
			t += 300_000_000L;
			p.onFrame(t);
			p.tryAdmit(t);
			if (i == 20) {
				increasesBefore = p.increases();
				assertEquals(UploadPacer.FLOOR, p.budget(), "backed off to the floor");
			}
		}
		assertEquals(increasesBefore, p.increases(), "no increases while stuttering");
		assertEquals(UploadPacer.FLOOR, p.budget());
	}

	@Test
	void longGapIsAClockRestartNotAHitch() {
		UploadPacer p = new UploadPacer();
		long t = 10_000_000_000L;
		t = smoothFrames(p, t, 20, 2);
		t += 3_000_000_000L; // loading screen
		p.onFrame(t);
		t += 3_000_000_000L;
		p.onFrame(t);
		assertEquals(0, p.backoffs());
	}

	@Test
	void singleSpikeDoesNotBackOff() {
		UploadPacer p = new UploadPacer();
		long t = 10_000_000_000L;
		t = smoothFrames(p, t, 30, 2);
		double before = p.budget();
		t += 200_000_000L; // one spike (autosave, chunk mesh)
		p.onFrame(t);
		t = smoothFrames(p, t, 3, 2);
		assertEquals(before, p.budget());
		assertEquals(0, p.backoffs());
	}

	@Test
	void growsSlowlyWhileSmoothAndNeverBelowFloorOrAboveCap() {
		UploadPacer p = new UploadPacer();
		long t = 10_000_000_000L;
		t = smoothFrames(p, t, 25, 1);
		assertTrue(p.budget() > UploadPacer.INITIAL_BUDGET, "additive increase after clean frames");
		assertTrue(p.increases() >= 2);
		// Hammer it with hitches: floor holds.
		for (int i = 0; i < 40; i++) {
			t = smoothFrames(p, t, 1, 1);
			t += 300_000_000L;
			p.onFrame(t);
		}
		assertTrue(p.budget() >= UploadPacer.FLOOR);
		// A very long smooth stretch: cap holds.
		t = smoothFrames(p, t, 5000, 1);
		assertTrue(p.budget() <= UploadPacer.CAP);
	}

	@Test
	void reconfiguringWithTheSameSettingsDoesNotResetTheBudget() {
		UploadPacer p = new UploadPacer();
		long t = 10_000_000_000L;
		for (int i = 0; i < 25; i++) {
			p.configure(UploadPacer.Mode.AUTO, 0); // what the worker does on every task
			t = smoothFrames(p, t, 1, 1);
		}
		assertTrue(p.budget() > UploadPacer.INITIAL_BUDGET, "budget must keep growing across re-configures: " + p.budget());
		p.configure(UploadPacer.Mode.FIXED, 3);
		p.configure(UploadPacer.Mode.AUTO, 0);
		assertEquals(UploadPacer.INITIAL_BUDGET, p.budget(), "a real mode change resets");
	}

	@Test
	void fixedAndOffModes() {
		UploadPacer p = new UploadPacer();
		long t = 10_000_000_000L;
		p.configure(UploadPacer.Mode.FIXED, 2);
		p.onFrame(t);
		assertTrue(p.tryAdmit(t + 1));
		assertTrue(p.tryAdmit(t + 2));
		assertFalse(p.tryAdmit(t + 3));
		assertEquals(2.0, p.budget());
		p.configure(UploadPacer.Mode.OFF, 2);
		for (int i = 0; i < 50; i++) {
			assertTrue(p.tryAdmit(t + 4 + i));
		}
		assertTrue(p.describe().startsWith("off"));
	}
}
