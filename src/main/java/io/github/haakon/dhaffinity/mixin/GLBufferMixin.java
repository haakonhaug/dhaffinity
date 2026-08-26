package io.github.haakon.dhaffinity.mixin;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLBuffer;
import io.github.haakon.dhaffinity.client.gpu.GpuUploadWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * After DH has issued a buffer upload on the GPU upload thread, wait for the GPU to finish it
 * before returning: DH completes its "buffer ready" future inside the very same task, and the
 * render thread may draw the buffer right after that.
 */
@Mixin(value = GLBuffer.class, remap = false)
public abstract class GLBufferMixin {

	@Inject(method = "uploadBuffer", at = @At("RETURN"), remap = false, require = 0)
	private void dhaffinity$fenceAfterUpload(ByteBuffer bb, EDhApiGpuUploadMethod uploadMethod, int maxExpansionSize, int bufferHint, CallbackInfo ci) {
		GpuUploadWorker.INSTANCE.afterUploadOnWorker();
	}
}
