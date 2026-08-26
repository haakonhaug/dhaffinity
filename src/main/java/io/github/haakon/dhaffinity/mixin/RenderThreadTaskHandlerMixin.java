package io.github.haakon.dhaffinity.mixin;

import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import io.github.haakon.dhaffinity.client.gpu.GpuUploadWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every OpenGL job Distant Horizons needs done goes through
 * {@code RenderThreadTaskHandler#queueRunningOnRenderThread(name, runnable)}. Buffer jobs are
 * diverted to the GPU upload thread; everything else keeps its normal path. The per-frame drain
 * on the render thread can also be given a smaller time budget when uploads are not diverted.
 */
@Mixin(value = RenderThreadTaskHandler.class, remap = false)
public abstract class RenderThreadTaskHandlerMixin {

	@Inject(method = "queueRunningOnRenderThread", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void dhaffinity$divertBufferTasks(String name, Runnable renderCall, CallbackInfo ci) {
		if (GpuUploadWorker.INSTANCE.tryRedirect(name, renderCall)) {
			ci.cancel();
		}
	}

	@Inject(method = "runRenderThreadTasks()V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void dhaffinity$overrideBudget(CallbackInfo ci) {
		GpuUploadWorker.INSTANCE.acknowledgeOnRenderThread();
		long nanos = GpuUploadWorker.INSTANCE.renderThreadBudgetNanos();
		if (nanos > 0 && this instanceof RenderThreadTaskHandlerInvoker invoker) {
			invoker.dhaffinity$runTasks(nanos);
			ci.cancel();
		}
	}
}
