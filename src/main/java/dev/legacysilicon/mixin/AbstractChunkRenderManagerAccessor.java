package dev.legacysilicon.mixin;

import net.minecraft.client.render.world.AbstractChunkRenderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractChunkRenderManager.class)
public interface AbstractChunkRenderManagerAccessor {
	@Accessor("viewX")
	double legacysilicon$getViewX();

	@Accessor("viewY")
	double legacysilicon$getViewY();

	@Accessor("viewZ")
	double legacysilicon$getViewZ();
}
