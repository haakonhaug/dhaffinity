package io.github.haakon.dhaffinity.mixin;

import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Access to DH's private per-frame drain with an explicit time budget. */
@Mixin(value = RenderThreadTaskHandler.class, remap = false)
public interface RenderThreadTaskHandlerInvoker {

	@Invoker(value = "runRenderThreadTasks", remap = false)
	void dhaffinity$runTasks(long nanoMaxRunTime);
}
