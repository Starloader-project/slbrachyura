package io.github.coolcrabs.brachyura.maven;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import io.github.coolcrabs.brachyura.dependency.JavaJarDependency;

/**
 * A small primitive resolver for maven artifacts.
 * All artifacts are cached to a local folder. Nonexisting artifacts
 * are not cached however.
 *
 * @author Geolykt
 */
public class MavenResolver {

    @NotNull
    private final List<MavenRepository> repositories = new ArrayList<>();
    @NotNull
    private final Path cacheFolder;

    public MavenResolver(@NotNull Path cacheFolder) {
        this.cacheFolder = cacheFolder;
    }

    public void addRepository(@NotNull MavenRepository repository) {
        this.repositories.add(repository);
    }

    public void addRepositories(@NotNull Collection<MavenRepository> repositories) {
        this.repositories.addAll(repositories);
    }

    @NotNull
    public ResolvedFile resolveArtifact(@NotNull MavenId artifact, @NotNull String classifier, @NotNull String extension) throws IOException {
        String folder = artifact.groupId.replace('.', '/') + '/' + artifact.artifactId + '/' + artifact.version + '/';
        String nameString;
        if (classifier.isEmpty()) {
            nameString = artifact.artifactId + '-' + artifact.version + '.' + extension;
        } else {
            nameString = artifact.artifactId + '-' + artifact.version + '-' + classifier + '.' + extension;
        }
        ResolvedFile directFile = resolveFileContents(folder, nameString);
        if (directFile != null) {
            return directFile;
        }

        ResolvedFile mavenMeta = resolveFileContents(folder, "maven-metadata.xml");
        if (mavenMeta != null) {
            // TODO evaluate using the maven meta
            throw new UnsupportedOperationException("Not yet supported!");
        } else {
            throw new IOException("Unable to resolve artifact: maven-metadata.xml is missing and the file"
                    + " was not able to be fetched directly.");
        }
    }

    @Nullable
    private ResolvedFile resolveFileContents(@NotNull String folder, @NotNull String file) {
        Path cacheFile = cacheFolder.resolve(folder).resolve(file);
        if (Files.exists(cacheFile)) {
            try {
                ResolvedFile f = new ResolvedFile(null, Files.readAllBytes(cacheFile));
                f.setCachePath(cacheFile);
                return f;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (MavenRepository repo : repositories) {
            ResolvedFile resolved = repo.resolve(folder, file);
            if (resolved != null) {
                try {
                    Files.createDirectories(cacheFile.getParent());
                    Files.write(cacheFile, resolved.data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    resolved.setCachePath(cacheFile);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to write to cache", e);
                }
                return resolved;
            }
        }
        return null;
    }

    private void getTransitiveDependencyVersions(@NotNull MavenId artifact, @NotNull Map<VersionlessMavenId, MavenId> versions) {
        VersionlessMavenId verlessMavenId = new VersionlessMavenId(artifact.groupId, artifact.artifactId);
        if (versions.containsKey(verlessMavenId)) {
            String currentver = versions.get(verlessMavenId).version;
            String[] versionOld = currentver.split("\\.");
            String[] versionNew = artifact.version.split("\\.");
            int minLen = Math.min(versionOld.length, versionNew.length);
            for (int i = 0; i < minLen; i++) {
                String versionPartOld = versionOld[i];
                String versionPartNew = versionNew[i];
                if (versionPartOld.length() > versionPartNew.length()) {
                    return;
                } else if (versionPartNew.length() == versionPartOld.length()) {
                    int cmp = versionPartOld.compareTo(versionPartNew);
                    if (cmp == 0) {
                        continue;
                    } else if (cmp < 0) {
                        // Currently queried one is newer
                        break;
                    } else {
                        // Currently queried one is older
                        return;
                    }
                } else {
                    break;
                }
            }
            if (versionOld.length > versionNew.length) {
                return;
            } else if (versionOld.length == versionNew.length) {
                return;
            }
        }
        versions.put(verlessMavenId, artifact);
        try {
            Document xmlDoc;
            {
                ResolvedFile file = resolveArtifact(artifact, "", "pom");
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                xmlDoc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(file.data));
            }
            Element project = xmlDoc.getDocumentElement();
            project.normalize();
            NodeList dependencies;
            {
                dependencies = project.getElementsByTagName("dependencies");
                if (dependencies.getLength() == 0) {
                    return;
                } else if (dependencies.getLength() != 1) {
                    throw new IllegalStateException("Pom for artifact " + artifact.toString() + " contains multiple "
                            + "dependencies blocks.");
                }
                dependencies = ((Element) dependencies.item(0)).getElementsByTagName("dependency");
            }
            for (int i = 0; i < dependencies.getLength(); i++) {
                Node depend = dependencies.item(i);
                if (!depend.hasChildNodes()) {
                    continue;
                }
                Element elem = (Element) depend;
                String groupId = elem.getElementsByTagName("groupId").item(0).getTextContent();
                String artifactId = elem.getElementsByTagName("artifactId").item(0).getTextContent();
                String version = elem.getElementsByTagName("version").item(0).getTextContent();
                if (groupId == null || artifactId == null || version == null) {
                    throw new IllegalStateException("Dependency block in pom of " + artifact.toString() + " contains either"
                            + " no groupId, artifactId or version element.");
                }
                getTransitiveDependencyVersions(new MavenId(groupId, artifactId, version), versions);
            }
        } catch (SAXException | ParserConfigurationException e1) {
            e1.printStackTrace();
        } catch (IOException ignored) {}
    }

    /**
     * Obtains the jar dependency that corresponds to a maven artifact and if applicable obtains
     * all transitive dependencies of that dependency in a cyclic manner. The highest version is used for
     * each artifact, provided the artifact could be resolved.
     *
     * @param artifact The root artifact to resolve
     * @return A collection of resolved artifacts
     */
    @SuppressWarnings("null")
    @NotNull
    public Collection<JavaJarDependency> getTransitiveDependencies(@NotNull MavenId artifact) {
        Map<VersionlessMavenId, MavenId> versions = new HashMap<>();
        Map<MavenId, JavaJarDependency> dependencies = new HashMap<>();
        getTransitiveDependencyVersions(artifact, versions);
        versions.values().forEach(shouldBeDependency -> {
            JavaJarDependency resolvedDependency = getJarDepend(shouldBeDependency);
            if (resolvedDependency == null) {
                return;
            }
            dependencies.put(shouldBeDependency, resolvedDependency);
        });
        return dependencies.values();
    }

    @Nullable
    public JavaJarDependency getJarDepend(@NotNull MavenId artifact) {
        Path noSourcesCacheFile;
        {
            String folder = artifact.groupId.replace('.', '/') + '/' + artifact.artifactId + '/' + artifact.version + '/';
            String file = artifact.artifactId + '-' + artifact.version + "nosources";
            noSourcesCacheFile = cacheFolder.resolve(folder).resolve(file);
        }
        ResolvedFile sources = null;
        if (Files.notExists(noSourcesCacheFile)) {
            try {
                sources = resolveArtifact(artifact, "sources", "jar");
            } catch (Exception exception1) {
                try {
                    Files.createFile(noSourcesCacheFile);
                } catch (IOException exception2) {
                    IllegalStateException ex = new IllegalStateException("Cannot cache sources", exception2);
                    ex.addSuppressed(exception1);
                    throw ex;
                }
            }
        }
        try {
            Path sourcesPath = null;
            if (sources != null) {
                sourcesPath = sources.getCachePath();
            }
            ResolvedFile resolvedJar = resolveArtifact(artifact, "", "jar");
            Path jarPath = resolvedJar.getCachePath();
            if (jarPath == null) {
                return null;
            }
            return new JavaJarDependency(jarPath, sourcesPath, artifact);
        } catch (Exception e) {
            return null;
        }
    }
} class VersionlessMavenId {

    @NotNull
    private String groupId;
    @NotNull
    private String artifactId;

    VersionlessMavenId(@NotNull String group, @NotNull String artifact) {
        this.groupId = group;
        this.artifactId = artifact;
    }

    @Override
    public int hashCode() {
        return this.groupId.hashCode() ^ this.artifactId.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof VersionlessMavenId) {
            VersionlessMavenId other = (VersionlessMavenId) obj;
            return other.groupId.equals(this.groupId) && other.artifactId.equals(this.artifactId);
        }
        return false;
    }
}
