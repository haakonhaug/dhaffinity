package io.github.haakon.dhaffinity.client.diag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitchProfilerTest {

	private static StackTraceElement e(String cls, String method) {
		return new StackTraceElement(cls, method, null, -1);
	}

	@Test
	void classifiesDhVanillaShaderAndDriverFrames() {
		String[] dh = HitchProfiler.classify(new StackTraceElement[] {
				e("java.util.HashMap", "get"),
				e("com.seibel.distanthorizons.core.render.renderer.LodRenderer", "drawLods"),
				e("net.minecraft.class_761", "method_1")});
		assertEquals("DH rendering", dh[0]);
		assertEquals("LodRenderer.drawLods", dh[1]);

		String[] chunk = HitchProfiler.classify(new StackTraceElement[] {
				e("net.minecraft.class_846$class_851", "method_3"),
				e("net.minecraft.class_761", "method_1")});
		assertEquals("vanilla chunk building/upload", chunk[0]);

		String[] shader = HitchProfiler.classify(new StackTraceElement[] {e("net.minecraft.class_5944", "method_9")});
		assertEquals("shader compile/load", shader[0]);

		String[] gl = HitchProfiler.classify(new StackTraceElement[] {
				e("org.lwjgl.opengl.GL15C", "nglBufferData"),
				e("org.lwjgl.opengl.GL15", "glBufferData"),
				e("net.minecraft.class_10860", "method_7"),
				e("net.minecraft.class_761", "method_1")});
		assertEquals("OpenGL driver call inside vanilla GPU commands (Blaze3D)", gl[0]);
		assertTrue(gl[1].endsWith("-> nglBufferData"), gl[1]);

		String[] iris = HitchProfiler.classify(new StackTraceElement[] {e("net.irisshaders.iris.pipeline.IrisRenderingPipeline", "beginLevelRendering")});
		assertEquals("Iris shaders", iris[0]);

		String[] ours = HitchProfiler.classify(new StackTraceElement[] {e("io.github.haakon.dhaffinity.mixin.RenderThreadTaskHandlerMixin", "x")});
		assertEquals("DH Affinity mod", ours[0]);

		String[] other = HitchProfiler.classify(new StackTraceElement[] {e("com.example.somemod.Foo", "bar")});
		assertEquals("com.example (other mod)", other[0]);

		String[] jvm = HitchProfiler.classify(new StackTraceElement[] {e("java.lang.Thread", "sleep")});
		assertEquals("JVM internals", jvm[0]);
	}
}
