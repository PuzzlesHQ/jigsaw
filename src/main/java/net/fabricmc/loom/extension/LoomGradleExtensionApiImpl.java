/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2024 FabricMC
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

package net.fabricmc.loom.extension;

import java.io.IOException;
import java.util.Set;

import net.fabricmc.loom.api.remapping.RemapperExtension;
import net.fabricmc.loom.api.remapping.RemapperParameters;
import net.fabricmc.loom.configuration.RemapConfigurations;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.NamedDomainObjectList;
import org.gradle.api.Project;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.publish.maven.MavenPublication;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.InterfaceInjectionExtensionAPI;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.api.MixinExtensionAPI;
import net.fabricmc.loom.api.ModSettings;
import net.fabricmc.loom.api.RemapConfigurationSettings;
import net.fabricmc.loom.api.decompilers.DecompilerOptions;
import net.fabricmc.loom.api.processor.CosmicReachtJarProcessor;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import net.fabricmc.loom.configuration.processors.JarProcessor;
import net.fabricmc.loom.configuration.providers.cosmicreach.ManifestLocations;
import net.fabricmc.loom.configuration.providers.cosmicreach.CosmicReachJarConfiguration;
import net.fabricmc.loom.configuration.providers.cosmicreach.CosmicReachMetadataProvider;
import net.fabricmc.loom.configuration.providers.cosmicreach.CosmicReachSourceSets;
import net.fabricmc.loom.util.DeprecationHelper;
import net.fabricmc.loom.util.MirrorUtil;
import net.fabricmc.loom.util.fmj.PuzzleModJson;
import net.fabricmc.loom.util.fmj.FabricModJsonFactory;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

import org.gradle.api.tasks.SourceSet;
import org.slf4j.LoggerFactory;

/**
 * This class implements the public extension api.
 */
public abstract class LoomGradleExtensionApiImpl implements LoomGradleExtensionAPI {
	protected final DeprecationHelper deprecationHelper;
	@Deprecated()
	protected final ListProperty<JarProcessor> jarProcessors;
	protected final ConfigurableFileCollection log4jConfigs;
	protected final RegularFileProperty accessWidener;
	protected final ManifestLocations versionsManifests;
	protected final Property<String> customMetadata;
	protected final SetProperty<String> knownIndyBsms;
	protected final Property<Boolean> transitiveAccessWideners;
	protected final Property<Boolean> modProvidedJavadoc;
	protected final Property<String> intermediary;
	private final Property<Boolean> runtimeOnlyLog4j;
	private final Property<Boolean> splitModDependencies;
	private final Property<CosmicReachJarConfiguration<?, ?>> minecraftJarConfiguration;
	private final Property<Boolean> splitEnvironmentalSourceSet;
	private final InterfaceInjectionExtensionAPI interfaceInjectionExtension;

	private final NamedDomainObjectContainer<RunConfigSettings> runConfigs;
	private final NamedDomainObjectContainer<DecompilerOptions> decompilers;
	private final NamedDomainObjectContainer<ModSettings> mods;
	private final NamedDomainObjectList<RemapConfigurationSettings> remapConfigurations;
	private final ListProperty<CosmicReachtJarProcessor<?>> minecraftJarProcessors;
	protected final ListProperty<RemapperExtensionHolder> remapperExtensions;

	// A common mistake with layered mappings is to call the wrong `officialMojangMappings` method, use this to keep track of when we are building a layered mapping spec.
	protected final ThreadLocal<Boolean> layeredSpecBuilderScope = ThreadLocal.withInitial(() -> false);
	public static final String DEFAULT_INTERMEDIARY_URL = "https://maven.fabricmc.net/net/fabricmc/intermediary/%1$s/intermediary-%1$s-v2.jar";

	protected boolean hasEvaluatedLayeredMappings = false;

	protected LoomGradleExtensionApiImpl(Project project, LoomFiles directories) {
		this.jarProcessors = project.getObjects().listProperty(JarProcessor.class)
				.empty();
		this.log4jConfigs = project.files(directories.getDefaultLog4jConfigFile());
		this.accessWidener = project.getObjects().fileProperty();
		this.versionsManifests = new ManifestLocations();
		this.versionsManifests.add("cosmic_reach", MirrorUtil.getVersionManifests(project), -2);
		this.versionsManifests.add("puzzle_cosmic_reach", MirrorUtil.getVersionManifests2(project), -2);
//		this.versionsManifests.add("fabric_experimental", MirrorUtil.getExperimentalVersions(project), -1);
		this.customMetadata = project.getObjects().property(String.class);
		this.knownIndyBsms = project.getObjects().setProperty(String.class).convention(Set.of(
				"java/lang/invoke/StringConcatFactory",
				"java/lang/runtime/ObjectMethods",
				"org/codehaus/groovy/vmplugin/v8/IndyInterface"
		));
		this.knownIndyBsms.finalizeValueOnRead();
		this.transitiveAccessWideners = project.getObjects().property(Boolean.class)
				.convention(true);
		this.transitiveAccessWideners.finalizeValueOnRead();
		this.modProvidedJavadoc = project.getObjects().property(Boolean.class)
				.convention(true);
		this.modProvidedJavadoc.finalizeValueOnRead();
		this.intermediary = project.getObjects().property(String.class)
				.convention(DEFAULT_INTERMEDIARY_URL);

		this.deprecationHelper = new DeprecationHelper.ProjectBased(project);

		this.runConfigs = project.container(RunConfigSettings.class,
				baseName -> project.getObjects().newInstance(RunConfigSettings.class, project, baseName));
		this.decompilers = project.getObjects().domainObjectContainer(DecompilerOptions.class);
		this.mods = project.getObjects().domainObjectContainer(ModSettings.class);
		this.remapConfigurations = project.getObjects().namedDomainObjectList(RemapConfigurationSettings.class);
		//noinspection unchecked
		this.minecraftJarProcessors = (ListProperty<CosmicReachtJarProcessor<?>>) (Object) project.getObjects().listProperty(CosmicReachtJarProcessor.class);
		this.minecraftJarProcessors.finalizeValueOnRead();

		//noinspection unchecked
		this.minecraftJarConfiguration = project.getObjects().property((Class<CosmicReachJarConfiguration<?, ?>>) (Class<?>) CosmicReachJarConfiguration.class)
				.convention(project.provider(() -> {
					final LoomGradleExtension extension = LoomGradleExtension.get(project);
					final CosmicReachMetadataProvider metadataProvider = extension.getMetadataProvider();

					// if no configuration is selected by the user, attempt to select one
					// based on the mc version and which sides are present for it
					if (metadataProvider.getVersionEntry().server == null) {
						return CosmicReachJarConfiguration.CLIENT_ONLY;
					} else if (metadataProvider.getVersionEntry().client == null) {
						return CosmicReachJarConfiguration.SERVER_ONLY;
					} /*else if (metadataProvider.getVersionMeta().isVersionOrNewer(Constants.RELEASE_TIME_1_3)) {
						return CosmicReachJarConfiguration.MERGED;
					} else {
						return CosmicReachJarConfiguration.LEGACY_MERGED;
					}*/
					return CosmicReachJarConfiguration.MERGED;
				}));
		this.minecraftJarConfiguration.finalizeValueOnRead();

		this.accessWidener.finalizeValueOnRead();
		this.getGameJarProcessors().finalizeValueOnRead();

		this.runtimeOnlyLog4j = project.getObjects().property(Boolean.class).convention(false);
		this.runtimeOnlyLog4j.finalizeValueOnRead();

		this.splitModDependencies = project.getObjects().property(Boolean.class).convention(true);
		this.splitModDependencies.finalizeValueOnRead();

		this.interfaceInjectionExtension = project.getObjects().newInstance(InterfaceInjectionExtensionAPI.class);
		this.interfaceInjectionExtension.getIsEnabled().convention(true);

		this.splitEnvironmentalSourceSet = project.getObjects().property(Boolean.class).convention(false);
		this.splitEnvironmentalSourceSet.finalizeValueOnRead();

		// Enable dep iface injection by default
		interfaceInjection(interfaceInjection -> {
			interfaceInjection.getEnableDependencyInterfaceInjection().convention(true).finalizeValueOnRead();
		});
		remapperExtensions = project.getObjects().listProperty(RemapperExtensionHolder.class);
		remapperExtensions.finalizeValueOnRead();

	}

	@Override
	public DeprecationHelper getDeprecationHelper() {
		return deprecationHelper;
	}

	@Override
	public RegularFileProperty getAccessWidenerPath() {
		return accessWidener;
	}

	@Override
	public ListProperty<RemapperExtensionHolder> getRemapperExtensions() {
		return remapperExtensions;
	}

	@Override
	public NamedDomainObjectContainer<DecompilerOptions> getDecompilerOptions() {
		return decompilers;
	}

	@Override
	public void decompilers(Action<NamedDomainObjectContainer<DecompilerOptions>> action) {
		action.execute(decompilers);
	}

	@Override
	public ListProperty<JarProcessor> getGameJarProcessors() {
		return jarProcessors;
	}

	@Override
	public NamedDomainObjectList<RemapConfigurationSettings> getRemapConfigurations() {
		return remapConfigurations;
	}
	@Override
	public RemapConfigurationSettings addRemapConfiguration(String name, Action<RemapConfigurationSettings> action) {
		final RemapConfigurationSettings configurationSettings = getProject().getObjects().newInstance(RemapConfigurationSettings.class, name);

		// TODO remove in 2.0, this is a fallback to mimic the previous (Broken) behaviour
		configurationSettings.getSourceSet().convention(SourceSetHelper.getMainSourceSet(getProject()));

		action.execute(configurationSettings);
		RemapConfigurations.applyToProject(getProject(), configurationSettings);
		remapConfigurations.add(configurationSettings);

		return configurationSettings;
	}

	public void createRemapConfigurations(SourceSet sourceSet) {
		RemapConfigurations.setupForSourceSet(getProject(), sourceSet);
	}
	@Override
	public ListProperty<CosmicReachtJarProcessor<?>> getMinecraftJarProcessors() {
		return minecraftJarProcessors;
	}

	@Override
	public void addCosmicReachJarProcessor(Class<? extends CosmicReachtJarProcessor<?>> clazz, Object... parameters) {
		getMinecraftJarProcessors().add(getProject().getObjects().newInstance(clazz, parameters));

	}

	@Override
	public void runs(Action<NamedDomainObjectContainer<RunConfigSettings>> action) {
		action.execute(runConfigs);
	}

	@Override
	public NamedDomainObjectContainer<RunConfigSettings> getRunConfigs() {
		return runConfigs;
	}

	@Override
	public ConfigurableFileCollection getLog4jConfigs() {
		return log4jConfigs;
	}

	@Override
	public void mixin(Action<MixinExtensionAPI> action) {
		action.execute(getMixin());
	}
	@Override
	public <T extends RemapperParameters> void addRemapperExtension(Class<? extends RemapperExtension<T>> remapperExtensionClass, Class<T> parametersClass, Action<T> parameterAction) {
		final ObjectFactory objectFactory = getProject().getObjects();
		final RemapperExtensionHolder holder;

		if (parametersClass != RemapperParameters.None.class) {
			T parameters = objectFactory.newInstance(parametersClass);
			parameterAction.execute(parameters);
			holder = objectFactory.newInstance(RemapperExtensionHolder.class, parameters);
		} else {
			holder = objectFactory.newInstance(RemapperExtensionHolder.class, RemapperParameters.None.INSTANCE);
		}

		holder.getRemapperExtensionClass().set(remapperExtensionClass.getName());
		remapperExtensions.add(holder);
	}
	@Override
	public ManifestLocations getVersionsManifests() {
		return versionsManifests;
	}

	@Override
	public Property<String> getCustomMinecraftMetadata() {
		return customMetadata;
	}

	@Override
	public SetProperty<String> getKnownIndyBsms() {
		return knownIndyBsms;
	}

	@Override
	public String getModVersion() {
		try {
			final PuzzleModJson fabricModJson = FabricModJsonFactory.createFromSourceSetsNullable(SourceSetHelper.getMainSourceSet(getProject()));

			if (fabricModJson == null) {
				throw new RuntimeException("Could not find a fabric.mod.json file in the main sourceset");
			}

			return fabricModJson.getModVersion();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read mod version from main sourceset.", e);
		}
	}

	@Override
	public Property<Boolean> getEnableModProvidedJavadoc() {
		return modProvidedJavadoc;
	}

	protected abstract Project getProject();

	protected abstract LoomFiles getFiles();

	@Override
	public Property<String> getIntermediaryUrl() {
		return intermediary;
	}

	@Override
	public void disableDeprecatedPomGeneration(MavenPublication publication) {
		net.fabricmc.loom.configuration.MavenPublication.excludePublication(publication);
	}

	@Override
	public Property<CosmicReachJarConfiguration<?, ?>> getMinecraftJarConfiguration() {
		return minecraftJarConfiguration;
	}

	@Override
	public Property<Boolean> getRuntimeOnlyLog4j() {
		return runtimeOnlyLog4j;
	}

	@Override
	public Property<Boolean> getSplitModDependencies() {
		return splitModDependencies;
	}

	@Override
	public void splitEnvironmentSourceSets() {
		splitMinecraftJar();

		splitEnvironmentalSourceSet.set(true);

		// We need to lock these values, as we setup the new source sets right away.
		splitEnvironmentalSourceSet.finalizeValue();
		minecraftJarConfiguration.finalizeValue();

		CosmicReachSourceSets.get(getProject()).evaluateSplit(getProject());
	}

	@Override
	public boolean areEnvironmentSourceSetsSplit() {
		return splitEnvironmentalSourceSet.get();
	}

	@Override
	public InterfaceInjectionExtensionAPI getInterfaceInjection() {
		return interfaceInjectionExtension;
	}

	@Override
	public void mods(Action<NamedDomainObjectContainer<ModSettings>> action) {
		action.execute(getMods());
	}

	@Override
	public NamedDomainObjectContainer<ModSettings> getMods() {
		return mods;
	}

	@Override
	public Provider<String> getMinecraftVersion() {
		return getProject().provider(() -> LoomGradleExtension.get(getProject()).getCosmicReachProvider().cosmicReachVersion());
	}

}
