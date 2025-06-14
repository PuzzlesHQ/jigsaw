/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 FabricMC
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

import java.util.List;
import java.util.function.BiConsumer;

import com.google.common.base.Preconditions;

import net.fabricmc.loom.configuration.RemapConfigurations;

import net.fabricmc.loom.task.AbstractRemapJarTask;

import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.jvm.tasks.Jar;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

public abstract sealed class CosmicReachSourceSets permits CosmicReachSourceSets.Single, CosmicReachSourceSets.Split {
	public static CosmicReachSourceSets get(Project project) {
		return LoomGradleExtension.get(project).areEnvironmentSourceSetsSplit() ? Split.INSTANCE : Single.INSTANCE;
	}

	public abstract void applyDependencies(BiConsumer<String, CosmicReachJar.Type> consumer, List<CosmicReachJar.Type> targets);

	public abstract String getSourceSetForEnv(String env);

	protected abstract List<ConfigurationName> getConfigurations();

	public void evaluateSplit(Project project) {
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		Preconditions.checkArgument(extension.areEnvironmentSourceSetsSplit());

		Split.INSTANCE.evaluate(project);
	}

	public abstract void afterEvaluate(Project project);

	protected void createConfigurations(Project project) {
		final ConfigurationContainer configurations = project.getConfigurations();

		for (ConfigurationName configurationName : getConfigurations()) {
			configurations.register(configurationName.runtime(), configuration -> {
				configuration.setTransitive(false);
				configuration.extendsFrom(configurations.getByName(configurationName.mcLibsRuntimeName()));
				configuration.extendsFrom(configurations.getByName(Constants.Configurations.LOADER_DEPENDENCIES));
				configuration.extendsFrom(configurations.getByName(Constants.Configurations.LOOM_DEVELOPMENT_DEPENDENCIES));
			});

			configurations.register(configurationName.compile(), configuration -> {
				configuration.setTransitive(false);
				configuration.extendsFrom(configurations.getByName(configurationName.mcLibsCompileName()));
				configuration.extendsFrom(configurations.getByName(Constants.Configurations.LOADER_DEPENDENCIES));
			});
		}
	}

	protected void extendsFrom(Project project, String name, String extendsFrom) {
		final ConfigurationContainer configurations = project.getConfigurations();

		configurations.named(name, configuration -> {
			configuration.extendsFrom(configurations.getByName(extendsFrom));
		});
	}

	/**
	 * Used when we have a single source set, either with split or merged jars.
	 */
	public static final class Single extends CosmicReachSourceSets {
		private static final ConfigurationName COSMICREACH_NAMED = new ConfigurationName(
				"cosmicReachNamed",
				Constants.Configurations.COSMICREACH_COMPILE_LIBRARIES,
				Constants.Configurations.COSMICREACH_RUNTIME_LIBRARIES
		);

		private static final Single INSTANCE = new Single();

		@Override
		public void applyDependencies(BiConsumer<String, CosmicReachJar.Type> consumer, List<CosmicReachJar.Type> targets) {
			for (CosmicReachJar.Type target : targets) {
				consumer.accept(COSMICREACH_NAMED.compile(), target);
				consumer.accept(COSMICREACH_NAMED.runtime(), target);
			}
		}

		@Override
		public String getSourceSetForEnv(String env) {
			return SourceSet.MAIN_SOURCE_SET_NAME;
		}

		@Override
		protected List<ConfigurationName> getConfigurations() {
			return List.of(COSMICREACH_NAMED);
		}

		@Override
		public void afterEvaluate(Project project) {
			// This is done in afterEvaluate as we need to be sure that split source sets was not enabled.
			createConfigurations(project);

			extendsFrom(project, JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME, COSMICREACH_NAMED.compile());
			extendsFrom(project, JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, COSMICREACH_NAMED.runtime());
			extendsFrom(project, JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME, COSMICREACH_NAMED.compile());
			extendsFrom(project, JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME, COSMICREACH_NAMED.runtime());
		}
	}

	/**
	 * Used when we have a split client/common source set and split jars.
	 */
	public static final class Split extends CosmicReachSourceSets {
		private static final ConfigurationName COSMICREACH_SERVER_NAMED = new ConfigurationName(
				"cosmicReachCommonNamed",
				Constants.Configurations.COSMICREACH_COMPILE_LIBRARIES,
				Constants.Configurations.COSMICREACH_RUNTIME_LIBRARIES
		);
		// Depends on the Minecraft client libraries.
		private static final ConfigurationName COSMICREACH_CLIENT_NAMED = new ConfigurationName(
				"cosmicReachClientOnlyNamed",
				Constants.Configurations.COSMICREACH_CLIENT_COMPILE_LIBRARIES,
				Constants.Configurations.COSMICREACH_CLIENT_RUNTIME_LIBRARIES
		);

		public static final String CLIENT_ONLY_SOURCE_SET_NAME = "client";

		private static final Split INSTANCE = new Split();

		@Override
		public void applyDependencies(BiConsumer<String, CosmicReachJar.Type> consumer, List<CosmicReachJar.Type> targets) {
			Preconditions.checkArgument(targets.size() == 2);
			Preconditions.checkArgument(targets.contains(CosmicReachJar.Type.SERVER));
			Preconditions.checkArgument(targets.contains(CosmicReachJar.Type.CLIENT));

			consumer.accept(COSMICREACH_SERVER_NAMED.runtime(), CosmicReachJar.Type.SERVER);
			consumer.accept(COSMICREACH_CLIENT_NAMED.runtime(), CosmicReachJar.Type.CLIENT);
			consumer.accept(COSMICREACH_SERVER_NAMED.compile(), CosmicReachJar.Type.SERVER);
			consumer.accept(COSMICREACH_CLIENT_NAMED.compile(), CosmicReachJar.Type.CLIENT);
		}

		@Override
		public String getSourceSetForEnv(String env) {
			return env.equals("client") ? CLIENT_ONLY_SOURCE_SET_NAME : SourceSet.MAIN_SOURCE_SET_NAME;
		}

		@Override
		protected List<ConfigurationName> getConfigurations() {
			return List.of(COSMICREACH_SERVER_NAMED, COSMICREACH_CLIENT_NAMED);
		}

		// Called during evaluation, when the loom extension method is called.
		private void evaluate(Project project) {
			createConfigurations(project);
			final ConfigurationContainer configurations = project.getConfigurations();

			// Register our new client only source set, main becomes common only, with their respective jars.
			final SourceSet mainSourceSet = SourceSetHelper.getMainSourceSet(project);
			final SourceSet clientOnlySourceSet = SourceSetHelper.createSourceSet(CLIENT_ONLY_SOURCE_SET_NAME, project);

			// Add Minecraft to the main and client source sets.
			extendsFrom(project, mainSourceSet.getCompileClasspathConfigurationName(), COSMICREACH_SERVER_NAMED.compile());
			extendsFrom(project, mainSourceSet.getRuntimeClasspathConfigurationName(), COSMICREACH_SERVER_NAMED.runtime());
			extendsFrom(project, clientOnlySourceSet.getCompileClasspathConfigurationName(), COSMICREACH_CLIENT_NAMED.compile());
			extendsFrom(project, clientOnlySourceSet.getRuntimeClasspathConfigurationName(), COSMICREACH_CLIENT_NAMED.runtime());

			// Client source set depends on common.
			extendsFrom(project, COSMICREACH_CLIENT_NAMED.runtime(), COSMICREACH_SERVER_NAMED.runtime());
			extendsFrom(project, COSMICREACH_CLIENT_NAMED.compile(), COSMICREACH_SERVER_NAMED.compile());

			// Client annotation processor configuration extendsFrom "annotationProcessor"
			extendsFrom(project, clientOnlySourceSet.getAnnotationProcessorConfigurationName(), JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME);

			clientOnlySourceSet.setCompileClasspath(
					clientOnlySourceSet.getCompileClasspath()
							.plus(mainSourceSet.getOutput())
			);
			clientOnlySourceSet.setRuntimeClasspath(
					clientOnlySourceSet.getRuntimeClasspath()
							.plus(mainSourceSet.getOutput())
			);

			extendsFrom(project, clientOnlySourceSet.getCompileClasspathConfigurationName(), mainSourceSet.getCompileClasspathConfigurationName());
			extendsFrom(project, clientOnlySourceSet.getRuntimeClasspathConfigurationName(), mainSourceSet.getRuntimeClasspathConfigurationName());

			// Test source set depends on client
			final SourceSet testSourceSet = SourceSetHelper.getSourceSetByName(SourceSet.TEST_SOURCE_SET_NAME, project);
			extendsFrom(project, testSourceSet.getCompileClasspathConfigurationName(), clientOnlySourceSet.getCompileClasspathConfigurationName());
			extendsFrom(project, testSourceSet.getRuntimeClasspathConfigurationName(), clientOnlySourceSet.getRuntimeClasspathConfigurationName());
			project.getDependencies().add(testSourceSet.getImplementationConfigurationName(), clientOnlySourceSet.getOutput());


			RemapConfigurations.configureClientConfigurations(project, clientOnlySourceSet);

			// Include the client only output in the jars
			project.getTasks().named(mainSourceSet.getJarTaskName(), Jar.class).configure(jar -> {
				jar.from(clientOnlySourceSet.getOutput().getClassesDirs());
				jar.from(clientOnlySourceSet.getOutput().getResourcesDir());

				jar.dependsOn(project.getTasks().named(clientOnlySourceSet.getProcessResourcesTaskName()));
			});

			project.getTasks().withType(AbstractRemapJarTask.class).configureEach(remapJarTask -> {
				remapJarTask.getClasspath().from(
						project.getConfigurations().getByName(clientOnlySourceSet.getCompileClasspathConfigurationName())
				);
			});

			// The sources tas
			// The sources task can be registered at a later time.
			project.getTasks().configureEach(task -> {
				if (!mainSourceSet.getSourcesJarTaskName().equals(task.getName()) || !(task instanceof Jar jar)) {
					// Not the sources task we are looking for.
					return;
				}

				// The client only sources to the combined sources jar.
				jar.from(clientOnlySourceSet.getAllSource());
			});
			project.getTasks().withType(AbstractRemapJarTask.class, task -> {
				// Set the default client only source set name
				task.getClientOnlySourceSetName().convention(CLIENT_ONLY_SOURCE_SET_NAME);
			});

		}

		@Override
		public void afterEvaluate(Project project) {
		}
	}

	private record ConfigurationName(String baseName, String mcLibsCompileName, String mcLibsRuntimeName) {
		private String runtime() {
			return baseName + "Runtime";
		}

		private String compile() {
			return baseName + "Compile";
		}
	}
}
