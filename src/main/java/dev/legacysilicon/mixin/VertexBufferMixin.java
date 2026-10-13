package dev.legacysilicon.mixin;

import com.mojang.blaze3d.platform.GLX;
import dev.legacysilicon.VaoHolder;
import net.minecraft.client.render.VertexBuffer;
import org.lwjgl.opengl.APPLEVertexArrayObject;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives each chunk VBO a vertex array object holding the same four pointers vanilla re-specifies before
 * every chunk draw. The VBO name never changes (glBufferData only replaces its storage), so the VAO is
 * built once and stays valid for the life of the buffer.
 */
@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin implements VaoHolder {
	@Shadow private int id;
	@Unique private int legacysilicon$vao;

	@Override
	public int legacysilicon$getVao() {
		if (this.legacysilicon$vao == 0 && this.id > 0) {
			int vao = APPLEVertexArrayObject.glGenVertexArraysAPPLE();
			APPLEVertexArrayObject.glBindVertexArrayAPPLE(vao);
			GLX.gl15BindBuffer(GLX.arrayBuffer, this.id);
			// BLOCK format: pos 3f @0, color 4ub @12, uv 2f @16, lightmap 2s @24, stride 28 — same as vanilla.
			GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
			GL11.glVertexPointer(3, GL11.GL_FLOAT, 28, 0L);
			GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
			GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 28, 12L);
			GLX.gl13ClientActiveTexture(GLX.textureUnit);
			GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
			GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16L);
			GLX.gl13ClientActiveTexture(GLX.lightmapTextureUnit);
			GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
			GL11.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24L);
			GLX.gl13ClientActiveTexture(GLX.textureUnit);
			APPLEVertexArrayObject.glBindVertexArrayAPPLE(0);
			GLX.gl15BindBuffer(GLX.arrayBuffer, 0);
			this.legacysilicon$vao = vao;
		}
		return this.legacysilicon$vao;
	}

	@Inject(method = "delete", at = @At("HEAD"))
	private void legacysilicon$deleteVao(CallbackInfo ci) {
		if (this.legacysilicon$vao != 0) {
			APPLEVertexArrayObject.glDeleteVertexArraysAPPLE(this.legacysilicon$vao);
			this.legacysilicon$vao = 0;
		}
	}
}
