package dev.legacysilicon.mixin;

import java.nio.FloatBuffer;

import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BuiltChunk.class)
public interface BuiltChunkAccessor {
	/** The constant per-chunk seam matrix vanilla multiplies in (translate -8, scale 1.000001, translate 8). */
	@Accessor("field_11080")
	FloatBuffer legacysilicon$getChunkMatrix();
}
