package dev.legacysilicon.mixin;

import dev.legacysilicon.Autotest;
import com.mojang.blaze3d.platform.GLX;
import dev.legacysilicon.ChunkMatrix;
import dev.legacysilicon.Config;
import dev.legacysilicon.region.ChunkRegions;
import org.lwjgl.opengl.GLContext;
import dev.legacysilicon.LegacySilicon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.profiler.Profiler;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
	/** One-time report of what the GL context actually looks like. */
	@Inject(method = "initializeGame", at = @At("TAIL"))
	private void legacysilicon$reportDisplay(CallbackInfo ci) {
		MinecraftClient self = (MinecraftClient) (Object) this;
		LegacySilicon.LOGGER.info("GL: {} / {} / {}", GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION), GL11.glGetString(GL11.GL_VENDOR));
		LegacySilicon.LOGGER.info("Window {}x{}, LWJGL pixel scale factor {} (1.0 = not rendering at Retina resolution)",
			self.width, self.height, Display.getPixelScaleFactor());
		ChunkMatrix.verify();
		ChunkRegions.active = Config.regionBatching && GLX.vboSupported && GLContext.getCapabilities().OpenGL14;
		ChunkRegions.dualUpload = Autotest.ENABLED;
		LegacySilicon.LOGGER.info("Region batching: {}", ChunkRegions.active ? "on" : "off");
		if (Autotest.ENABLED) {
			Autotest.selfTestBuffers();
		}
	}

	/**
	 * Vanilla only enables the profiler while the F3 pie chart is on screen. The autotest needs it without
	 * drawing the pie (which would distort the frame), so it can force it on at the same toggle point vanilla uses.
	 */
	@Redirect(method = "runGameLoop", at = @At(value = "FIELD", target = "Lnet/minecraft/util/profiler/Profiler;enabled:Z", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
	private void legacysilicon$forceProfiler(Profiler profiler, boolean value) {
		profiler.enabled = value || Autotest.forceProfiler;
	}
}
