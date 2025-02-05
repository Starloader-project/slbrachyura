package io.github.coolcrabs.brachyura.fabric;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.coolcrabs.accesswidener.AccessWidener;
import io.github.coolcrabs.accesswidener.AccessWidenerReader;
import io.github.coolcrabs.brachyura.compiler.java.JavaCompilationOptions;
import io.github.coolcrabs.brachyura.decompiler.BrachyuraDecompiler;
import io.github.coolcrabs.brachyura.decompiler.cfr.CfrDecompiler;
import io.github.coolcrabs.brachyura.dependency.JavaJarDependency;
import io.github.coolcrabs.brachyura.dependency.MavenDependency;
import io.github.coolcrabs.brachyura.fabric.FabricContext.ModDependencyCollector;
import io.github.coolcrabs.brachyura.ide.IdeModule;
import io.github.coolcrabs.brachyura.maven.LocalMavenRepository;
import io.github.coolcrabs.brachyura.exception.UnknownJsonException;
import io.github.coolcrabs.brachyura.mappings.Namespaces;
import io.github.coolcrabs.brachyura.maven.MavenId;
import io.github.coolcrabs.brachyura.maven.MavenResolver;
import io.github.coolcrabs.brachyura.maven.publish.AuthentificatedMavenPublishRepository;
import io.github.coolcrabs.brachyura.maven.publish.MavenPublisher;
import io.github.coolcrabs.brachyura.minecraft.VersionMeta;
import io.github.coolcrabs.brachyura.processing.ProcessorChain;
import io.github.coolcrabs.brachyura.processing.sinks.AtomicZipProcessingSink;
import io.github.coolcrabs.brachyura.processing.sources.DirectoryProcessingSource;
import io.github.coolcrabs.brachyura.project.Task;
import io.github.coolcrabs.brachyura.project.java.BaseJavaProject;
import io.github.coolcrabs.brachyura.util.Lazy;
import io.github.coolcrabs.brachyura.util.PathUtil;
import io.github.coolcrabs.brachyura.util.Util;
import net.fabricmc.mappingio.tree.MappingTree;

public abstract class SimpleFabricProject extends BaseJavaProject {

    @NotNull
    private final JavaCompilationOptions compileOptions = new JavaCompilationOptions();

    public final Lazy<FabricContext> context = new Lazy<>(this::createContext);
    protected FabricContext createContext() {
        return new SimpleFabricContext();
    }

    public final Lazy<FabricModule> module = new Lazy<>(this::createModule);
    protected FabricModule createModule() {
        return new SimpleFabricModule(context.get());
    }

    public abstract VersionMeta createMcVersion();
    public abstract MappingTree createMappings();
    public abstract FabricLoader getLoader();
    public abstract void getModDependencies(ModDependencyCollector d);

    public int getJavaVersion() {
        return 8;
    }

    public BrachyuraDecompiler decompiler() {
        return new CfrDecompiler();
    }

    public Path[] getSrcDirs() {
        return new Path[]{getProjectDir().resolve("src").resolve("main").resolve("java")};
    }

    public Path[] getResourceDirs() {
        return new Path[]{getProjectDir().resolve("src").resolve("main").resolve("resources")};
    }

    /**
     * The list of dependencies that should be JIJ'd into the jar.
     *
     * <p>Note that adding dependencies to this list alone may not impact the build classpath
     * as well as the runtime classpath if launched via the "runServer"/"runClient" tasks.
     *
     * <p>Therefore these dependencies (whether they are mods or not) should also be added through the
     * conventional route of {@link #getModDependencies(ModDependencyCollector)}.
     */
    protected ArrayList<JavaJarDependency> jijList = new ArrayList<>();

    @Nullable
    public AccessWidener createAw() {
        String aw = fmjParseThingy.get()[2];
        if (aw == null) return null;
        for (Path r : getResourceDirs()) {
            Path awp = r.resolve(aw);
            if (Files.exists(awp)) {
                AccessWidener result = new AccessWidener(Namespaces.NAMED);
                try {
                    try (BufferedReader read = Files.newBufferedReader(awp)) {
                        new AccessWidenerReader(result).read(read);
                    }
                } catch (IOException e) {
                    throw Util.sneak(e);
                }
                return result;
            }
        }
        throw new UnknownJsonException("Unable to find aw named:" + aw);
    }

    @Nullable
    public String getMavenGroup() {
        return null;
    }

    @NotNull
    public MavenId getId() {
        String group = getMavenGroup();
        return group == null ? new MavenId(getModId(), getModId(), getVersion()) : new MavenId(group, getModId(), getVersion());
    }

    @SuppressWarnings("null")
    @NotNull
    public String getModId() {
        return fmjParseThingy.get()[0];
    }

    @SuppressWarnings("null")
    @NotNull
    public String getVersion() {
        return fmjParseThingy.get()[1];
    }

    public JavaJarDependency jij(JavaJarDependency mod) {
        jijList.add(mod);
        return mod;
    }

    public MappingTree createMojmap() {
        return context.get().createMojmap();
    }

    private Lazy<String[]> fmjParseThingy = new Lazy<>(() -> {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().setLenient().create();
            JsonObject fabricModJson;
            Path fmj = null;
            for (Path resDir : getResourceDirs()) {
                Path p = resDir.resolve("fabric.mod.json");
                if (Files.exists(p)) {
                    fmj = p;
                    break;
                }
            }
            try (BufferedReader reader = PathUtil.newBufferedReader(fmj)) {
                fabricModJson = gson.fromJson(reader, JsonObject.class);
            }
            JsonElement aw = fabricModJson.get("accessWidener");
            return new String[] {fabricModJson.get("id").getAsString(), fabricModJson.get("version").getAsString(), aw == null ? null : aw.getAsString()};
        } catch (Exception e) {
            throw Util.sneak(e);
        }
    });

    public class SimpleFabricContext extends FabricContext {
        @Override
        public VersionMeta createMcVersion() {
            return SimpleFabricProject.this.createMcVersion();
        }

        @Override
        public MappingTree createMappings() {
            return SimpleFabricProject.this.createMappings();
        }

        @Override
        public FabricLoader getLoader() {
            return SimpleFabricProject.this.getLoader();
        }

        @Override
        public void getModDependencies(ModDependencyCollector d) {
            SimpleFabricProject.this.getModDependencies(d);
        }

        @Override
        @Nullable
        protected AccessWidener createAw() {
            return SimpleFabricProject.this.createAw();
        }

        @Override
        @Nullable
        public BrachyuraDecompiler decompiler() {
            return SimpleFabricProject.this.decompiler();
        }

        @Override
        public Path getContextRoot() {
            return getProjectDir();
        }
    }

    public class SimpleFabricModule extends FabricModule {
        public SimpleFabricModule(FabricContext context) {
            super(context);
        }

        @Override
        public int getJavaVersion() {
            return SimpleFabricProject.this.getJavaVersion();
        }

        @Override
        public Path[] getSrcDirs() {
            return SimpleFabricProject.this.getSrcDirs();
        }

        @Override
        public Path[] getResourceDirs() {
            return SimpleFabricProject.this.getResourceDirs();
        }

        @Override
        @NotNull
        public String getModuleName() {
            return getModId();
        }

        @Override
        @NotNull
        public Path getModuleRoot() {
            return getProjectDir();
        }

        @Override
        @NotNull
        protected JavaCompilationOptions getExtraCompileOptions() {
            return getCompileOptions();
        }
    }

    @NotNull
    @Override
    public List<@NotNull Task> getTasks() {
        List<@NotNull Task> tasks = super.getTasks();
        tasks.add(Task.builder("build", this).build(this::build));
        tasks.addAll(getPublishTasks());
        return tasks;
    }

    @NotNull
    public List<@NotNull Task> getPublishTasks() { // Slbrachyura: Improved task system
        List<@NotNull Task> tasks = new ArrayList<>();
        tasks.add(Task.builder("publishToMavenLocal", this).build(() -> {
            MavenPublisher publisher = new MavenPublisher().addRepository(new LocalMavenRepository(MavenResolver.MAVEN_LOCAL));
            List<MavenDependency> mavendeps = new ArrayList<>();
            ModDependencyCollector dependencies = new ModDependencyCollector();
            getModDependencies(dependencies);
            dependencies.dependencies.forEach(dep -> {
                if (dep.jarDependency instanceof MavenDependency) {
                    mavendeps.add((MavenDependency) dep.jarDependency);
                }
            });
            publisher.publishJar(build(), mavendeps);
        }));
        tasks.add(Task.builder("publish", this).build(() -> {
            MavenPublisher publisher = new MavenPublisher().addRepository(AuthentificatedMavenPublishRepository.fromEnvironmentVariables());
            List<MavenDependency> mavendeps = new ArrayList<>();
            ModDependencyCollector dependencies = new ModDependencyCollector();
            getModDependencies(dependencies);
            dependencies.dependencies.forEach(dep -> {
                if (dep.jarDependency instanceof MavenDependency) {
                    mavendeps.add((MavenDependency) dep.jarDependency);
                }
            });
            publisher.publishJar(build(), mavendeps);
        }));
        return tasks;
    }

    @Deprecated // Slbrachyura: Deprecate task handling with consumers
    public final void getPublishTasks(@NotNull Consumer<@NotNull Task> p) {
        getPublishTasks().forEach(p);
    }

    @Override
    @NotNull
    public IdeModule @NotNull [] getIdeModules() {
        return new @NotNull IdeModule[]{module.get().ideModule()};
    }

    @NotNull
    public ProcessorChain resourcesProcessingChain() {
        return context.get().resourcesProcessingChain(jijList);
    }

    @NotNull
    public JavaJarDependency build() {
        try {
            try (AtomicZipProcessingSink out = new AtomicZipProcessingSink(getBuildJarPath())) {
                context.get().modDependencies.get(); // Ugly hack
                resourcesProcessingChain().apply(out, Arrays.stream(getResourceDirs()).map(DirectoryProcessingSource::new).collect(Collectors.toList()));
                context.get().getRemappedClasses(module.get()).values().forEach(s -> s.getInputs(out));
                out.commit();
            }
            return new JavaJarDependency(getBuildJarPath(), null, getId());
        } catch (Exception e) {
            throw Util.sneak(e);
        }
    }

    @NotNull
    public Path getBuildJarPath() {
        return getBuildLibsDir().resolve(getModId() + "-" + getVersion() + ".jar");
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
