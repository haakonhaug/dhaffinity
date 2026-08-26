package io.github.haakon.dhaffinity.client.gpu;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuUploadOffloaderTest {

	@Test
	void offNeverRedirects() {
		GpuUploadOffloader o = new GpuUploadOffloader();
		assertFalse(o.shouldRedirect("LodBufferContainer VBO Upload", () -> true));
		assertEquals(GpuUploadOffloader.State.OFF, o.state());
		assertEquals(1, o.passedThroughCount());
	}

	@Test
	void startingKicksOffTheWorkerAndPassesThroughUntilReady() {
		GpuUploadOffloader o = new GpuUploadOffloader();
		o.setEnabled(true);
		assertEquals(GpuUploadOffloader.State.STARTING, o.state());
		AtomicInteger starts = new AtomicInteger();
		assertFalse(o.shouldRedirect("LodBufferContainer VBO Upload", () -> {
			starts.incrementAndGet();
			return true;
		}));
		assertFalse(o.shouldRedirect("LodBufferContainer VBO Upload", () -> {
			starts.incrementAndGet();
			return true;
		}));
		assertEquals(2, starts.get(), "caller is asked each time while STARTING; it must dedupe");

		o.markReady();
		assertTrue(o.shouldRedirect("LodBufferContainer VBO Upload", () -> true));
		assertFalse(o.shouldRedirect("GLBuffer destroyAsync", () -> true), "deletes stay on the render thread (between frames)");
		assertFalse(o.shouldRedirect("LodBufferContainer Close", () -> true), "closes stay on the render thread (between frames)");
		assertTrue(o.shouldRedirect("LodBufferContainer Setup", () -> true));
		assertFalse(o.shouldRedirect("Some Other Task", () -> true), "unknown tasks stay on the render thread");
		assertFalse(o.shouldRedirect(GpuUploadOffloader.PUBLISH_TASK, () -> true), "the publish no-op must reach the render thread");
		assertFalse(o.shouldRedirect(GpuUploadOffloader.HANDBACK_TASK, () -> true), "handed-back tasks must reach the render thread");
		assertFalse(o.shouldRedirect(null, () -> true));
		assertEquals(2, o.redirectedCount());
	}

	@Test
	void failureStopsRedirectionAndIsSticky() {
		GpuUploadOffloader o = new GpuUploadOffloader();
		o.setEnabled(true);
		o.markReady();
		assertTrue(o.shouldRedirect("LodBufferContainer Setup", () -> true));
		o.markFailed("context creation failed");
		assertFalse(o.shouldRedirect("LodBufferContainer Setup", () -> true));
		o.setEnabled(true);
		assertEquals(GpuUploadOffloader.State.FAILED, o.state(), "re-enabling does not retry a failed context");
		assertTrue(o.describe().contains("FAILED"));
	}

	@Test
	void tooManyGlErrorsTurnTheFeatureOff() {
		GpuUploadOffloader o = new GpuUploadOffloader();
		o.setEnabled(true);
		o.markReady();
		assertFalse(o.recordGlErrors(0));
		assertFalse(o.recordGlErrors(GpuUploadOffloader.MAX_GL_ERRORS - 1));
		assertTrue(o.recordGlErrors(1));
		assertEquals(GpuUploadOffloader.State.FAILED, o.state());
	}

	@Test
	void disablingFromConfigStopsRedirectionButCanBeReEnabled() {
		GpuUploadOffloader o = new GpuUploadOffloader();
		o.setEnabled(true);
		o.markReady();
		o.setEnabled(false);
		assertEquals(GpuUploadOffloader.State.OFF, o.state());
		assertFalse(o.shouldRedirect("LodBufferContainer VBO Upload", () -> true));
		o.setEnabled(true);
		assertEquals(GpuUploadOffloader.State.STARTING, o.state());
	}

	@Test
	void unsupportedIsPermanent() {
		GpuUploadOffloader o = new GpuUploadOffloader();
		o.setEnabled(true);
		o.markUnsupported("rendering API is VULKAN");
		o.setEnabled(true);
		o.markReady();
		assertEquals(GpuUploadOffloader.State.UNSUPPORTED, o.state());
		assertFalse(o.shouldRedirect("LodBufferContainer VBO Upload", () -> true));
	}
}
