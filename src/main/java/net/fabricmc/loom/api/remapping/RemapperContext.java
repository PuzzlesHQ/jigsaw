package net.fabricmc.loom.api.remapping;

import org.objectweb.asm.commons.Remapper;

/**
 * Context for a {@link RemapperExtension}.
 */
public interface RemapperContext {
	/**
	 * @return The {@link Remapper} instance
	 */
	Remapper remapper();

	/**
	 * @return the source namespace
	 */
	String sourceNamespace();

	/**
	 * @return the target namespace
	 */
	String targetNamespace();
}