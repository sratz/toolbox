/*
 * Copyright (c) 2023-2024 Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.toolbox.shared.internal;

import static java.util.Objects.requireNonNull;

import eu.maveniverse.maven.toolbox.shared.ResolutionRoot;
import eu.maveniverse.maven.toolbox.shared.ResolutionScope;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.eclipse.aether.version.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ToolboxResolverImpl {
    private static final String CTX_TOOLBOX = "toolbox";
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> remoteRepositories;

    public ToolboxResolverImpl(
            RepositorySystem repositorySystem,
            RepositorySystemSession session,
            List<RemoteRepository> remoteRepositories) {
        this.repositorySystem = requireNonNull(repositorySystem, "repositorySystem");
        this.session = requireNonNull(session, "session");
        this.remoteRepositories = requireNonNull(remoteRepositories, "remoteRepositories");
    }

    public RepositorySystem getRepositorySystem() {
        return repositorySystem;
    }

    public RepositorySystemSession getSession() {
        return session;
    }

    public List<RemoteRepository> getRemoteRepositories() {
        return remoteRepositories;
    }

    public ArtifactDescriptorResult readArtifactDescriptor(Artifact artifact) throws ArtifactDescriptorException {
        ArtifactDescriptorRequest artifactDescriptorRequest =
                new ArtifactDescriptorRequest(artifact, remoteRepositories, CTX_TOOLBOX);
        return repositorySystem.readArtifactDescriptor(session, artifactDescriptorRequest);
    }

    public List<Dependency> importBOMs(Collection<String> boms) throws ArtifactDescriptorException {
        HashSet<String> keys = new HashSet<>();
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(this.session);
        session.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(false, false));
        ArrayList<Dependency> managedDependencies = new ArrayList<>();
        for (String bomGav : boms) {
            if (null == bomGav || bomGav.isEmpty()) {
                continue;
            }
            Artifact bom = new DefaultArtifact(bomGav);
            ArtifactDescriptorResult artifactDescriptorResult = readArtifactDescriptor(bom);
            artifactDescriptorResult.getManagedDependencies().forEach(d -> {
                if (keys.add(ArtifactIdUtils.toVersionlessId(d.getArtifact()))) {
                    managedDependencies.add(d);
                } else {
                    logger.warn("BOM {} introduced an already managed dependency {}", bom, d);
                }
            });
        }
        return managedDependencies;
    }

    public Artifact parseGav(String gav, List<Dependency> managedDependencies) {
        try {
            return new DefaultArtifact(gav);
        } catch (IllegalArgumentException e) {
            if (managedDependencies != null) {
                // assume it is g:a and we have v in depMgt section
                return managedDependencies.stream()
                        .map(Dependency::getArtifact)
                        .filter(a -> gav.equals(a.getGroupId() + ":" + a.getArtifactId()))
                        .findFirst()
                        .orElseThrow(() -> e);
            } else {
                throw e;
            }
        }
    }

    public RemoteRepository parseRemoteRepository(String spec) {
        String[] parts = spec.split("::");
        String id = "mima";
        String type = "default";
        String url;
        if (parts.length == 1) {
            url = parts[0];
        } else if (parts.length == 2) {
            id = parts[0];
            url = parts[1];
        } else if (parts.length == 3) {
            id = parts[0];
            type = parts[1];
            url = parts[2];
        } else {
            throw new IllegalArgumentException("Invalid remote repository spec");
        }
        return repositorySystem.newDeploymentRepository(session, new RemoteRepository.Builder(id, type, url).build());
    }

    public ResolutionRoot loadGav(String gav, Collection<String> boms) throws ArtifactDescriptorException {
        List<Dependency> managedDependency = importBOMs(boms);
        Artifact artifact = parseGav(gav, managedDependency);
        return loadRoot(ResolutionRoot.ofLoaded(artifact)
                .withManagedDependencies(managedDependency)
                .build());
    }

    public ResolutionRoot loadRoot(ResolutionRoot resolutionRoot) throws ArtifactDescriptorException {
        if (resolutionRoot.isPrepared()) {
            return resolutionRoot;
        }
        if (resolutionRoot.isLoad()) {
            ArtifactDescriptorResult artifactDescriptorResult = readArtifactDescriptor(resolutionRoot.getArtifact());
            resolutionRoot = ResolutionRoot.ofLoaded(resolutionRoot.getArtifact())
                    .withDependencies(
                            mergeDeps(resolutionRoot.getDependencies(), artifactDescriptorResult.getDependencies()))
                    .withManagedDependencies(mergeDeps(
                            resolutionRoot.getManagedDependencies(), artifactDescriptorResult.getManagedDependencies()))
                    .build();
        }
        return resolutionRoot.prepared();
    }

    private List<Dependency> mergeDeps(List<Dependency> dominant, List<Dependency> recessive) {
        List<Dependency> result;
        if (dominant == null || dominant.isEmpty()) {
            result = recessive;
        } else if (recessive == null || recessive.isEmpty()) {
            result = dominant;
        } else {
            int initialCapacity = dominant.size() + recessive.size();
            result = new ArrayList<>(initialCapacity);
            Collection<String> ids = new HashSet<>(initialCapacity, 1.0f);
            for (Dependency dependency : dominant) {
                ids.add(getId(dependency.getArtifact()));
                result.add(dependency);
            }
            for (Dependency dependency : recessive) {
                if (!ids.contains(getId(dependency.getArtifact()))) {
                    result.add(dependency);
                }
            }
        }
        return result;
    }

    private static String getId(Artifact a) {
        return a.getGroupId() + ':' + a.getArtifactId() + ':' + a.getClassifier() + ':' + a.getExtension();
    }

    public CollectResult collect(
            ResolutionScope resolutionScope,
            Artifact root,
            List<Dependency> dependencies,
            List<Dependency> managedDependencies,
            boolean verbose)
            throws DependencyCollectionException {
        return doCollect(resolutionScope, null, root, dependencies, managedDependencies, remoteRepositories, verbose);
    }

    public CollectResult collect(
            ResolutionScope resolutionScope,
            Dependency root,
            List<Dependency> dependencies,
            List<Dependency> managedDependencies,
            boolean verbose)
            throws DependencyCollectionException {
        return doCollect(resolutionScope, root, null, dependencies, managedDependencies, remoteRepositories, verbose);
    }

    public DependencyResult resolve(
            ResolutionScope resolutionScope,
            Artifact root,
            List<Dependency> dependencies,
            List<Dependency> managedDependencies)
            throws DependencyResolutionException {
        return doResolve(resolutionScope, null, root, dependencies, managedDependencies, remoteRepositories);
    }

    public DependencyResult resolve(
            ResolutionScope resolutionScope,
            Dependency root,
            List<Dependency> dependencies,
            List<Dependency> managedDependencies)
            throws DependencyResolutionException {
        return doResolve(resolutionScope, root, null, dependencies, managedDependencies, remoteRepositories);
    }

    private CollectResult doCollect(
            ResolutionScope resolutionScope,
            Dependency rootDependency,
            Artifact root,
            List<Dependency> dependencies,
            List<Dependency> managedDependencies,
            List<RemoteRepository> remoteRepositories,
            boolean verbose)
            throws DependencyCollectionException {
        requireNonNull(resolutionScope);
        if (rootDependency == null && root == null) {
            throw new NullPointerException("one of rootDependency or root must be non-null");
        }

        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(this.session);
        if (verbose) {
            session.setConfigProperty(ConflictResolver.CONFIG_PROP_VERBOSE, ConflictResolver.Verbosity.FULL);
            session.setConfigProperty(DependencyManagerUtils.CONFIG_PROP_VERBOSE, true);
        }
        logger.debug("Collecting scope: {}", resolutionScope.name());

        CollectRequest collectRequest = new CollectRequest();
        if (rootDependency != null) {
            root = rootDependency.getArtifact();
        }
        collectRequest.setRootArtifact(root);
        collectRequest.setDependencies(dependencies.stream()
                .filter(d -> !resolutionScope.isEliminateTest() || !JavaScopes.TEST.equals(d.getScope()))
                .collect(Collectors.toList()));
        collectRequest.setManagedDependencies(managedDependencies);
        collectRequest.setRepositories(remoteRepositories);
        collectRequest.setRequestContext(CTX_TOOLBOX);
        collectRequest.setTrace(RequestTrace.newChild(null, collectRequest));

        logger.debug("Collecting {}", collectRequest);
        CollectResult result = repositorySystem.collectDependencies(session, collectRequest);
        if (!verbose && resolutionScope != ResolutionScope.TEST) {
            ArrayList<DependencyNode> childrenToRemove = new ArrayList<>();
            for (DependencyNode node : result.getRoot().getChildren()) {
                if (!resolutionScope
                        .getDirectInclude()
                        .contains(node.getDependency().getScope())) {
                    childrenToRemove.add(node);
                }
            }
            if (!childrenToRemove.isEmpty()) {
                result.getRoot().getChildren().removeAll(childrenToRemove);
            }
        }
        return result;
    }

    private DependencyResult doResolve(
            ResolutionScope resolutionScope,
            Dependency rootDependency,
            Artifact root,
            List<Dependency> dependencies,
            List<Dependency> managedDependencies,
            List<RemoteRepository> remoteRepositories)
            throws DependencyResolutionException {
        requireNonNull(resolutionScope);
        if (rootDependency == null && root == null) {
            throw new NullPointerException("one of rootDependency or root must be non-null");
        }

        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(this.session);
        logger.debug("Resolving scope: {}", resolutionScope.name());

        CollectRequest collectRequest = new CollectRequest();
        if (rootDependency != null) {
            root = rootDependency.getArtifact();
        }
        collectRequest.setRootArtifact(root);
        collectRequest.setDependencies(dependencies.stream()
                .filter(d -> !resolutionScope.isEliminateTest() || !JavaScopes.TEST.equals(d.getScope()))
                .collect(Collectors.toList()));
        collectRequest.setManagedDependencies(managedDependencies);
        collectRequest.setRepositories(remoteRepositories);
        collectRequest.setRequestContext(CTX_TOOLBOX);
        collectRequest.setTrace(RequestTrace.newChild(null, collectRequest));
        DependencyRequest dependencyRequest =
                new DependencyRequest(collectRequest, resolutionScope.getDependencyFilter());

        logger.debug("Resolving {}", dependencyRequest);
        DependencyResult result = repositorySystem.resolveDependencies(session, dependencyRequest);
        try {
            ArtifactResult rootResult =
                    resolveArtifacts(Collections.singletonList(root)).get(0);

            DefaultDependencyNode newRoot = new DefaultDependencyNode(new Dependency(rootResult.getArtifact(), ""));
            newRoot.setChildren(result.getRoot().getChildren());
            result.setRoot(newRoot);
            result.getArtifactResults().add(0, rootResult);
            return result;
        } catch (ArtifactResolutionException e) {
            throw new DependencyResolutionException(result, e);
        }
    }

    public List<ArtifactResult> resolveArtifacts(Collection<Artifact> artifacts) throws ArtifactResolutionException {
        requireNonNull(artifacts);

        List<ArtifactRequest> artifactRequests = new ArrayList<>();
        artifacts.forEach(a -> artifactRequests.add(new ArtifactRequest(a, remoteRepositories, null)));
        return repositorySystem.resolveArtifacts(session, artifactRequests);
    }

    public Version findNewestVersion(Artifact artifact, boolean allowSnapshots) throws VersionRangeResolutionException {
        VersionRangeRequest rangeRequest = new VersionRangeRequest();
        rangeRequest.setArtifact(new DefaultArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getClassifier(),
                artifact.getExtension(),
                "[0,)"));
        rangeRequest.setRepositories(remoteRepositories);
        rangeRequest.setRequestContext(CTX_TOOLBOX);
        VersionRangeResult result = repositorySystem.resolveVersionRange(session, rangeRequest);
        Version highest = result.getHighestVersion();
        if (allowSnapshots || !highest.toString().endsWith("SNAPSHOT")) {
            return highest;
        } else {
            for (int idx = result.getVersions().size() - 1; idx >= 0; idx--) {
                highest = result.getVersions().get(idx);
                if (!highest.toString().endsWith("SNAPSHOT")) {
                    return highest;
                }
            }
            return null;
        }
    }

    public List<Artifact> listAvailablePlugins(Collection<String> groupIds) throws Exception {
        DefaultRepositorySystemSession session = new DefaultRepositorySystemSession(this.session);
        session.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS);

        RequestTrace trace = RequestTrace.newChild(null, this);

        List<MetadataRequest> requests = new ArrayList<>();
        for (String groupId : groupIds) {
            org.eclipse.aether.metadata.Metadata metadata =
                    new DefaultMetadata(groupId, "maven-metadata.xml", DefaultMetadata.Nature.RELEASE);
            for (RemoteRepository repository : remoteRepositories) {
                requests.add(new MetadataRequest(metadata, repository, CTX_TOOLBOX).setTrace(trace));
            }
        }

        HashSet<String> processedGAs = new HashSet<>();
        ArrayList<Artifact> result = new ArrayList<>();
        List<MetadataResult> results = repositorySystem.resolveMetadata(session, requests);
        for (MetadataResult res : results) {
            org.eclipse.aether.metadata.Metadata metadata = res.getMetadata();
            if (metadata != null
                    && metadata.getFile() != null
                    && metadata.getFile().isFile()) {
                try (InputStream inputStream =
                        Files.newInputStream(metadata.getFile().toPath())) {
                    org.apache.maven.artifact.repository.metadata.Metadata pluginGroupMetadata =
                            new MetadataXpp3Reader().read(inputStream, false);
                    List<org.apache.maven.artifact.repository.metadata.Plugin> plugins =
                            pluginGroupMetadata.getPlugins();
                    for (org.apache.maven.artifact.repository.metadata.Plugin plugin : plugins) {
                        if (processedGAs.add(metadata.getGroupId() + ":" + plugin.getArtifactId())) {
                            Artifact blueprint =
                                    new DefaultArtifact(metadata.getGroupId(), plugin.getArtifactId(), "jar", "0");
                            Version newestVersion = findNewestVersion(blueprint, false);
                            if (newestVersion != null) {
                                result.add(new DefaultArtifact(
                                        blueprint.getGroupId(),
                                        blueprint.getArtifactId(),
                                        blueprint.getExtension(),
                                        newestVersion.toString()));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
}
