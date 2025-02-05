package io.github.coolcrabs.brachyura.project.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.tools.StandardLocation;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import io.github.coolcrabs.brachyura.compiler.java.CompilationFailedException;
import io.github.coolcrabs.brachyura.compiler.java.JavaCompilation;
import io.github.coolcrabs.brachyura.compiler.java.JavaCompilationOptions;
import io.github.coolcrabs.brachyura.dependency.JavaJarDependency;
import io.github.coolcrabs.brachyura.ide.IdeModule;
import io.github.coolcrabs.brachyura.maven.LocalMavenRepository;
import io.github.coolcrabs.brachyura.maven.MavenId;
import io.github.coolcrabs.brachyura.maven.MavenResolver;
import io.github.coolcrabs.brachyura.maven.publish.AuthentificatedMavenPublishRepository;
import io.github.coolcrabs.brachyura.maven.publish.MavenPublisher;
import io.github.coolcrabs.brachyura.processing.ProcessorChain;
import io.github.coolcrabs.brachyura.processing.sinks.AtomicZipProcessingSink;
import io.github.coolcrabs.brachyura.processing.sources.DirectoryProcessingSource;
import io.github.coolcrabs.brachyura.project.Task;
import io.github.coolcrabs.brachyura.util.Lazy;

public abstract class SimpleJavaProject extends BaseJavaProject {

    @NotNull
    private final JavaCompilationOptions compileOptions = new JavaCompilationOptions();

    @NotNull
    public abstract MavenId getId();

    public int getJavaVersion() {
        return 8;
    }

    @SuppressWarnings("null")
    @NotNull
    public List<JavaJarDependency> createDependencies() {
        return Collections.emptyList();
    }

    public final Lazy<SimpleJavaModule> projectModule = new Lazy<>(this::createProjectModule);

    public SimpleJavaModule createProjectModule() {
        return new SimpleJavaProjectModule();
    }

    public ProcessorChain getResourceProcessorChain() {
        return new ProcessorChain();
    }

    public class SimpleJavaProjectModule extends SimpleJavaModule {
        @Override
        public int getJavaVersion() {
            return SimpleJavaProject.this.getJavaVersion();
        }

        @NotNull
        @Override
        public Path @NotNull[] getSrcDirs() {
            return new @NotNull Path[]{getModuleRoot().resolve("src").resolve("main").resolve("java")};
        }

        @NotNull
        @Override
        public Path @NotNull[] getResourceDirs() {
            return new @NotNull Path[]{getProjectDir().resolve("src").resolve("main").resolve("resources")};
        }

        @Override
        @NotNull
        protected List<JavaJarDependency> createDependencies() {
            return SimpleJavaProject.this.createDependencies();
        }

        @Override
        @NotNull
        public String getModuleName() {
            return getId().artifactId;
        }

        @Override
        @NotNull
        public Path getModuleRoot() {
            return getProjectDir();
        }

        @Override
        @NotNull
        @Contract(pure = true, value = "-> new")
        protected JavaCompilation createCompilation() {
            return getCompileOptions().commit(super.createCompilation());
        }
    }

    @NotNull
    public String getJarBaseName() {
        return getId().artifactId + "-" + getId().version;
    }

    @NotNull
    @Override
    public List<@NotNull Task> getTasks() {
        List<@NotNull Task> tasks = super.getTasks();
        tasks.add(Task.builder("build", this).build(this::build));
        tasks.addAll(getPublishTasks());
        return tasks;
    }

    public List<@NotNull Task> getPublishTasks() { // Slbrachyura: Improved task handling
        List<@NotNull Task> tasks = new ArrayList<>();
        tasks.add(Task.builder("publishToMavenLocal", this).build(() -> {
            MavenPublisher publisher = new MavenPublisher().addRepository(new LocalMavenRepository(MavenResolver.MAVEN_LOCAL));
            publisher.publishJar(build(), projectModule.get().dependencies.get());
        }));
        tasks.add(Task.builder("publish", this).build(() -> {
            MavenPublisher publisher = new MavenPublisher().addRepository(AuthentificatedMavenPublishRepository.fromEnvironmentVariables());
            publisher.publishJar(build(), projectModule.get().dependencies.get());
        }));
        return tasks;
    }

    @Deprecated // Slbrachyura: Deprecate task handling with consumers
    public final void getPublishTasks(@NotNull Consumer<@NotNull Task> p) {
        getPublishTasks().forEach(p);
    }

    @Deprecated
    public Lazy<@NotNull JavaJarDependency> buildResult = new io.github.coolcrabs.brachyura.util.DementiaLazy<>(this::build);

    @Override
    @NotNull
    public IdeModule @NotNull[] getIdeModules() {
        return new @NotNull IdeModule[] {projectModule.get().ideModule()};
    }

    @NotNull
    public JavaJarDependency build() throws CompilationFailedException {
        Path outjar = getBuildLibsDir().resolve(getJarBaseName() + ".jar");
        Path outjarsources = getBuildLibsDir().resolve(getJarBaseName() + "-sources.jar");
        try (
            AtomicZipProcessingSink jarSink = new AtomicZipProcessingSink(outjar);
            AtomicZipProcessingSink jarSourcesSink = new AtomicZipProcessingSink(outjarsources);
        ) {
            getResourceProcessorChain().apply(jarSink, Arrays.stream(projectModule.get().getResourceDirs()).map(DirectoryProcessingSource::new).collect(Collectors.toList()));
            projectModule.get().compilationOutput.get().getInputs(jarSink);
            for (Path p : projectModule.get().getSrcDirs()) {
                new DirectoryProcessingSource(p).getInputs(jarSourcesSink);
            }
            projectModule.get().compilationResult.get().getOutputLocation(StandardLocation.SOURCE_OUTPUT, jarSourcesSink);
            jarSink.commit();
            jarSourcesSink.commit();
        }
        MavenId mvnid = getId();
        return new JavaJarDependency(outjar, outjar, Objects.isNull(mvnid) ? new MavenId(getJarBaseName(), getJarBaseName(), "0.0.1-SNAPSHOT") : mvnid);
    }

    /**
     * Obtains the options that are passed to the compiler when invoked via {@link #build()}.
     * The returned object can be mutated and will share the state used in {@link #build()}.
     * {@link #build()} uses this method and not the underlying field to obtain the compilation
     * options so overriding this method is valid, albeit potentially not viable.
     *
     * @return The compilation options
     */
    @NotNull
    @Contract(pure = true)
    @Override
    public JavaCompilationOptions getCompileOptions() {
        return this.compileOptions;
    }
}
