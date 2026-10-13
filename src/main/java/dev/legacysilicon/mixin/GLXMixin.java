package dev.legacysilicon.mixin;

import com.mojang.blaze3d.platform.GLX;
import dev.legacysilicon.region.ChunkRegions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GLX.class)
public abstract class GLXMixin {
	/** Region batching is built on VBOs, so keep the VBO path on even if "Use VBOs" is off in video settings. */
	@Inject(method = "supportsVbo", at = @At("HEAD"), cancellable = true)
	private static void legacysilicon$forceVbo(CallbackInfoReturnable<Boolean> cir) {
		if (ChunkRegions.active && GLX.vboSupported) {
			cir.setReturnValue(true);
		}
	}
}
