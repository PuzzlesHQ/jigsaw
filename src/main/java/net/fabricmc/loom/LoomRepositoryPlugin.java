/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
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

package net.fabricmc.loom;

import org.apache.maven.artifact.versioning.ComparableVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactRepositoryContainer;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.IvyArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.initialization.Settings;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.PluginAware;
import org.jetbrains.annotations.NotNull;

import net.fabricmc.loom.extension.LoomFiles;
import net.fabricmc.loom.util.MirrorUtil;

import java.util.Objects;

public class LoomRepositoryPlugin implements Plugin<PluginAware> {
	@Override
	public void apply(@NotNull PluginAware target) {
		if (target instanceof Settings settings) {
			declareRepositories(settings.getDependencyResolutionManagement().getRepositories(), LoomFiles.create(settings), settings);

			// leave a marker so projects don't try to override these
			settings.getGradle().getPluginManager().apply(LoomRepositoryPlugin.class);
		} else if (target instanceof Project project) {
			if (project.getGradle().getPlugins().hasPlugin(LoomRepositoryPlugin.class)) {
				return;
			}

			declareRepositories(project.getRepositories(), LoomFiles.create(project), project);
			setupProjectDependencies(project);
		} else if (target instanceof Gradle) {
			return;
		} else {
			throw new IllegalArgumentException("Expected target to be a Project or Settings, but was a " + target.getClass());
		}
	}

	public void addImplSided(Project project, String dep) {
		if (project.getConfigurations().findByName("clientCompileOnly") != null) {
			project.getDependencies().add("clientCompileOnly", dep + ":client");
			project.getDependencies().add("clientRuntimeOnly", dep + ":client");
			project.getDependencies().add("compileOnly", dep + ":server");
			project.getDependencies().add("runtimeOnly", dep + ":server");
		} else {
			project.getDependencies().add("compileOnly", dep + ":client");
			project.getDependencies().add("runtimeOnly", dep + ":client");
		}
	}

	public void addImpl(Project project, String dep) {
		project.getDependencies().add("compileOnly", dep);
		project.getDependencies().add("runtimeOnly", dep);
	}

	static ComparableVersion PUZZLE_VERSION_REFACTOR = new ComparableVersion("2.0.0");
	static ComparableVersion PUZZLE_VERSION_PRE_BUNDLED = new ComparableVersion("2.1.16");

	private void setupProjectDependencies(Project project) {
		// Puzzle Loader
		if (project.getProperties().get("puzzle_loader_version") != null) {
			if (!project.getProperties().get("puzzle_loader_version").toString().contains("development"))
				addImplSided(project, getPuzzleLoader((String) project.getProperties().get("puzzle_loader_version")));
		}

		// Puzzle Paradox
		if (project.getProperties().get("puzzle_paradox_version") != null) {
			addImpl(project, getPuzzleParadox((String) project.getProperties().get("puzzle_paradox_version")));
		}

		// Access Manipulators
		if (project.getProperties().get("access_manipulators_version") != null) {
			addImpl(project, getAccessManipulators((String) project.getProperties().get("access_manipulators_version")));
		}

		if (project.getProperties().get("puzzle_loader_version") != null) {
			ComparableVersion puzzleVersionString = new ComparableVersion(project.getProperties().get("puzzle_loader_version").toString());

			if (
					!(puzzleVersionString.compareTo(PUZZLE_VERSION_PRE_BUNDLED) > 0
					|| "2.1.16".equals(project.getProperties().get("puzzle_loader_version").toString()))
			) {
				if (Objects.equals(project.getProperties().get("puzzle_loader_version").toString(), "development-fabric"))
					addImpl(project, "net.fabricmc:sponge-mixin:0.15.3+mixin.0.8.7");
				else if (Objects.equals(project.getProperties().get("puzzle_loader_version").toString(), "development-sponge"))
					addImpl(project, "org.spongepowered:mixin:0.8.5");

				// Asm
				addImpl(project, "org.ow2.asm:asm:9.6");
				addImpl(project, "org.ow2.asm:asm-tree:9.6");
				addImpl(project, "org.ow2.asm:asm-util:9.6");
				addImpl(project, "org.ow2.asm:asm-analysis:9.6");
				addImpl(project, "org.ow2.asm:asm-commons:9.6");

				if (project.getProperties().get("puzzle_loader_version").toString().contains("development")) return;

				// Mixins
				if (puzzleVersionString.compareTo(PUZZLE_VERSION_REFACTOR) > 0 || "2.0.0".equals(project.getProperties().get("puzzle_loader_version").toString()))
					addImpl(project, "net.fabricmc:sponge-mixin:0.15.3+mixin.0.8.7");
				else
					addImpl(project, "org.spongepowered:mixin:0.8.5");
			}
		}
	}

	private void declareRepositories(RepositoryHandler repositories, LoomFiles files, ExtensionAware target) {
		declareLocalRepositories(repositories, files);

		repositories.maven(repo -> {
			repo.setName("Fabric");
			repo.setUrl(MirrorUtil.getFabricRepository(target));
		});

		repositories.maven(repo -> {
			repo.setName("Sponge");
			repo.setUrl("https://repo.spongepowered.org/repository/maven-public/");
		});

		IvyArtifactRepository cosmicArchiveRepoPreAlpha = repositories.ivy(repo -> { // The CR repo
			repo.setName("CosmicArchive");
			repo.setUrl("https://github.com/CRModders/CosmicArchive/raw/main/versions/pre-alpha");

			repo.patternLayout(pattern -> {
				pattern.artifact("/[revision]/[classifier]/Cosmic Reach-[revision].jar");
				pattern.artifact("/[revision]/[classifier]/Cosmic Reach-Server-[revision].jar");
			});

			repo.metadataSources(sources -> {
				sources.artifact();
				sources.ignoreGradleMetadataRedirection();
			});

			repo.content(content -> {
				content.includeModule("finalforeach", "cosmicreach-prealpha");
			});
		});

		IvyArtifactRepository cosmicArchiveRepoAlpha = repositories.ivy(repo -> { // The CR repo
			repo.setName("CosmicArchive");
			repo.setUrl("https://github.com/CRModders/CosmicArchive/raw/main/versions/alpha");

			repo.patternLayout(pattern -> {
				pattern.artifact("/[revision]/[classifier]/Cosmic Reach-[revision].jar");
				pattern.artifact("/[revision]/[classifier]/Cosmic Reach-Server-[revision].jar");
			});

			repo.metadataSources(sources -> {
				sources.artifact();
				sources.ignoreGradleMetadataRedirection();
			});

			repo.content(content -> {
				content.includeModule("finalforeach", "cosmicreach-alpha");
			});
		});
//
//		IvyArtifactRepository puzzleArchiveRepo = repositories.ivy(repo -> { // The CR repo
//			repo.setName("PuzzleArchive");
//			repo.setUrl("https://github.com/PuzzleLoader/CRPuzzleArchive/raw/main/versions/pre-alpha");
//
//			repo.patternLayout(pattern -> {
//				pattern.artifact("/[revision]/[classifier]/Cosmic Reach-[revision].jar");
//				pattern.artifact("/[revision]/[classifier]/Cosmic Reach-Server-[revision].jar");
//			});
//
//			repo.metadataSources(sources -> {
//				sources.artifact();
//				sources.ignoreGradleMetadataRedirection();
//			});
//
//
//			repo.content(content -> {
//				content.includeModule("finalforeach", "cosmicreach");
//			});
//		});

		// If a mavenCentral repo is already defined, remove the mojang repo and add it back before the mavenCentral repo so that it will be checked first.
		// See: https://github.com/FabricMC/fabric-loom/issues/621
		ArtifactRepository mavenCentral = repositories.findByName(ArtifactRepositoryContainer.DEFAULT_MAVEN_CENTRAL_REPO_NAME);

		repositories.add(cosmicArchiveRepoAlpha);
		repositories.add(cosmicArchiveRepoPreAlpha);
//		repositories.add(puzzleArchiveRepo);
		if (mavenCentral != null) {
			repositories.remove(cosmicArchiveRepoAlpha);
			repositories.remove(cosmicArchiveRepoPreAlpha);
//			repositories.remove(puzzleArchiveRepo);
			repositories.add(repositories.indexOf(mavenCentral), cosmicArchiveRepoAlpha);
			repositories.add(repositories.indexOf(mavenCentral), cosmicArchiveRepoPreAlpha);
//			repositories.add(repositories.indexOf(cosmicArchiveRepo), puzzleArchiveRepo);
		}

		repositories.mavenCentral();
	}

	private void declareLocalRepositories(RepositoryHandler repositories, LoomFiles files) {
		repositories.maven(repo -> {
			repo.setName("LoomLocalRemappedMods");
			repo.setUrl(files.getRemappedModCache());
		});

		repositories.maven(repo -> {
			repo.setName("LoomLocalCosmicReach");
			repo.setUrl(files.getLocalCosmicReachRepo());
		});

		repositories.maven(repo -> {
			repo.setName("LoomGlobalCosmicReach");
			repo.setUrl(files.getGlobalCosmicReachRepo());
		});
	}

	public static void setupForLegacyVersions(RepositoryHandler repositories) {
		// 1.4.7 contains an LWJGL version with an invalid maven pom, set the metadata sources to not use the pom for this version.
		repositories.named("CosmicArchive", MavenArtifactRepository.class, repo -> {
			repo.metadataSources(sources -> {
				// Only use the maven artifact and not the pom or gradle metadata.
				sources.artifact();
				sources.ignoreGradleMetadataRedirection();
			});
		});
	}

	public static void forceLWJGLFromMavenCentral(RepositoryHandler repositories) {
		if (repositories.findByName("MavenCentralLWJGL") != null) {
			// Already applied.
			return;
		}

		// Force LWJGL from central, as it contains all the platform natives.
		MavenArtifactRepository central = repositories.maven(repo -> {
			repo.setName("MavenCentralLWJGL");
			repo.setUrl(ArtifactRepositoryContainer.MAVEN_CENTRAL_URL);
			repo.content(content -> {
				content.includeGroup("org.lwjgl");
			});
		});

		repositories.exclusiveContent(repository -> {
			repository.forRepositories(central);
			repository.filter(filter -> {
				filter.includeGroup("org.lwjgl");
			});
		});
	}

	/**
	 * Gets the Maven formatted string to Quilt with the specified ${version}
	 * @param ver Version of Quilt
	 * @return Gradle dependency ready formatted string
	 * @since 1.1.0
	 */
	static String getPuzzleParadox(String ver) {
		return "com.github.PuzzlesHQ:Paradox:" + ver;
	}

	/**
	 * Gets the Maven formatted string to Puzzle Loader with the specified ${version}
	 * @param ver Version of PuzzleLoader
	 * @return Gradle dependency ready formatted string
	 * @since 1.0.0
	 */
	static String getPuzzleLoader(String ver) {
		return "com.github.PuzzlesHQ:PuzzleLoader:" + ver;
	}

	/**
	 * Gets the Maven formatted string to Access Manipulators with the specified ${version}
	 * @param ver Version of Access Manipulators
	 * @return Gradle dependency ready formatted string
	 * @since 1.0.0
	 */
	static String getAccessManipulators(String ver) {
		return "com.github.PuzzlesHQ:access_manipulators:" + ver;
	}
}
