/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2023 FabricMC
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

package net.fabricmc.loom.configuration.providers.cosmicreach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.fabricmc.loom.configuration.ConfigContext;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.configuration.processors.CosmicReachJarProcessorManager;
import net.fabricmc.loom.configuration.processors.ProcessorContextImpl;

import org.slf4j.LoggerFactory;

public abstract class ProcessedNamedCosmicReachProvider<M extends CosmicReachProvider, P extends FinalizedCosmicReachProvider<M>> extends FinalizedCosmicReachProvider<M> {
	private final P parentMinecraftProvider;
	private final CosmicReachJarProcessorManager jarProcessorManager;

	public ProcessedNamedCosmicReachProvider(P parentMinecraftProvide, CosmicReachJarProcessorManager jarProcessorManager) {
		super(parentMinecraftProvide.getProject(), parentMinecraftProvide.minecraftProvider);
		this.parentMinecraftProvider = parentMinecraftProvide;
		this.jarProcessorManager = Objects.requireNonNull(jarProcessorManager);
	}

	@Override
	public List<CosmicReachJar> provide(ProvideContext context) throws Exception {
		final List<CosmicReachJar> parentMinecraftJars = parentMinecraftProvider.getCosmicReachJars();
		final Map<CosmicReachJar, CosmicReachJar> minecraftJarOutputMap = parentMinecraftJars.stream()
				.collect(Collectors.toMap(Function.identity(), this::getProcessedJar));
		final List<CosmicReachJar> minecraftJars = List.copyOf(minecraftJarOutputMap.values());

		parentMinecraftProvider.provide(context.withApplyDependencies(false));

		boolean requiresProcessing = context.refreshOutputs() || !hasBackupJars(minecraftJars) || parentMinecraftJars.stream()
				.map(this::getProcessedPath)
				.anyMatch(jarProcessorManager::requiresProcessingJar);

		if (requiresProcessing) {
			processJars(minecraftJarOutputMap, context.configContext());
			createBackupJars(minecraftJars);
		}

		if (context.applyDependencies()) {
			applyDependencies();
		}

		return List.copyOf(minecraftJarOutputMap.values());
	}

	@Override
	public MavenScope getMavenScope() {
		return MavenScope.LOCAL;
	}

	private void processJars(Map<CosmicReachJar, CosmicReachJar> minecraftJarMap, ConfigContext configContext) throws IOException {

		for (Map.Entry<CosmicReachJar, CosmicReachJar> entry : minecraftJarMap.entrySet()) {
			final CosmicReachJar minecraftJar = entry.getKey();
			final CosmicReachJar outputJar = entry.getValue();
			deleteSimilarJars(outputJar.getPath());

			final LocalMavenHelper mavenHelper = getMavenHelper(minecraftJar.getType());
			final Path outputPath = mavenHelper.copyToMaven(minecraftJar.getPath(), null);

			assert outputJar.getPath().equals(outputPath);

			jarProcessorManager.processJar(outputPath, new ProcessorContextImpl(configContext, minecraftJar));
		}
	}

	@Override
	public List<CosmicReachJar.Type> getDependencyTypes() {
		return parentMinecraftProvider.getDependencyTypes();
	}

	private void applyDependencies() {
		final List<CosmicReachJar.Type> dependencyTargets = getDependencyTypes();

		if (dependencyTargets.isEmpty()) {
			return;
		}

		CosmicReachSourceSets.get(getProject()).applyDependencies(
				(configuration, name) -> getProject().getDependencies().add(configuration, getDependencyNotation(name)),
				dependencyTargets
		);
	}

	private void deleteSimilarJars(Path jar) throws IOException {
		Files.deleteIfExists(jar);
		final Path parent = jar.getParent();

		if (Files.notExists(parent)) {
			return;
		}

		for (Path path : Files.list(parent).filter(Files::isRegularFile)
				.filter(path -> path.getFileName().startsWith(jar.getFileName().toString().replace(".jar", ""))).toList()) {
			Files.deleteIfExists(path);
		}
	}

	protected String getDependencyNotation(CosmicReachJar.Type type) {
		return "finalforeach:cosmicreach-%s:%s:%s".formatted(getName(type), getVersion(), getName(type));
	}

	public LocalMavenHelper getMavenHelper(CosmicReachJar.Type type) {
		return new LocalMavenHelper("finalforeach", "cosmicreach-"+getName(type), getVersion(), getName(type), getMavenScope().getRoot(extension));
	}

	@Override
	protected String getName(CosmicReachJar.Type type) {
		// Hash the cache value so that we don't have to process the same JAR multiple times for many projects
		return "processed-%s-%s".formatted(type.toString(), jarProcessorManager.getJarHash());
	}

	@Override
	public Path getJar(CosmicReachJar.Type type) {
		// Something has gone wrong if this gets called.
		throw new UnsupportedOperationException();
	}

	@Override
	public List<CosmicReachJar> getCosmicReachJars() {
		return getParentMinecraftProvider().getCosmicReachJars().stream()
				.map(this::getProcessedJar)
				.toList();
	}

	public P getParentMinecraftProvider() {
		return parentMinecraftProvider;
	}

	private Path getProcessedPath(CosmicReachJar minecraftJar) {
		final LocalMavenHelper mavenHelper = getMavenHelper(minecraftJar.getType());
		return mavenHelper.getOutputFile(null);
	}

	public CosmicReachJar getProcessedJar(CosmicReachJar minecraftJar) {
		return minecraftJar.forPath(getProcessedPath(minecraftJar));
	}

	public static final class MergedImpl extends ProcessedNamedCosmicReachProvider<MergedCosmicReachProvider, FinalizedCosmicReachProvider.MergedImpl> implements Merged {
		public MergedImpl(FinalizedCosmicReachProvider.MergedImpl parentMinecraftProvide, CosmicReachJarProcessorManager jarProcessorManager) {
			super(parentMinecraftProvide, jarProcessorManager);
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			return List.of(
					new RemappedJars(minecraftProvider.getMergedJar(), getMergedJar())
			);
		}

		@Override
		public CosmicReachJar getMergedJar() {
			return getProcessedJar(getParentMinecraftProvider().getMergedJar());
		}
	}

	public static final class SplitImpl extends ProcessedNamedCosmicReachProvider<SplitCosmicReachProvider, FinalizedCosmicReachProvider.SplitImpl> implements Split {
		public SplitImpl(FinalizedCosmicReachProvider.SplitImpl parentMinecraftProvide, CosmicReachJarProcessorManager jarProcessorManager) {
			super(parentMinecraftProvide, jarProcessorManager);
		}

		@Override
		public CosmicReachJar getServerJar() {
			return getProcessedJar(getParentMinecraftProvider().getServerJar());
		}

		@Override
		public CosmicReachJar getClientJar() {
			return getProcessedJar(getParentMinecraftProvider().getClientJar());
		}


		@Override
		public List<RemappedJars> getRemappedJars() {
			return List.of(
					new RemappedJars(minecraftProvider.getCosmicReachServerJar().toPath(), getServerJar()),
					new RemappedJars(minecraftProvider.getCosmicReachClientJar().toPath(), getClientJar())
			);
		}
	}

	public static final class SingleJarImpl extends ProcessedNamedCosmicReachProvider<SingleJarCosmicReachProvider, FinalizedCosmicReachProvider.SingleJarImpl> implements SingleJar {
		private final SingleJarEnvType env;

		private SingleJarImpl(FinalizedCosmicReachProvider.SingleJarImpl parentMinecraftProvide, CosmicReachJarProcessorManager jarProcessorManager, SingleJarEnvType env) {
			super(parentMinecraftProvide, jarProcessorManager);
			this.env = env;
		}

		public static ProcessedNamedCosmicReachProvider.SingleJarImpl server(FinalizedCosmicReachProvider.SingleJarImpl parentMinecraftProvide, CosmicReachJarProcessorManager jarProcessorManager) {
			return new ProcessedNamedCosmicReachProvider.SingleJarImpl(parentMinecraftProvide, jarProcessorManager, SingleJarEnvType.SERVER);
		}

		public static ProcessedNamedCosmicReachProvider.SingleJarImpl client(FinalizedCosmicReachProvider.SingleJarImpl parentMinecraftProvide, CosmicReachJarProcessorManager jarProcessorManager) {
			return new ProcessedNamedCosmicReachProvider.SingleJarImpl(parentMinecraftProvide, jarProcessorManager, SingleJarEnvType.CLIENT);
		}

		@Override
		public List<RemappedJars> getRemappedJars() {
			return List.of(
					new RemappedJars(minecraftProvider.getCosmicReachEnvOnlyJar(), getEnvOnlyJar())
			);
		}

		@Override
		public CosmicReachJar getEnvOnlyJar() {
			return getProcessedJar(getParentMinecraftProvider().getEnvOnlyJar());
		}

		@Override
		public SingleJarEnvType env() {
			return env;
		}
	}
}