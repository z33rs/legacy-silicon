package dev.legacysilicon.mixin;

import java.util.Map;

import net.minecraft.util.profiler.Profiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Profiler.class)
public interface ProfilerAccessor {
	@Accessor("profilingLocationTimes")
	Map<String, Long> legacysilicon$getTimes();
}
