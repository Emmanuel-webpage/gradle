/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.plugins;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.internal.DomainObjectCollectionInternal;
import org.gradle.api.internal.artifacts.configurations.ConfigurationRolesForMigration;
import org.gradle.api.internal.artifacts.configurations.RoleBasedConfigurationContainerInternal;
import org.gradle.api.internal.plugins.BuildConfigurationRule;
import org.gradle.api.internal.plugins.DefaultArtifactPublicationSet;
import org.gradle.api.internal.plugins.NaggingBasePluginConvention;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.internal.DefaultBasePluginExtension;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.internal.deprecation.DeprecationLogger;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

import java.util.stream.Collectors;

/**
 * <p>A {@link org.gradle.api.Plugin} which defines a basic project lifecycle and some common convention properties.</p>
 *
 * @see <a href="https://docs.gradle.org/current/userguide/base_plugin.html">Base plugin reference</a>
 */
public abstract class BasePlugin implements Plugin<Project> {
    public static final String CLEAN_TASK_NAME = LifecycleBasePlugin.CLEAN_TASK_NAME;
    public static final String ASSEMBLE_TASK_NAME = LifecycleBasePlugin.ASSEMBLE_TASK_NAME;
    public static final String BUILD_GROUP = LifecycleBasePlugin.BUILD_GROUP;

    @Override
    public void apply(final Project project) {
        project.getPluginManager().apply(LifecycleBasePlugin.class);

        BasePluginExtension baseExtension = project.getExtensions().create(BasePluginExtension.class, "base", DefaultBasePluginExtension.class, project);

        addConvention(project, baseExtension);
        configureExtension(project, baseExtension);
        configureBuildConfigurationRule(project);
        configureArchiveDefaults(project, baseExtension);
        configureConfigurations(project);
    }


    @SuppressWarnings("deprecation")
    private static void addConvention(Project project, BasePluginExtension baseExtension) {
        BasePluginConvention convention = project.getObjects().newInstance(org.gradle.api.plugins.internal.DefaultBasePluginConvention.class, baseExtension);
        DeprecationLogger.whileDisabled(() -> {
            project.getConvention().getPlugins().put("base", new NaggingBasePluginConvention(convention));
        });
    }

    private static void configureExtension(Project project, BasePluginExtension extension) {
        extension.getArchivesName().convention(project.getName());
        extension.getLibsDirectory().convention(project.getLayout().getBuildDirectory().dir("libs"));
        extension.getDistsDirectory().convention(project.getLayout().getBuildDirectory().dir("distributions"));
    }

    private static void configureArchiveDefaults(final Project project, final BasePluginExtension extension) {
        project.getTasks().withType(AbstractArchiveTask.class).configureEach(task -> {
            task.getDestinationDirectory().convention(extension.getDistsDirectory());
            task.getArchiveVersion().convention(
                project.provider(() -> project.getVersion() == Project.DEFAULT_VERSION ? null : project.getVersion().toString())
            );

            task.getArchiveBaseName().convention(extension.getArchivesName());
        });
    }

    private static void configureBuildConfigurationRule(Project project) {
        project.getTasks().addRule(new BuildConfigurationRule(project.getConfigurations(), project.getTasks()));
    }

    private static void configureConfigurations(final Project project) {
        RoleBasedConfigurationContainerInternal configurations = (RoleBasedConfigurationContainerInternal) project.getConfigurations();
        ((ProjectInternal) project).getInternalStatus().convention("integration");

        final Configuration archivesConfiguration = configurations.maybeCreateMigratingUnlocked(Dependency.ARCHIVES_CONFIGURATION, ConfigurationRolesForMigration.CONSUMABLE_TO_REMOVED)
            .setDescription("Configuration for archive artifacts.");

        ((DomainObjectCollectionInternal<?>) archivesConfiguration.getArtifacts()).beforeCollectionChanges(methodName ->
            DeprecationLogger.deprecateBehaviour("Adding artifacts to the archives configuration.")
            .withContext("The 'archives' configuration will be removed in Gradle 9.0")
            .withAdvice("To ensure an artifact is built by the 'assemble' task, use tasks.assemble.dependsOn(artifact)")
            .willBecomeAnErrorInGradle9()
            .withUpgradeGuideSection(8, "deprecate_automatically_assembled_artifacts")
            .nagUser()
        );

        configurations.maybeCreateConsumableUnlocked(Dependency.DEFAULT_CONFIGURATION)
            .setDescription("Configuration for default artifacts.");

        // This extension is deprecated, adding artifacts to it directly adds artifacts to the archives configuration.
        // Even though it is deprecated, Kotlin still uses it.
        // See https://github.com/JetBrains/kotlin/blob/54da79fbc4034054c724b6be89cf6f4aca225fe5/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/targets/native/configureBinaryFrameworks.kt#L91-L100
        project.getExtensions().create("defaultArtifacts", DefaultArtifactPublicationSet.class, archivesConfiguration.getArtifacts());

        // Do not nag about automatically adding artifacts to the archives configuration.
        // This would require us telling users to call setVisible(false) on all consumable configurations.
        // However, we plan to get rid of setVisible, so we don't want users littering that call everywhere
        // Instead, we will change this behavior of auto-building artifacts in 9.0 and just notify about
        // the change in behavior in the upgrade guide.
        // TODO: When removing this code be sure to add an entry to the upgrade guide as a potential breaking change.
        DeprecationLogger.whileDisabled(() ->
            archivesConfiguration.getArtifacts().addAllLater(project.provider(() ->
                configurations.stream()
                    .filter(conf -> !conf.equals(archivesConfiguration) && conf.isVisible())
                    .flatMap(conf -> conf.getArtifacts().stream())
                    .collect(Collectors.toList())
            ))
        );

        project.getTasks().named(ASSEMBLE_TASK_NAME, task -> {
            task.dependsOn(archivesConfiguration.getAllArtifacts());
        });
    }
}
