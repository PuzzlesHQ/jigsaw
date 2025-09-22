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

import groovy.lang.Closure;

import net.fabricmc.loom.configuration.LoomConfigurations;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ArtifactRepositoryContainer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.IvyArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.initialization.Settings;
import org.gradle.api.internal.artifacts.DefaultExcludeRule;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.PluginAware;
import org.gradle.api.publish.maven.internal.dependencies.DefaultMavenDependency;
import org.hjson.JsonArray;
import org.hjson.JsonObject;
import org.hjson.JsonValue;
import org.hjson.Stringify;
import org.jetbrains.annotations.NotNull;

import net.fabricmc.loom.extension.LoomFiles;
import net.fabricmc.loom.util.MirrorUtil;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Stream;

import static org.gradle.internal.impldep.org.junit.Assert.assertTrue;

public class LoomRepositoryPlugin implements Plugin<PluginAware> {
	
	static final String NORMAL_BASE = "dev.puzzleshq";
	static final String JITPACK_BASE = "com.github.PuzzlesHQ";
	static final String VERSION_MANIFEST_CORE_LOC = "https://raw.githubusercontent.com/PuzzlesHQ/puzzle-loader-core/refs/heads/versioning/versions.json";
	static final String VERSION_MANIFEST_COSMIC_LOC = "https://raw.githubusercontent.com/PuzzlesHQ/puzzle-loader-cosmic/refs/heads/versioning/versions.json";
	static String BASE = NORMAL_BASE;
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
		if (project.getConfigurations().findByName("clientImplementation") != null) {
			project.getDependencies().add("clientImplementation", dep);
		} else {
			project.getDependencies().add("implementation", dep);
		}
	}

	private static HashMap<String, String> createExclude(String group, String module) {
		return new HashMap<>(){{
			put("group", group);
			put("module", module);
		}};
	}

	public void addImpl(Project project, String dep) {
		if (dep.contains("net.neoforged:bus")) {
			DefaultExternalModuleDependency dependency = ((DefaultExternalModuleDependency)project.getDependencies().add("implementation", dep));
			dependency.exclude(createExclude("org.apache.logging.log4j", "log4j-api"));
			dependency.exclude(createExclude("org.apache.logging.log4j", "log4j-core"));
		} else if (dep.contains("net.fabricmc:sponge-mixin")) {
			DefaultExternalModuleDependency dependency = ((DefaultExternalModuleDependency)project.getDependencies().add("implementation", dep));
			dependency.exclude(createExclude("com.google.code.gson", "gson"));
			dependency.exclude(createExclude("com.google.guava", "guava"));
		} else {
			project.getDependencies().add("implementation", dep);
		}
	}

	private void pullDeps(Project project, String propertiesVersion, URL url){
		try {

			var stream = url.openStream();
			String jsonInfo = new String(stream.readAllBytes());
			stream.close();

			JsonObject obj = JsonValue.readHjson(jsonInfo).asObject();
			var versionsList = obj.get("versions").asObject();
			JsonObject versionInfo = versionsList.get((String)project.getProperties().get(propertiesVersion)).asObject();
			if(versionInfo == null){
				versionInfo = versionsList.get(obj.get("latest").asObject().get("*").asString()).asObject();
				assertTrue(ObjectUtils.allNotNull(versionInfo));
			}
			var depsUrl = new URL(versionInfo.get("dependencies").asString());

			stream =  depsUrl.openStream();
			String jsonDepsInfoString =  new String(stream.readAllBytes());
			stream.close();

			JsonObject depsobj = JsonValue.readHjson(jsonDepsInfoString).asObject();

			if(depsobj.get("common") != null) {
				JsonArray commonDepsList = depsobj.get("common").asArray();
				for (JsonValue jsonValue : commonDepsList) {
					var depobj = jsonValue.asObject();
					if (Objects.equals(depobj.get("type").asString(), "implementation")) {
						String dep = String.format("%s:%s:%s",
								depobj.get("groupId").asString(),
								depobj.get("artifactId").asString(),
								depobj.get("version").asString()
						);
						addImpl(project, dep);
					}
				}
			}

			if(depsobj.get("server") != null) {
				JsonArray server = depsobj.get("server").asArray();
				for (JsonValue jsonValue : server) {
					var depobj = jsonValue.asObject();
					if (Objects.equals(depobj.get("type").asString(), "implementation")) {
						String dep = String.format("%s:%s:%s",
								depobj.get("groupId").asString(),
								depobj.get("artifactId").asString(),
								depobj.get("version").asString()
						);
						addImpl(project, dep);
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void setupProjectDependencies(Project project) {
		// Puzzle Core
		if (project.getProperties().get("use_jitpack") != null) {
			if(project.getProperties().get("use_jitpack").equals("true") || project.getProperties().get("use_jitpack").equals(true)){
				BASE = JITPACK_BASE;
			}
		}
		if (project.getProperties().get("puzzle_core_version") != null) {
			if (project.getConfigurations().findByName("clientImplementation") != null) {
				addImplSided(project, getPuzzleCore((String) project.getProperties().get("puzzle_core_version")) + ":client");
				addImpl(project, getPuzzleCore((String) project.getProperties().get("puzzle_core_version")) + ":common");
				addImpl(project, getPuzzleCore((String) project.getProperties().get("puzzle_core_version")) + ":server");
			} else {
				addImpl(project, getPuzzleCore((String) project.getProperties().get("puzzle_core_version")) + ":client");
				addImpl(project, getPuzzleCore((String) project.getProperties().get("puzzle_core_version")) + ":common");
				addImpl(project, getPuzzleCore((String) project.getProperties().get("puzzle_core_version")) + ":server");
			}
			try {
				pullDeps(project,"puzzle_core_version", new URL(VERSION_MANIFEST_CORE_LOC));
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}
		// Puzzle Paradox
		if (project.getProperties().get("puzzle_paradox_version") != null) {
			addImpl(project, getPuzzleParadox((String) project.getProperties().get("puzzle_paradox_version")));
		}
		// Puzzle Cosmic
		if (project.getProperties().get("puzzle_cosmic_version") != null) {
			addImpl(project, getPuzzleCosmic((String) project.getProperties().get("puzzle_cosmic_version")) + ":common");
			addImpl(project, getPuzzleCosmic((String) project.getProperties().get("puzzle_cosmic_version")) + ":server");
			if (project.getConfigurations().findByName("clientCompileOnly") != null) {
				addImplSided(project, getPuzzleCosmic((String) project.getProperties().get("puzzle_cosmic_version")) + ":client");
			} else {
				addImpl(project, getPuzzleCosmic((String) project.getProperties().get("puzzle_cosmic_version")) + ":client");
			}
			try {
				pullDeps(project,"puzzle_cosmic_version", new URL(VERSION_MANIFEST_COSMIC_LOC));
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}

		// Access Manipulators
		if (project.getProperties().get("access_manipulators_version") != null) {
			addImpl(project, getAccessManipulators((String) project.getProperties().get("access_manipulators_version")));
		}
//
//		if (project.getProperties().get("puzzle_core_version") != null) {
//
//				// Asm
////				addImpl(project, "org.ow2.asm:asm:9.8");
////				addImpl(project, "org.ow2.asm:asm-tree:9.8");
////				addImpl(project, "org.ow2.asm:asm-util:9.8");
////				addImpl(project, "org.ow2.asm:asm-analysis:9.8");
////				addImpl(project, "org.ow2.asm:asm-commons:9.8");
////				addImpl(project, "net.neoforged:bus:8.0.2");
//
//
//				// Mixins
////			   addImpl(project, "net.fabricmc:sponge-mixin:0.15.3+mixin.0.8.7");
////			   addImpl(project, "io.github.llamalad7:mixinextras-fabric:0.4.1");
//
//		}

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
		repositories.maven(repo -> {
			repo.setName("Jitpack");
			repo.setUrl("https://jitpack.io");
		});

		IvyArtifactRepository puzzleArchiveRepo = repositories.ivy(repo -> { // The CR repo
			repo.setName("CRArchive");
			repo.setUrl("https://github.com/PuzzlesHQ/CRArchive/releases/download");

			repo.patternLayout(pattern -> {
				pattern.artifact("/[revision]/cosmic-reach-[classifier]-[revision].jar");
				pattern.artifact("/[revision]/cosmic-reach-[classifier]-[revision].jar");
			});

			repo.metadataSources(sources -> {
				sources.artifact();
				sources.ignoreGradleMetadataRedirection();
			});

			repo.content(content -> {
				content.includeModule("finalforeach", "cosmicreach");
			});
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

		// If a mavenCentral repo is already defined, remove the mojang repo and add it back before the mavenCentral repo so that it will be checked first.
		// See: https://github.com/FabricMC/fabric-loom/issues/621
		ArtifactRepository mavenCentral = repositories.findByName(ArtifactRepositoryContainer.DEFAULT_MAVEN_CENTRAL_REPO_NAME);

		repositories.add(puzzleArchiveRepo);
		repositories.add(cosmicArchiveRepoAlpha);
		repositories.add(cosmicArchiveRepoPreAlpha);
		if (mavenCentral != null) {
			repositories.remove(puzzleArchiveRepo);
			repositories.remove(cosmicArchiveRepoAlpha);
			repositories.remove(cosmicArchiveRepoPreAlpha);
			repositories.add(repositories.indexOf(mavenCentral), puzzleArchiveRepo);
			repositories.add(repositories.indexOf(mavenCentral), cosmicArchiveRepoAlpha);
			repositories.add(repositories.indexOf(mavenCentral), cosmicArchiveRepoPreAlpha);

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
	 * @since 1.2.0
	 */
	static String getPuzzleCore(String ver) {
		return BASE + ":puzzle-loader-core:" + ver;
	}

	/**
	 * Gets the Maven formatted string to Puzzle Loader with the specified ${version}
	 * @param ver Version of PuzzleLoader
	 * @return Gradle dependency ready formatted string
	 * @since 1.2.0
	 */
	static String getPuzzleCosmic(String ver) {
		return BASE + ":puzzle-loader-cosmic:" + ver;
	}


	/**
	 * Gets the Maven formatted string to Access Manipulators with the specified ${version}
	 * @param ver Version of Access Manipulators
	 * @return Gradle dependency ready formatted string
	 * @since 1.0.0
	 */
	static String getAccessManipulators(String ver) {
		return BASE + ":access_manipulators:" + ver;
	}

}
