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

package net.fabricmc.loom.api;

import net.fabricmc.loom.api.remapping.RemapperExtension;

import net.fabricmc.loom.api.remapping.RemapperParameters;

import net.fabricmc.loom.extension.RemapperExtensionHolder;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.NamedDomainObjectList;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.SourceSet;
import org.jetbrains.annotations.ApiStatus;

import net.fabricmc.loom.api.decompilers.DecompilerOptions;
import net.fabricmc.loom.api.manifest.VersionsManifestsAPI;
import net.fabricmc.loom.api.processor.CosmicReachtJarProcessor;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import net.fabricmc.loom.configuration.processors.JarProcessor;
import net.fabricmc.loom.configuration.providers.cosmicreach.ManifestLocations;
import net.fabricmc.loom.configuration.providers.cosmicreach.CosmicReachJarConfiguration;
import net.fabricmc.loom.util.DeprecationHelper;

/**
 * This is the public api available exposed to build scripts.
 */
public interface LoomGradleExtensionAPI {
	@ApiStatus.Internal
	DeprecationHelper getDeprecationHelper();

	RegularFileProperty getAccessWidenerPath();

	NamedDomainObjectContainer<DecompilerOptions> getDecompilerOptions();

	void decompilers(Action<NamedDomainObjectContainer<DecompilerOptions>> action);
	ListProperty<RemapperExtensionHolder> getRemapperExtensions();

	@Deprecated()
	ListProperty<JarProcessor> getGameJarProcessors();

	@Deprecated()
	default void addJarProcessor(JarProcessor processor) {
		getGameJarProcessors().add(processor);
	}
	NamedDomainObjectList<RemapConfigurationSettings> getRemapConfigurations();
	void createRemapConfigurations(SourceSet sourceSet);
	RemapConfigurationSettings addRemapConfiguration(String name, Action<RemapConfigurationSettings> action);

	ListProperty<CosmicReachtJarProcessor<?>> getMinecraftJarProcessors();

	void addCosmicReachJarProcessor(Class<? extends CosmicReachtJarProcessor<?>> clazz, Object... parameters);

	ConfigurableFileCollection getLog4jConfigs();

	void runs(Action<NamedDomainObjectContainer<RunConfigSettings>> action);

	NamedDomainObjectContainer<RunConfigSettings> getRunConfigs();

	/**
	 * {@return the value of {@link #getRunConfigs}}
	 * This is an alias for it that matches {@link #runs}.
	 */
	default NamedDomainObjectContainer<RunConfigSettings> getRuns() {
		return getRunConfigs();
	}

	void mixin(Action<MixinExtensionAPI> action);
	<T extends RemapperParameters> void addRemapperExtension(Class<? extends RemapperExtension<T>> remapperExtensionClass, Class<T> parametersClass, Action<T> parameterAction);

	/**
	 * Optionally register and configure a {@link ModSettings} object. The name should match the modid.
	 * This is generally only required when the mod spans across multiple classpath directories, such as when using split sourcesets.
	 */
	void mods(Action<NamedDomainObjectContainer<ModSettings>> action);

	NamedDomainObjectContainer<ModSettings> getMods();

	@ApiStatus.Experimental
	// TODO: move this from LoomGradleExtensionAPI to LoomGradleExtension once getRefmapName & setRefmapName is removed.
	MixinExtensionAPI getMixin();

	default void interfaceInjection(Action<InterfaceInjectionExtensionAPI> action) {
		action.execute(getInterfaceInjection());
	}

	InterfaceInjectionExtensionAPI getInterfaceInjection();

	@ApiStatus.Experimental
	default void versionsManifests(Action<VersionsManifestsAPI> action) {
		action.execute(getVersionsManifests());
	}

	@ApiStatus.Experimental
	ManifestLocations getVersionsManifests();

	/**
	 * @deprecated use {@linkplain #getCustomMinecraftMetadata} instead
	 */
	@Deprecated
	default Property<String> getCustomMinecraftManifest() {
		return getCustomMinecraftMetadata();
	}

	Property<String> getCustomMinecraftMetadata();

	SetProperty<String> getKnownIndyBsms();

	/**
	 * Disables the deprecated POM generation for a publication.
	 * This is useful if you want to suppress deprecation warnings when you're not using software components.
	 *
	 * <p>Experimental API: Will be removed in Loom 0.12 together with the deprecated POM generation functionality.
	 *
	 * @param publication the maven publication
	 */
	@ApiStatus.Experimental
	void disableDeprecatedPomGeneration(MavenPublication publication);

	/**
	 * Reads the mod version from the fabric.mod.json file located in the main sourcesets resources.
	 * This is useful if you want to set the gradle version based of the version in the fabric.mod.json file.
	 *
	 * @return the version defined in the fabric.mod.json
	 */
	String getModVersion();

	/**
	 * When true loom will apply mod provided javadoc from dependencies.
	 *
	 * @return the property controlling the mod provided javadoc
	 */
	Property<Boolean> getEnableModProvidedJavadoc();

	/**
	 * Use "%1$s" as a placeholder for the minecraft version.
	 *
	 * @return the intermediary url template
	 */
	Property<String> getIntermediaryUrl();

	@ApiStatus.Experimental
	Property<CosmicReachJarConfiguration<?, ?>> getMinecraftJarConfiguration();

	default void serverOnlyMinecraftJar() {
		getMinecraftJarConfiguration().set(CosmicReachJarConfiguration.SERVER_ONLY);
	}

	default void clientOnlyMinecraftJar() {
		getMinecraftJarConfiguration().set(CosmicReachJarConfiguration.CLIENT_ONLY);
	}

	default void splitMinecraftJar() {
		getMinecraftJarConfiguration().set(CosmicReachJarConfiguration.SPLIT);
	}

	void splitEnvironmentSourceSets();

	boolean areEnvironmentSourceSetsSplit();

	Property<Boolean> getRuntimeOnlyLog4j();

	Property<Boolean> getSplitModDependencies();

	/**
	 * @return The minecraft version, as a {@link Provider}.
	 */
	Provider<String> getMinecraftVersion();
}
