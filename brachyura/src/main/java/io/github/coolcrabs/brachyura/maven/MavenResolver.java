package io.github.coolcrabs.brachyura.maven;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import io.github.coolcrabs.brachyura.dependency.JavaJarDependency;
import io.github.coolcrabs.brachyura.dependency.MavenDependencyScope;
import io.github.coolcrabs.brachyura.util.IterableNodeList;
import io.github.coolcrabs.brachyura.util.PathUtil;

/**
 * A small primitive resolver for maven artifacts.
 * All artifacts are cached to a local folder. Artifacts that do not exist
 * are not cached however.
 *
 * @author Geolykt
 */
public class MavenResolver {

    // FIXME while this usually works, this is not exactly right. See https://stackoverflow.com/a/47833316
    @SuppressWarnings("null")
    @NotNull
    public static final Path MAVEN_LOCAL = PathUtil.HOME.resolve(".m2").resolve("repository");

    @NotNull
    public static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";

    @NotNull
    public static final MavenRepository MAVEN_CENTRAL_REPO = new HttpMavenRepository(MAVEN_CENTRAL);

    /**
     * Set of all artifacts that may not be resolved via {@link #getTransitiveDependencies(MavenId)}.
     * This means that all transitive child dependencies of the listed artifacts are also not resolved,
     * unless they are included via other means (such as being declared by another artifact or by
     * being directly resolved via the {@link #getTransitiveDependencies(MavenId)} method).
     *
     * <p>Any artifacts in this set may still get obtained directly via {@link #getJarDepend(MavenId)}.
     */
    @NotNull
    private final Set<VersionlessMavenId> blacklistedArtifacts = new HashSet<>();

    @NotNull
    private final List<MavenRepository> repositories = new ArrayList<>();
    @NotNull
    private final Path cacheFolder;

    /**
     * The default scope to use for the constructor of {@link JavaJarDependency}.
     * This scope as of now is used for any {@link JavaJarDependency} created by this instance
     * of the {@link MavenResolver}.
     * Default value is {@link MavenDependencyScope#COMPILE_ONLY} due to backwards compatibility reasons.
     */
    @NotNull
    private MavenDependencyScope defaultScope = MavenDependencyScope.COMPILE_ONLY;

    private boolean resolveProvidedDependencies = false;
    private boolean resolveTestDependencies = false;

    public MavenResolver(@NotNull Path cacheFolder) {
        this.cacheFolder = cacheFolder;
    }

    /**
     * Adds multiple repositories to the resolver. The repositories will be fetched in the order they occur in the iterator of the
     * collection. They will however not take precedence over currently existing repositories or the cache and they will be only
     * queried should the cache not contain a given file and if no preceding repositories were able to resolve the file.
     *
     * @param repositories The repositories to add
     * @return The current {@link MavenResolver} instance, for chaining
     */
    @NotNull
    @Contract(mutates = "this", pure = false, value = "!null -> this; null -> fail")
    public MavenResolver addRepositories(@NotNull Collection<MavenRepository> repositories) {
        this.repositories.addAll(repositories);
        return this;
    }

    @NotNull
    @Contract(mutates = "this", pure = false, value = "_ -> this")
    public MavenResolver addRepository(@NotNull MavenRepository repository) {
        this.repositories.add(repository);
        return this;
    }

    @SuppressWarnings("null")
    @NotNull
    private String applyPlaceholders(String string, @NotNull String groupId, @NotNull String artifactId, @NotNull String version, @NotNull Map<String, String> properties) {
        if (!string.contains("${")) {
            return string;
        }
        string = string.replace("${pom.", "${").replace("${project.", "${").replace("${groupId}", groupId).replace("${artifactId}", artifactId).replace("${version}", version);
        String str2 = string;
        string = properties.getOrDefault(string, string);
        if (string == str2) {
            if (string.contains("${")) {
                throw new IllegalStateException("Cannot simplify placeholders of string \"" + string + "\" further."
                        + " Placeholder used in: " + groupId + ":" + artifactId + ":" + version);
            }
        }
        return applyPlaceholders(string, groupId, artifactId, version, properties);
    }

    @Nullable
    public Path resolveArtifactLocationCached(@NotNull MavenId artifact, @NotNull String classifier, @NotNull String extension) {
        String folder = artifact.groupId.replace('.', '/') + '/' + artifact.artifactId + '/' + artifact.version + '/';
        String nameString;
        if (classifier.isEmpty()) {
            nameString = artifact.artifactId + '-' + artifact.version + '.' + extension;
        } else {
            nameString = artifact.artifactId + '-' + artifact.version + '-' + classifier + '.' + extension;
        }
        Path cacheFile = cacheFolder.resolve(folder).resolve(nameString);
        if (Files.exists(cacheFile)) {
            return cacheFile;
        }
        return null;
    }

    @NotNull
    public ResolvedFile resolveArtifact(@NotNull MavenId artifact, @Nullable String classifier, @NotNull String extension) throws IOException {
        String folder = artifact.groupId.replace('.', '/') + '/' + artifact.artifactId + '/' + artifact.version + '/';
        String nameString;
        if (classifier == null || classifier.isEmpty()) {
            classifier = null;
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
            String snapshotVer = getLastSnapshotVersion(mavenMeta, classifier, extension);
            if (snapshotVer == null) {
                throw new IOException("Cannot find artifact " + artifact + " in maven-metadata.xml. It couldn't be resolved directly either");
            }
            if (classifier == null) {
                nameString = artifact.artifactId + '-' + snapshotVer + '.' + extension;
            } else {
                nameString = artifact.artifactId + '-' + snapshotVer + '-' + classifier + '.' + extension;
            }
            directFile = resolveFileContents(folder, nameString);
            if (directFile == null) {
                throw new IOException("Unable to resolve artifact: " + folder + "," + nameString
                        + " despite it being defined in the maven-metadata.xml file!");
            }
            return directFile;
        } else {
            throw new IOException("Unable to resolve artifact: maven-metadata.xml is missing and the file"
                    + " was not able to be fetched directly.");
        }
    }

    @Nullable
    private ResolvedFile resolveFileContents(@NotNull String folder, @NotNull String file) {
        Path cacheFileParent = cacheFolder.resolve(folder);
        Path cacheFile = cacheFileParent.resolve(file);
        if (Files.exists(cacheFileParent)) {
            if (Files.exists(cacheFile)) {
                return new ResolvedFile(null, cacheFile);
            }
            Path nolookupFile = cacheFileParent.resolve(file + ".nolookup");
            if (Files.exists(nolookupFile)) {
                return null;
            }
            if (Files.exists(cacheFile, LinkOption.NOFOLLOW_LINKS)) {
                Logger.info("Deleting outdated symlink: " + cacheFile.toAbsolutePath());
                try {
                    Files.delete(cacheFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        for (MavenRepository repo : repositories) {
            ResolvedFile resolved = repo.resolve(folder, file);
            if (resolved != null) {
                IOException symlinkEx = null;
                if (resolved.getCachePath() != null) {
                    // Create symbol link to the resolved file
                    try {
                        Files.createDirectories(cacheFile.getParent());
                        Files.createSymbolicLink(cacheFile, resolved.getCachePath());
                        return resolved;
                    } catch (IOException e) {
                        // Cannot create symbolic link
                        symlinkEx = e;
                    }
                }
                try {
                    Files.createDirectories(cacheFile.getParent());
                    Files.write(cacheFile, resolved.getData(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    resolved.setCachePath(cacheFile);
                } catch (IOException e) {
                    IllegalStateException toThrow = new IllegalStateException("Unable to write to cache", e);
                    if (symlinkEx != null) {
                        toThrow.addSuppressed(symlinkEx);
                    }
                    throw toThrow;
                }
                return resolved;
            }
        }
        try {
            Files.createDirectories(cacheFileParent);
            Path nolookupFile = cacheFileParent.resolve(file + ".nolookup");
            Files.createFile(nolookupFile);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write to cache", e);
        }
        return null;
    }

    /**
     * Sets the default scope to use for the constructor of {@link JavaJarDependency}.
     * This scope as of now is used for any {@link JavaJarDependency} created by this instance
     * of the {@link MavenResolver}.
     *
     * <p>Default value is {@link MavenDependencyScope#COMPILE_ONLY} due to backwards compatibility reasons.
     *
     * <p>Furthermore it may be beneficial to switch between the types of scope depending on the situation.
     * For example it may not be fully required to have {@link MavenDependencyScope#COMPILE} for transitive
     * dependencies if only {@link MavenDependencyScope#PROVIDED} suffices in that instance.
     *
     * @param scope The scope to use from now on
     * @return The {@link MavenResolver} instance that was used to invoke this method. Used for chaining
     */
    @Contract(pure = false, mutates = "this", value = "null -> fail; !null -> this")
    @NotNull
    public MavenResolver setDefaultScope(@NotNull MavenDependencyScope scope) {
        Objects.requireNonNull(scope, "scope may not be null!");
        this.defaultScope = scope;
        return this;
    }

    private void getTransitiveDependencyVersions(@NotNull MavenId artifact, @NotNull Map<VersionlessMavenId, MavenId> versions,
            Set<VersionlessMavenId> unknownVersions) {
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
        unknownVersions.remove(verlessMavenId);
        try {
            Document xmlDoc;
            {
                ResolvedFile file = resolveArtifact(artifact, "", "pom");
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                xmlDoc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(file.getData()));
            }
            Element project = xmlDoc.getDocumentElement();
            project.normalize();
            NodeList dependencies;
            Map<String, String> placeholders = new HashMap<>();
            parsePlaceholders(xmlDoc, artifact, placeholders);
            {
                Element dependenciesBlock = null;
                for (Node block : new IterableNodeList(project.getChildNodes())) {
                    if (!block.hasChildNodes() || !(block instanceof Element)) {
                        continue;
                    }
                    Element blockElem = (Element) block;
                    if (blockElem.getTagName().equals("dependencies")) {
                        if (dependenciesBlock != null) {
                            throw new IllegalStateException("Pom for artifact " + artifact.toString() + " contains multiple "
                                    + "dependencies blocks.");
                        }
                        dependenciesBlock = blockElem;
                    }
                }
                if (dependenciesBlock == null) {
                    return;
                }
                dependencies = dependenciesBlock.getChildNodes();
            }

            for (Node depend : new IterableNodeList(dependencies)) {
                if (!depend.hasChildNodes() || !(depend instanceof Element)) {
                    continue;
                }
                Element elem = (Element) depend;
                if (!elem.getTagName().equals("dependency")) {
                    continue;
                }

                Node scopeElement = elem.getElementsByTagName("scope").item(0);
                if (scopeElement instanceof Element) {
                    Element scope = (Element) scopeElement;
                    if (!resolveTestDependencies && scope.getTextContent().equals("test")) {
                        continue;
                    } else if (!resolveProvidedDependencies && scope.getTextContent().equals("provided")) {
                        continue;
                    }
                }

                String groupId = elem.getElementsByTagName("groupId").item(0).getTextContent();
                String artifactId = elem.getElementsByTagName("artifactId").item(0).getTextContent();
                groupId = applyPlaceholders(groupId, artifact.groupId, artifact.artifactId, artifact.version, placeholders);
                artifactId = applyPlaceholders(artifactId, artifact.groupId, artifact.artifactId, artifact.version, placeholders);

                VersionlessMavenId dependencyVerlessId = new VersionlessMavenId(groupId, artifactId);
                if (blacklistedArtifacts.contains(dependencyVerlessId)) {
                    continue;
                }

                Node versionElement = elem.getElementsByTagName("version").item(0);
                if (versionElement == null) {
                    if (!versions.containsKey(verlessMavenId)) {
                        Logger.info(dependencyVerlessId);
                        unknownVersions.add(dependencyVerlessId);
                    }
                    continue;
                }

                String version = versionElement.getTextContent();
                version = applyPlaceholders(version, artifact.groupId, artifact.artifactId, artifact.version, placeholders);

                if (version.charAt(0) == '[') {
                    // Version range
                    if (version.endsWith(",)")) {
                        // No idea how to treat all these scenarios - I'll just do something that works
                        version = version.substring(1, version.length() - 2);
                    } else if (version.endsWith(")") && version.contains(", ")) {
                        int seperator = version.indexOf(", ");
                        version = version.substring(seperator + 2, version.length() - 1);
                    }
                }
                getTransitiveDependencyVersions(new MavenId(groupId, artifactId, version), versions, unknownVersions);
            }
        } catch (SAXException | ParserConfigurationException e1) {
            e1.printStackTrace();
        } catch (IOException ignored) {}
    }

    /**
     * Parse the placeholders based on the properties block of a maven pom.
     * If a parent artifact is specified in the pom, it will also be used for placeholders.
     * However the properties block from the child artifact has always precedence over the parent artifact.
     *
     * @param xmlDoc The xml document that represents the maven pom
     * @param artifact The artifact that is current resolved. The pom should come from this artifact.
     * @param out The map of placeholders. Keys are in the format of "${key}". Values are never overwritten.
     * @throws IOException In case the pom of the parent module of the artifact cannot be resolved or in case the pom cannot be parsed
     */
    @Contract(pure = false, mutates = "param3")
    private void parsePlaceholders(@NotNull Document xmlDoc, @NotNull MavenId artifact, @NotNull Map<String, String> out) throws IOException {
        Element project = xmlDoc.getDocumentElement();
        project.normalize();
        Element parentArtifactNode = null;
        for (Node block : new IterableNodeList(project.getChildNodes())) {
            if (!block.hasChildNodes() || !(block instanceof Element)) {
                continue;
            }
            Element blockElem = (Element) block;
            if (blockElem.getTagName().equals("properties")) {
                for (Node property : new IterableNodeList(blockElem.getChildNodes())) {
                    if (property instanceof Element) {
                        Element propertyElement = (Element) property;
                        out.putIfAbsent("${" + propertyElement.getTagName() + "}", propertyElement.getTextContent());
                    }
                }
            } else if (blockElem.getTagName().equals("parent")) {
                parentArtifactNode = blockElem;
            }
        }
        if (parentArtifactNode == null) {
            return;
        }
        NodeList groupId = parentArtifactNode.getElementsByTagName("groupId");
        NodeList artifactId = parentArtifactNode.getElementsByTagName("artifactId");
        NodeList version = parentArtifactNode.getElementsByTagName("version");

        if (groupId.getLength() != 1) {
            throw new IllegalStateException("Pom of artifact " + artifact + " does not specify the groupId of it's parent correctly.");
        }
        if (artifactId.getLength() != 1) {
            throw new IllegalStateException("Pom of artifact " + artifact + " does not specify the artifactId of it's parent correctly.");
        }
        if (version.getLength() != 1) {
            throw new IllegalStateException("Pom of artifact " + artifact + " does not specify the version of it's parent correctly.");
        }

        @SuppressWarnings("null")
        MavenId parentMavenId = new MavenId(groupId.item(0).getTextContent(), artifactId.item(0).getTextContent(), version.item(0).getTextContent());

        Document parentDocument;
        try {
            ResolvedFile file = resolveArtifact(parentMavenId, "", "pom");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            parentDocument = factory.newDocumentBuilder().parse(new ByteArrayInputStream(file.getData()));
            if (parentDocument == null) {
                throw new NullPointerException("parentDocument is null");
            }
            parsePlaceholders(parentDocument, parentMavenId, out);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Cannot parse maven pom of artifact " + parentMavenId + ", which is the parent module of "
                    + artifact, e);
        }
    }

    /**
     * Prevent the resolution of multiple artifacts (regardless of version) through the
     * {@link #getTransitiveDependencies(MavenId)} method. This means that all transitive child dependencies of
     * these artifacts are not resolved, unless they are included via other means (such as being declared by
     * another artifact or by being directly resolved via the {@link #getTransitiveDependencies(MavenId)} method).
     *
     * <p>Any artifacts in the set of blacklisted artifacts may still get obtained directly via {@link #getJarDepend(MavenId)}.
     *
     * @param artifacts The artifacts to blacklist
     * @return The current {@link MavenResolver} instance.
     */
    @Contract(mutates = "this", pure = false, value = "_ -> this")
    @NotNull
    public MavenResolver preventTransitiveResolution(@NotNull Iterable<MavenId> artifacts) {
        for (MavenId artifact : artifacts) {
            this.blacklistedArtifacts.add(new VersionlessMavenId(artifact.groupId, artifact.artifactId));
        }
        return this;
    }

    /**
     * Prevent the resolution of an artifact (regardless of version) through the {@link #getTransitiveDependencies(MavenId)} method.
     * This means that all transitive child dependencies of this artifact are not resolved, unless they are included via
     * other means (such as being declared by another artifact or by being directly resolved via the
     * {@link #getTransitiveDependencies(MavenId)} method).
     *
     * <p>Any artifacts in the set of blacklisted artifacts may still get obtained directly via {@link #getJarDepend(MavenId)}.
     *
     * @param artifact The artifact to blacklist
     * @return The current {@link MavenResolver} instance.
     */
    @Contract(mutates = "this", pure = false, value = "_ -> this")
    @NotNull
    public MavenResolver preventTransitiveResolution(@NotNull MavenId artifact) {
        this.blacklistedArtifacts.add(new VersionlessMavenId(artifact.groupId, artifact.artifactId));
        return this;
    }

    /**
     * Obtains the latest valid "value" value for a snapshot repository based on a maven-metadata.xml file
     * and a classifier and extension. A null classifier is treated as not existent.
     *
     * @param mavenMeta The maven-metadata.xml file
     * @param classifier The classifier of the artifact
     * @param extension The extension of the artifact
     * @return The "value" value that corresponds to the artifact, or null if undefined
     * @throws IOException If an exception happened while parsing the file
     */
    @Nullable
    private String getLastSnapshotVersion(@NotNull ResolvedFile mavenMeta, @Nullable String classifier, @NotNull String extension) throws IOException {
        Document xmlDoc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            xmlDoc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(mavenMeta.getData()));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Unable to parse maven-metadata.xml", e);
        }

        Element versioningElement = new IterableNodeList(xmlDoc.getDocumentElement().getChildNodes()).resolveFirstElement("versioning");
        if (versioningElement == null) {
            return null;
        }

        Element snapshotVersionsElement = new IterableNodeList(versioningElement.getChildNodes()).resolveFirstElement("snapshotVersions");
        if (snapshotVersionsElement == null) {
            return null;
        }

        for (Node node : new IterableNodeList(snapshotVersionsElement.getChildNodes())) {
            if (!(node instanceof Element)) {
                continue;
            }
            Element snapshotVersion = (Element) node;
            if (!snapshotVersion.getTagName().equals("snapshotVersion")) {
                // I do not think that any other tag is allowed there, but safe is safe
                continue;
            }
            if (!snapshotVersion.getElementsByTagName("extension").item(0).getTextContent().equals(extension)) {
                continue;
            }
            NodeList classifierNode = snapshotVersion.getElementsByTagName("classifier");
            if (classifierNode.getLength() == 0 && classifier != null
                    || classifier == null && classifierNode.getLength() != 0) {
                continue;
            }
            if (classifier == null) {
                if (classifierNode.getLength() != 0) {
                    continue;
                }
            } else if (classifierNode.getLength() == 0) {
                continue;
            } else {
                if (!classifierNode.item(0).getTextContent().equals(classifier)) {
                    continue;
                }
            }
            NodeList valueNode = snapshotVersion.getElementsByTagName("value");
            if (valueNode.getLength() == 0) {
                return null;
            }
            return valueNode.item(0).getTextContent();
        }
        return null;
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
        Set<VersionlessMavenId> unknownVersions = new HashSet<>();
        getTransitiveDependencyVersions(artifact, versions, unknownVersions);
        unknownVersions.forEach(mavenid -> {
            Logger.warn("The artifact \"" + artifact + "\" was required by a dependency, but the version was left unspecified! It was thus not resolved");
        });
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
        ResolvedFile sources = null;
        ResolvedFile ijAnnotations = null;
        try {
            sources = resolveArtifact(artifact, "sources", "jar");
        } catch (Exception ignored) {
        }
        try {
            ijAnnotations = resolveArtifact(artifact, "annotations", "zip");
        } catch (Exception ignored) {
            // We aren't fully done here, but close enough. See:
            // https://youtrack.jetbrains.com/issue/IDEA-132487#focus=streamItem-27-3082925.0-0
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
            Path ijAnnotPath = null;
            if (ijAnnotations != null) {
                ijAnnotPath = ijAnnotations.getCachePath();
            }
            return new JavaJarDependency(jarPath, sourcesPath, artifact, null, ijAnnotPath, defaultScope, null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sets whether dependencies of artifacts which have the "test" scope should be resolved.
     * This only affects transitive dependencies, dependencies that are directly resolved via {@link #getTransitiveDependencies(MavenId)}
     * or {@link #getJarDepend(MavenId)} are excluded.
     *
     * <p>The default value is false, which disables the resolution of artifacts with the "test" scope.
     *
     * @param resolveProvidedDependencies The new value
     * @return The current {@link MavenResolver} instance, for chaining
     */
    @Contract(mutates = "this", pure = false, value = "_ -> this")
    @NotNull
    public MavenResolver setResolveTestDependencies(boolean resolveTestDependencies) {
        this.resolveTestDependencies = resolveTestDependencies;
        return this;
    }

    /**
     * Sets whether dependencies of artifacts which have the "provided" scope should be resolved.
     * This only affects transitive dependencies, dependencies that are directly resolved via {@link #getTransitiveDependencies(MavenId)}
     * or {@link #getJarDepend(MavenId)} are excluded.
     *
     * <p>The default value is false, which disables the resolution of artifacts with the "provided" scope.
     *
     * @param resolveProvidedDependencies The new value
     * @return The current {@link MavenResolver} instance, for chaining
     */
    @Contract(mutates = "this", pure = false, value = "_ -> this")
    @NotNull
    public MavenResolver setResolveProvidedDependencies(boolean resolveProvidedDependencies) {
        this.resolveProvidedDependencies = resolveProvidedDependencies;
        return this;
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

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
