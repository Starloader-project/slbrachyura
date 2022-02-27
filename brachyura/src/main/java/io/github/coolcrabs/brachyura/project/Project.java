package io.github.coolcrabs.brachyura.project;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.github.coolcrabs.brachyura.project.java.BaseJavaProject;
import io.github.coolcrabs.brachyura.util.PathUtil;

public class Project {
    BaseJavaProject buildscriptIdeProject;

    public void getTasks(Consumer<Task> p) {
        // no default tasks
    }

    public final void runTask(String name, String... args) {
        Tasks t = new Tasks();
        getTasks(t);
        t.get(name).doTask(args);
    }

    @SuppressWarnings("null") // There are circumstances that this is null, but we are going to ignore these
    @NotNull
    public Path getProjectDir() {
        @NotNull
        ByteBuffer buf = ByteBuffer.allocate(10);
        buf.array();
        return EntryGlobals.projectDir;
    }

    @NotNull
    public Path getLocalBrachyuraPath() {
        return PathUtil.resolveAndCreateDir(getProjectDir(), ".brachyura");
    }

    @Nullable
    public BaseJavaProject getBuildscriptProject() {
        return buildscriptIdeProject;
    }

    void setIdeProject(BaseJavaProject ideProject) {
        this.buildscriptIdeProject = ideProject;
    }
}
