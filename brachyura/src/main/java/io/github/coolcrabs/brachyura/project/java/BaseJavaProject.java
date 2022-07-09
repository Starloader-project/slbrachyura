package io.github.coolcrabs.brachyura.project.java;

import io.github.coolcrabs.brachyura.compiler.java.JavaCompilation;
import io.github.coolcrabs.brachyura.compiler.java.JavaCompilationResult;
import io.github.coolcrabs.brachyura.dependency.JavaJarDependency;
import java.nio.file.Path;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;
import org.tinylog.Logger;

import io.github.coolcrabs.brachyura.ide.Ide;
import io.github.coolcrabs.brachyura.ide.IdeModule;
import io.github.coolcrabs.brachyura.processing.sinks.DirectoryProcessingSink;
import io.github.coolcrabs.brachyura.project.Project;
import io.github.coolcrabs.brachyura.project.Task;
import io.github.coolcrabs.brachyura.util.ArrayUtil;
import io.github.coolcrabs.brachyura.util.JvmUtil;
import io.github.coolcrabs.brachyura.util.PathUtil;
import io.github.coolcrabs.brachyura.util.Util;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;

public abstract class BaseJavaProject extends Project {

    @NotNull
    public abstract IdeModule[] getIdeModules();

    @Override
    public void getTasks(@NotNull Consumer<@NotNull Task> p) {
        super.getTasks(p);
        getIdeTasks(p);
        getRunConfigTasks(p);
    }

    public void getIdeTasks(@NotNull Consumer<@NotNull Task> p) {
        for (Ide ide : Ide.getIdes()) {
            p.accept(Task.of(ide.ideName(), (Runnable) () -> {
                BaseJavaProject buildscriptProject = getBuildscriptProject();
                if (buildscriptProject != null) {
                    ide.updateProject(getProjectDir(), ArrayUtil.join(IdeModule.class, getIdeModules(), buildscriptProject.getIdeModules()));
                } else {
                    ide.updateProject(getProjectDir(), getIdeModules());
                }
            }));
        }
    }
    
    public void getRunConfigTasks(@NotNull Consumer<@NotNull Task> p) {
        IdeModule[] ms = getIdeModules();
        for (IdeModule m : ms) {
            for (IdeModule.RunConfig rc : m.runConfigs) { // Slbrachyura: TODO Document what the hell this does
                Logger.info("Debug run config name: " + rc.name);
                String tname = ms.length == 1 ? "run" + rc.name.replace(" ", "") : m.name.replace(" ", "") + ":run" + rc.name.replace(" ", "");
                p.accept(Task.of(tname, (Runnable) () -> runRunConfig(m, rc)));
            }
        }
    }
    
    public void runRunConfig(IdeModule ideProject, IdeModule.RunConfig rc) {
        try {
            LinkedHashSet<IdeModule> toCompile = new LinkedHashSet<>();
            Deque<IdeModule> a = new ArrayDeque<>();
            a.add(ideProject);
            a.addAll(rc.additionalModulesClasspath);
            while (!a.isEmpty()) {
                IdeModule m = a.pop();
                if (!toCompile.contains(m)) {
                    a.addAll(m.dependencyModules);
                    toCompile.add(m);
                }
            }
            HashMap<IdeModule, Path> mmap = new HashMap<>();
            for (IdeModule m : toCompile) {
                JavaCompilation compilation = new JavaCompilation();
                compilation.addOption(JvmUtil.compileArgs(JvmUtil.CURRENT_JAVA_VERSION, m.javaVersion));
                compilation.addOption("-proc:none");
                for (JavaJarDependency dep : m.dependencies.get()) {
                    compilation.addClasspath(dep.jar);
                }
                for (IdeModule m0 : m.dependencyModules) {
                    compilation.addClasspath(Objects.requireNonNull(mmap.get(m0), "Bad module dep " + m0.name));
                }
                for (Path srcDir : m.sourcePaths) {
                    compilation.addSourceDir(srcDir);
                }
                Path outDir = Files.createTempDirectory("brachyurarun");
                JavaCompilationResult result = compilation.compile();
                Objects.requireNonNull(result);
                result.getInputs(new DirectoryProcessingSink(outDir));
                mmap.put(m, outDir);
            }
            ArrayList<String> command = new ArrayList<>();
            command.add(JvmUtil.CURRENT_JAVA_EXECUTABLE);
            command.addAll(rc.vmArgs.get());
            command.add("-cp");
            ArrayList<Path> cp = new ArrayList<>(rc.classpath.get());
            cp.addAll(ideProject.resourcePaths);
            cp.add(mmap.get(ideProject));
            for (IdeModule m : rc.additionalModulesClasspath) {
                cp.add(mmap.get(m));
            }
            StringBuilder cpStr = new StringBuilder();
            for (Path p : cp) {
                cpStr.append(p.toString());
                cpStr.append(File.pathSeparator);
            }
            cpStr.setLength(cpStr.length() - 1);
            command.add(cpStr.toString());
            command.add(rc.mainClass);
            command.addAll(rc.args.get());
            new ProcessBuilder(command)
                .inheritIO()
                .directory(rc.cwd.toFile())
                .start()
                .waitFor();
        } catch (Exception e) {
            throw Util.sneak(e);
        }
    }

    @NotNull
    public Path getBuildLibsDir() {
        return PathUtil.resolveAndCreateDir(getBuildDir(), "libs");
    }

    @NotNull
    public Path getBuildDir() {
        return PathUtil.resolveAndCreateDir(getProjectDir(), "build");
    }

    @SuppressWarnings("null")
    @NotNull
    public Path getSrcDir() {
        return getProjectDir().resolve("src").resolve("main").resolve("java");
    }

    @NotNull
    public Path getResourcesDir() {
        Path resourceDir = getProjectDir().resolve("src").resolve("main").resolve("resources");
        if (!Files.exists(resourceDir)) {
            try {
                Files.createDirectories(resourceDir);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return resourceDir;
    }
}
