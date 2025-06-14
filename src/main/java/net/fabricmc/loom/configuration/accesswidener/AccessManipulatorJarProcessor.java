/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2023 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.accesswidener;

import net.fabricmc.loom.api.processor.CosmicReachtJarProcessor;
import net.fabricmc.loom.api.processor.ProcessorContext;
import net.fabricmc.loom.api.processor.SpecContext;
import net.fabricmc.loom.util.fmj.PuzzleModJson;
import net.fabricmc.loom.util.fmj.ModEnvironment;

import org.gradle.api.file.RegularFileProperty;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AccessManipulatorJarProcessor implements CosmicReachtJarProcessor<AccessManipulatorJarProcessor.Spec> {
	private final RegularFileProperty localAccessManipulatorProperty;

	@Inject
	public AccessManipulatorJarProcessor(RegularFileProperty localAccessManipulatorProperty) {
		this.localAccessManipulatorProperty = localAccessManipulatorProperty;
	}

	@Override
	public @Nullable AccessManipulatorJarProcessor.Spec buildSpec(SpecContext context) {

		List<AccessWidenerEntry> accessWideners = new ArrayList<>();

		if (localAccessManipulatorProperty.isPresent()) {
			Path path = localAccessManipulatorProperty.get().getAsFile().toPath();

			if (Files.notExists(path)) {
				throw new UncheckedIOException(new FileNotFoundException("Could not find access manipulator file at {%s}".formatted(path)));
			}

			// Add the access widener specified in the extension
			accessWideners.add(LocalAccessWidenerEntry.create(path));
		}

		/* Uncomment to read all access wideners from local mods.

		for (FabricModJson fabricModJson : context.localMods()) {
			accessWideners.addAll(ModAccessWidenerEntry.readAll(fabricModJson, false));
		}

		 */
		for (PuzzleModJson fabricModJson : context.modDependencies()) {
			accessWideners.addAll(ModAccessWidenerEntry.readAll(fabricModJson, true));
		}

		if (accessWideners.isEmpty()) {
			return null;
		}

		return new Spec(accessWideners.stream().sorted(Comparator.comparing(AccessWidenerEntry::getSortKey)).toList());
	}

	@Override
	public String getName() {
		return "Manipulator";
	}

	public record Spec(List<AccessWidenerEntry> accessWideners) implements CosmicReachtJarProcessor.Spec {
		List<AccessWidenerEntry> accessWidenersForContext(ProcessorContext context) {
			return accessWideners.stream()
					.filter(entry -> isSupported(entry.environment(), context))
					.toList();
		}

		private static boolean isSupported(ModEnvironment modEnvironment, ProcessorContext context) {
			if (context.isMerged()) {
				// All envs are supported wth a merged jar
				return true;
			}

			if (context.includesClient() && modEnvironment.isClient()) {
				return true;
			}

			if (context.includesServer() && modEnvironment.isServer()) {
				return true;
			}

			// Universal supports all jars
			return modEnvironment == ModEnvironment.UNIVERSAL;
		}
	}

	@Override
	public void processJar(Path jar, AccessManipulatorJarProcessor.Spec spec, ProcessorContext context) throws IOException {
		final List<AccessWidenerEntry> accessWideners = spec.accessWidenersForContext(context);
		for (AccessWidenerEntry entry : accessWideners) {
			entry.read();
		}

		AccessWidenerTransformer transformer = new AccessWidenerTransformer();
		transformer.apply(jar);
	}

}
