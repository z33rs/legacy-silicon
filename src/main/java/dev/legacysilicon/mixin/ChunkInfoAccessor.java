package dev.legacysilicon.mixin;

import net.minecraft.client.world.BuiltChunk;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** WorldRenderer.ChunkInfo is package-private; this exposes exactly what the visibility search uses. */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer$ChunkInfo")
public interface ChunkInfoAccessor {
	@Accessor("chunk")
	BuiltChunk legacysilicon$chunk();

	@Accessor("direction")
	Direction legacysilicon$direction();

	@Accessor("field_13539")
	byte legacysilicon$flags();

	@Accessor("propagationLevel")
	int legacysilicon$level();

	@Invoker("method_12340")
	void legacysilicon$addFlags(byte flags, Direction direction);
}
