package io.github.haakon.dhaffinity.mixin;

import io.github.haakon.dhaffinity.core.DhAffinity;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies that the DH classes and methods we hook still exist, so an incompatible DH update
 * turns into a clear log line and a status-command message instead of a crash. Two groups of
 * hooks: "affinity" (thread-pool creation → worker pinning) and "gpu" (render-thread GL task
 * queue → off-thread upload); each degrades independently.
 */
public final class DhAffinityMixinPlugin implements IMixinConfigPlugin {

	private record Target(String group, String... methodsAndDescs) {
		/** Pairs of (name, descriptor). */
		String[] pairs() {
			return methodsAndDescs;
		}
	}

	static final String GROUP_AFFINITY = "affinity";
	static final String GROUP_GPU = "gpu";

	/** The primary affinity hook: every heavy DH pool is created here. */
	static final String EXECUTOR_CLASS = "com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker$Executor";
	static final String POOL_UTIL_CLASS = "com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil";
	static final String TASK_HANDLER_CLASS = "com.seibel.distanthorizons.core.render.RenderThreadTaskHandler";
	static final String GL_BUFFER_CLASS = "com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLBuffer";
	static final String KEYED_LEVEL_CLASS = "com.seibel.distanthorizons.common.wrappers.level.KeyedClientLevelManager_fabric";
	static final String LEVEL_WRAPPER_CLASS = "com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_fabric";
	static final String GROUP_HOTPATH = "hotpath";

	private static final Map<String, Target> TARGETS = Map.of(
			EXECUTOR_CLASS, new Target(GROUP_AFFINITY, "createThreadPool", "()Lcom/seibel/distanthorizons/core/util/threading/RateLimitedThreadPoolExecutor;"),
			POOL_UTIL_CLASS, new Target(GROUP_AFFINITY, "setupThreadPools", "()V"),
			TASK_HANDLER_CLASS, new Target(GROUP_GPU,
					"queueRunningOnRenderThread", "(Ljava/lang/String;Ljava/lang/Runnable;)V",
					"runRenderThreadTasks", "()V",
					"runRenderThreadTasks", "(J)V"),
			GL_BUFFER_CLASS, new Target(GROUP_GPU, "uploadBuffer", "(Ljava/nio/ByteBuffer;Lcom/seibel/distanthorizons/api/enums/config/EDhApiGpuUploadMethod;II)V"),
			KEYED_LEVEL_CLASS, new Target(GROUP_HOTPATH,
					"getServerKeyedLevel", "(Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;)Lcom/seibel/distanthorizons/core/level/IServerKeyedClientLevel;",
					"setServerKeyedLevel", "(Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lcom/seibel/distanthorizons/core/level/IServerKeyedClientLevel;",
					"clearKeyedLevel", "()V",
					"disable", "()V"),
			// Descriptor contains a Minecraft class whose name differs between dev (mojmap) and production
			// (intermediary); a leading '*' means "match by suffix".
			LEVEL_WRAPPER_CLASS, new Target(GROUP_HOTPATH,
					"getWrapper", "*;Z)Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;",
					"onUnload", "()V"));

	private static volatile boolean affinityHooksMissing;
	private static volatile boolean gpuHooksMissing;
	private static volatile boolean hotpathHooksMissing;
	private static final Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Whether the GL task-queue hooks could be applied (checked before creating the upload context). */
	public static boolean gpuHooksAvailable() {
		return !gpuHooksMissing;
	}

	@Override
	public void onLoad(String mixinPackage) {
		ClassLoader loader = DhAffinityMixinPlugin.class.getClassLoader();
		for (Map.Entry<String, Target> e : TARGETS.entrySet()) {
			if (loader.getResource(e.getKey().replace('.', '/') + ".class") == null) {
				markMissing(e.getValue().group());
				DhAffinity.LOG.error("DH Affinity: {} was not found in this Distant Horizons version; the '{}' hooks are disabled.", e.getKey(), e.getValue().group());
			}
		}
	}

	private static void markMissing(String group) {
		if (GROUP_AFFINITY.equals(group)) {
			affinityHooksMissing = true;
			DhAffinity.setHookState(DhAffinity.HookState.TARGET_CLASS_MISSING);
		} else if (GROUP_GPU.equals(group)) {
			gpuHooksMissing = true;
		} else {
			hotpathHooksMissing = true;
		}
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		Target target = TARGETS.get(targetClassName);
		if (target == null) {
			return true;
		}
		return switch (target.group()) {
			case GROUP_AFFINITY -> !affinityHooksMissing;
			case GROUP_GPU -> !gpuHooksMissing;
			default -> !hotpathHooksMissing;
		};
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		Target target = TARGETS.get(targetClassName);
		if (target == null) {
			return;
		}
		String[] pairs = target.pairs();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			String name = pairs[i];
			String desc = pairs[i + 1];
			boolean found = false;
			for (MethodNode method : targetClass.methods) {
				boolean descMatches = desc.startsWith("*") ? method.desc.endsWith(desc.substring(1)) : desc.equals(method.desc);
				if (name.equals(method.name) && descMatches) {
					found = true;
					break;
				}
			}
			if (!found) {
				if (GROUP_AFFINITY.equals(target.group())) {
					DhAffinity.setHookState(DhAffinity.HookState.TARGET_METHOD_MISSING);
				} else if (GROUP_GPU.equals(target.group())) {
					gpuHooksMissing = true;
				} else {
					hotpathHooksMissing = true;
				}
				DhAffinity.LOG.error("DH Affinity: {}#{}{} was not found. This Distant Horizons version changed; the '{}' hooks are disabled.",
						targetClassName, name, desc, target.group());
			}
		}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		Target target = TARGETS.get(targetClassName);
		if (target == null) {
			return;
		}
		if (LOGGED.add(targetClassName)) {
			DhAffinity.LOG.info("DH Affinity: hooked {}#{}.", targetClassName, target.pairs()[0]);
		}
		if (EXECUTOR_CLASS.equals(targetClassName) && DhAffinity.hookState() == DhAffinity.HookState.NOT_APPLIED) {
			DhAffinity.setHookState(DhAffinity.HookState.APPLIED);
		}
	}
}
