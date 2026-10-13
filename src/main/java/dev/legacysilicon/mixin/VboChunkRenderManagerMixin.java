package dev.legacysilicon.mixin;

import java.nio.FloatBuffer;

import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.legacysilicon.ChunkMatrix;
import dev.legacysilicon.Config;
import dev.legacysilicon.DrawTiming;
import dev.legacysilicon.VaoHolder;
import dev.legacysilicon.region.ChunkRegions;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexBuffer;
import net.minecraft.client.render.world.AbstractChunkRenderManager;
import net.minecraft.client.render.world.VboChunkRenderManager;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.APPLEVertexArrayObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla, per visible chunk per layer (~12 driver calls):
 *   pushMatrix, translate, multMatrix(seam matrix), bindBuffer, vertex/color/texcoord pointers,
 *   clientActiveTexture, lightmap texcoord pointer, clientActiveTexture, drawArrays, popMatrix
 *
 * Here (3 calls): loadMatrix (bit-exact CPU result, verified against the driver at startup),
 *   bindVertexArray (VAO already holding the same pointers), drawArrays.
 * Chunk order, vertex data and draw parameters are untouched, so output is pixel-identical.
 */
@Mixin(VboChunkRenderManager.class)
public abstract class VboChunkRenderManagerMixin extends AbstractChunkRenderManager {
	@Unique private static final FloatBuffer FB = BufferUtils.createFloatBuffer(16);
	@Unique private static final float[] BASE = new float[16];
	@Unique private static final float[] SEAM = new float[16];
	@Unique private static final float[] OUT = new float[16];
	@Unique private static final float[] TMP = new float[16];
	@Unique private static int vaoSupport = -1;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void legacysilicon$fastRender(RenderLayer layer, CallbackInfo ci) {
		if (!Config.fastChunkDraw) {
			return;
		}
		ci.cancel();
		if (!this.field_10667) {
			return;
		}
		if (ChunkRegions.active && Config.debugRegions != 0) {
			AbstractChunkRenderManagerAccessor v = (AbstractChunkRenderManagerAccessor) this;
			ChunkRegions.render(layer, this.helpers, v.legacysilicon$getViewX(), v.legacysilicon$getViewY(), v.legacysilicon$getViewZ());
			this.helpers.clear();
			return;
		}
		if (vaoSupport < 0) {
			vaoSupport = GLContext.getCapabilities().GL_APPLE_vertex_array_object ? 1 : 0;
		}

		int n = this.helpers.size();
		int matrixMode = Config.debugMatrixMode >= 0 ? Config.debugMatrixMode : (ChunkMatrix.variant >= 0 ? 1 : 2);
		boolean useVao = Config.debugBindMode >= 0 ? Config.debugBindMode == 1 && vaoSupport == 1 : Config.chunkVao && vaoSupport == 1;
		boolean timing = DrawTiming.enabled;

		AbstractChunkRenderManagerAccessor view = (AbstractChunkRenderManagerAccessor) this;
		double vx = view.legacysilicon$getViewX();
		double vy = view.legacysilicon$getViewY();
		double vz = view.legacysilicon$getViewZ();

		GlStateManager.pushMatrix();
		if (matrixMode != 0 && n > 0) {
			FB.clear();
			GlStateManager.getFloat(GL11.GL_MODELVIEW_MATRIX, FB);
			FB.get(BASE);
			FloatBuffer seam = ((BuiltChunkAccessor) this.helpers.get(0)).legacysilicon$getChunkMatrix();
			for (int i = 0; i < 16; i++) {
				SEAM[i] = seam.get(i);
			}
		}

		int ordinal = layer.ordinal();
		long t0 = 0L;
		long t1;
		for (int c = 0; c < n; c++) {
			BuiltChunk chunk = this.helpers.get(c);
			VertexBuffer vbo = chunk.method_10165(ordinal);
			BlockPos pos = chunk.getPos();
			// Same expression as vanilla's translate((float)(pos - view)).
			float tx = (float) (pos.getX() - vx);
			float ty = (float) (pos.getY() - vy);
			float tz = (float) (pos.getZ() - vz);
			if (timing) {
				t0 = System.nanoTime();
			}

			if (matrixMode == 1) {
				ChunkMatrix.compute(ChunkMatrix.variant, BASE, tx, ty, tz, SEAM, OUT, TMP);
				FB.clear();
				FB.put(OUT).flip();
				GL11.glLoadMatrix(FB);
			} else if (matrixMode == 2) {
				// Driver does the math; loading the saved bits is exactly what popMatrix would restore.
				FB.clear();
				FB.put(BASE).flip();
				GL11.glLoadMatrix(FB);
				GlStateManager.translate(tx, ty, tz);
				chunk.method_10169();
			} else {
				GlStateManager.pushMatrix();
				GlStateManager.translate(tx, ty, tz);
				chunk.method_10169();
			}
			if (timing) {
				t1 = System.nanoTime();
				DrawTiming.matrixNs += t1 - t0;
				t0 = t1;
			}

			if (useVao) {
				APPLEVertexArrayObject.glBindVertexArrayAPPLE(((VaoHolder) vbo).legacysilicon$getVao());
			} else {
				vbo.bind();
				GlStateManager.method_12307(3, GL11.GL_FLOAT, 28, 0);
				GlStateManager.method_12311(4, GL11.GL_UNSIGNED_BYTE, 28, 12);
				GlStateManager.method_12302(2, GL11.GL_FLOAT, 28, 16);
				GLX.gl13ClientActiveTexture(GLX.lightmapTextureUnit);
				GlStateManager.method_12302(2, GL11.GL_SHORT, 28, 24);
				GLX.gl13ClientActiveTexture(GLX.textureUnit);
			}
			if (timing) {
				t1 = System.nanoTime();
				DrawTiming.bindNs += t1 - t0;
				t0 = t1;
			}

			vbo.draw(GL11.GL_QUADS);
			if (matrixMode == 0) {
				GlStateManager.popMatrix();
			}
			if (timing) {
				DrawTiming.drawNs += System.nanoTime() - t0;
				DrawTiming.chunks++;
			}
		}

		if (useVao) {
			APPLEVertexArrayObject.glBindVertexArrayAPPLE(0);
		}
		GlStateManager.popMatrix();
		GLX.gl15BindBuffer(GLX.arrayBuffer, 0);
		GlStateManager.clearColor();
		this.helpers.clear();
	}
}
