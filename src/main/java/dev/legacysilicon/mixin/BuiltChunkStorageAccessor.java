package dev.legacysilicon.mixin;

import net.minecraft.client.render.BuiltChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BuiltChunkStorage.class)
public interface BuiltChunkStorageAccessor {
	@Accessor("sizeX")
	int legacysilicon$sizeX();

	@Accessor("sizeY")
	int legacysilicon$sizeY();

	@Accessor("sizeZ")
	int legacysilicon$sizeZ();
}
