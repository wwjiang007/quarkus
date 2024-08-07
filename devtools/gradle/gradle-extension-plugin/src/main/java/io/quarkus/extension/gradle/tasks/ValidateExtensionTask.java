package io.quarkus.extension.gradle.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedModuleVersion;
import org.gradle.api.logging.Logger;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import io.quarkus.extension.gradle.QuarkusExtensionConfiguration;
import io.quarkus.gradle.tooling.dependency.ArtifactExtensionDependency;
import io.quarkus.gradle.tooling.dependency.DependencyUtils;
import io.quarkus.gradle.tooling.dependency.ExtensionDependency;
import io.quarkus.gradle.tooling.dependency.ProjectExtensionDependency;
import io.quarkus.maven.dependency.ArtifactKey;

public class ValidateExtensionTask extends DefaultTask {

    private Configuration runtimeModuleClasspath;
    private Configuration deploymentModuleClasspath;

    @Inject
    public ValidateExtensionTask(QuarkusExtensionConfiguration quarkusExtensionConfiguration,
            Configuration runtimeModuleClasspath) {
        setDescription("Validate extension dependencies");
        setGroup("quarkus");

        this.runtimeModuleClasspath = runtimeModuleClasspath;
        this.onlyIf(t -> !quarkusExtensionConfiguration.isValidationDisabled().get());

        // Calling this method tells Gradle that it should not fail the build. Side effect is that the configuration
        // cache will be at least degraded, but the build will not fail.
        notCompatibleWithConfigurationCache("The Quarkus Extension Plugin isn't compatible with the configuration cache");
    }

    @Internal
    public Configuration getRuntimeModuleClasspath() {
        return this.runtimeModuleClasspath;
    }

    @Internal
    public Configuration getDeploymentModuleClasspath() {
        return this.deploymentModuleClasspath;
    }

    public void setDeploymentModuleClasspath(Configuration deploymentModuleClasspath) {
        this.deploymentModuleClasspath = deploymentModuleClasspath;
    }

    @TaskAction
    public void validateExtension() {
        Set<ResolvedArtifact> runtimeArtifacts = getRuntimeModuleClasspath().getResolvedConfiguration().getResolvedArtifacts();

        List<ArtifactKey> deploymentModuleKeys = collectRuntimeExtensionsDeploymentKeys(runtimeArtifacts);
        List<ArtifactKey> invalidRuntimeArtifacts = findExtensionInConfiguration(runtimeArtifacts, deploymentModuleKeys);

        Set<ResolvedArtifact> deploymentArtifacts = getDeploymentModuleClasspath().getResolvedConfiguration()
                .getResolvedArtifacts();
        List<ArtifactKey> existingDeploymentModuleKeys = findExtensionInConfiguration(deploymentArtifacts,
                deploymentModuleKeys);
        deploymentModuleKeys.removeAll(existingDeploymentModuleKeys);

        boolean hasErrors = false;
        if (!invalidRuntimeArtifacts.isEmpty()) {
            hasErrors = true;
        }
        if (!deploymentModuleKeys.isEmpty()) {
            hasErrors = true;
        }

        if (hasErrors) {
            printValidationErrors(invalidRuntimeArtifacts, deploymentModuleKeys);
        }
    }

    private List<ArtifactKey> collectRuntimeExtensionsDeploymentKeys(Set<ResolvedArtifact> runtimeArtifacts) {
        List<ArtifactKey> runtimeExtensions = new ArrayList<>();
        for (ResolvedArtifact resolvedArtifact : runtimeArtifacts) {
            ExtensionDependency<?> extension = DependencyUtils.getExtensionInfoOrNull(getProject(), resolvedArtifact);
            if (extension != null) {
                if (extension instanceof ProjectExtensionDependency ped) {
                    runtimeExtensions
                            .add(ArtifactKey.ga(ped.getDeploymentModule().getGroup().toString(),
                                    ped.getDeploymentModule().getName()));
                } else if (extension instanceof ArtifactExtensionDependency aed) {
                    runtimeExtensions.add(ArtifactKey.ga(aed.getDeploymentModule().getGroupId(),
                            aed.getDeploymentModule().getArtifactId()));
                }
            }
        }
        return runtimeExtensions;
    }

    private List<ArtifactKey> findExtensionInConfiguration(Set<ResolvedArtifact> deploymentArtifacts,
            List<ArtifactKey> extensions) {
        List<ArtifactKey> foundExtensions = new ArrayList<>();

        for (ResolvedArtifact deploymentArtifact : deploymentArtifacts) {
            ArtifactKey key = toArtifactKey(deploymentArtifact.getModuleVersion());
            if (extensions.contains(key)) {
                foundExtensions.add(key);
            }
        }
        return foundExtensions;
    }

    private void printValidationErrors(List<ArtifactKey> invalidRuntimeArtifacts,
            List<ArtifactKey> missingDeploymentArtifacts) {
        Logger log = getLogger();
        log.error("Quarkus Extension Dependency Verification Error");

        if (!invalidRuntimeArtifacts.isEmpty()) {
            log.error("The following deployment artifact(s) appear on the runtime classpath: ");
            for (ArtifactKey invalidRuntimeArtifact : invalidRuntimeArtifacts) {
                log.error("- " + invalidRuntimeArtifact);
            }
        }

        if (!missingDeploymentArtifacts.isEmpty()) {
            log.error("The following deployment artifact(s) were found to be missing in the deployment module: ");
            for (ArtifactKey missingDeploymentArtifact : missingDeploymentArtifacts) {
                log.error("- " + missingDeploymentArtifact);
            }
        }

        throw new GradleException("Quarkus Extension Dependency Verification Error. See logs below");
    }

    private static ArtifactKey toArtifactKey(ResolvedModuleVersion artifactId) {
        return ArtifactKey.ga(artifactId.getId().getGroup(), artifactId.getId().getName());
    }
}
