package net.fabricmc.loom.api.remapping;

import java.io.Serializable;

import org.jetbrains.annotations.ApiStatus;

/**
 * Marker interface for parameter objects to {@link RemapperExtension}s.
 *
 * <p>Design based off of Gradle's {@link org.gradle.workers.WorkParameters}.
 */
public interface RemapperParameters extends Serializable {
	final class None implements RemapperParameters {
		@ApiStatus.Internal
		public static None INSTANCE = new None();

		private None() {
		}
	}
}