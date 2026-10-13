package dev.legacysilicon.mixin;

import dev.legacysilicon.VisibilityMask;
import net.minecraft.client.world.ChunkAssemblyHelper;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Vanilla answers "can you see from face A to face B of this chunk" through helper -> occlusion data ->
 * BitSet -> long[]: four dependent memory loads, six times per chunk per frame. The answer never changes
 * once a compiled helper is published, so it is computed once (through the same virtual method, so the
 * UNSUPPORTED placeholder still answers false) and kept as one long.
 */
@Mixin(ChunkAssemblyHelper.class)
public abstract class ChunkAssemblyHelperMixin implements VisibilityMask {
	@Unique private static final Direction[] DIRS = Direction.values();
	@Unique private long legacysilicon$mask = -1L;

	@Shadow
	public abstract boolean isVisibleThrough(Direction dir1, Direction dir2);

	@Override
	public long legacysilicon$mask() {
		long m = this.legacysilicon$mask;
		if (m == -1L) {
			m = 0L;
			for (Direction from : DIRS) {
				for (Direction to : DIRS) {
					if (this.isVisibleThrough(from, to)) {
						m |= 1L << (from.ordinal() * 6 + to.ordinal());
					}
				}
			}
			this.legacysilicon$mask = m;
		}
		return m;
	}
}
