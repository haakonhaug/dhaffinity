package io.github.haakon.dhaffinity.mixin;

import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import io.github.haakon.dhaffinity.core.WrappingThreadFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * DH's few single-thread pools (Cleanup, Beacon Culling, Full Data Migration, Network Client
 * Handler) are plain {@link ThreadPoolExecutor}s created in {@code ThreadPoolUtil#setupThreadPools};
 * their factories are swapped once that method has built them.
 */
@Mixin(value = ThreadPoolUtil.class, remap = false)
public abstract class ThreadPoolUtilMixin {

	@Inject(method = "setupThreadPools", at = @At("TAIL"), remap = false, require = 0)
	private static void dhaffinity$wrapSmallPools(CallbackInfo ci) {
		wrap(ThreadPoolUtil.getCleanupExecutor());
		wrap(ThreadPoolUtil.getBeaconCullingExecutor());
		wrap(ThreadPoolUtil.networkClientHandlerExecutor());
		wrap(ThreadPoolUtil.getFullDataMigrationExecutor());
	}

	private static void wrap(ThreadPoolExecutor pool) {
		if (pool != null) {
			pool.setThreadFactory(WrappingThreadFactory.wrap(pool.getThreadFactory()));
		}
	}
}
