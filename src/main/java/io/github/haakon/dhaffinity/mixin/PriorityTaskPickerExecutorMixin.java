package io.github.haakon.dhaffinity.mixin;

import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import io.github.haakon.dhaffinity.core.WrappingThreadFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Every heavy Distant Horizons pool (World Gen, LOD Builder, Render Loader, IO, Update Propagator,
 * Network Compression) is a {@code RateLimitedThreadPoolExecutor} built by
 * {@code PriorityTaskPicker.Executor#createThreadPool()}, also whenever DH's thread count changes.
 * The pool has no threads yet when the method returns, so swapping its factory here wraps every
 * worker it will ever create.
 *
 * <p>{@code DhThreadFactory#newThread} itself cannot be hooked: DH's own mixin plugin loads that
 * class before Mixin transforms anything.
 */
@Mixin(value = PriorityTaskPicker.Executor.class, remap = false)
public abstract class PriorityTaskPickerExecutorMixin {

	@Inject(method = "createThreadPool", at = @At("RETURN"), remap = false, require = 0)
	private void dhaffinity$wrapPoolFactory(CallbackInfoReturnable<?> cir) {
		if (cir.getReturnValue() instanceof ThreadPoolExecutor pool) {
			pool.setThreadFactory(WrappingThreadFactory.wrap(pool.getThreadFactory()));
		}
	}
}
