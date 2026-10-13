package dev.legacysilicon;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads Mach-O headers so we can tell, without loading them, which CPU a native library was built for. */
final class NativeArch {
	private static final int MH_MAGIC_64_LE = 0xCFFAEDFE; // 0xFEEDFACF read big-endian
	private static final int FAT_MAGIC = 0xCAFEBABE;
	private static final int CPU_X86_64 = 0x01000007;
	private static final int CPU_ARM64 = 0x0100000C;
	private static final int CPU_I386 = 0x00000007;

	private NativeArch() {
	}

	/** @return architectures in the file, e.g. ["arm64"] or ["x86_64", "arm64"]; empty if unreadable/not Mach-O. */
	static List<String> archesOf(File file) {
		List<String> out = new ArrayList<String>(2);
		try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
			int magic = in.readInt();
			if (magic == MH_MAGIC_64_LE) {
				out.add(name(Integer.reverseBytes(in.readInt())));
			} else if (magic == FAT_MAGIC) {
				int n = in.readInt();
				for (int i = 0; i < n && i < 8; i++) {
					out.add(name(in.readInt()));
					in.skipBytes(16); // cpusubtype, offset, size, align
				}
			}
		} catch (IOException ignored) {
		}
		return out;
	}

	private static String name(int cpu) {
		switch (cpu) {
			case CPU_ARM64:
				return "arm64";
			case CPU_X86_64:
				return "x86_64";
			case CPU_I386:
				return "i386";
			default:
				return "cpu(0x" + Integer.toHexString(cpu) + ")";
		}
	}
}
