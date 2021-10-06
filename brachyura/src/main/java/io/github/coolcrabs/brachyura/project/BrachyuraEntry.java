package io.github.coolcrabs.brachyura.project;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.tinylog.Logger;

import io.github.coolcrabs.brachyura.util.Util;

public class BrachyuraEntry {
    private BrachyuraEntry() { }

    static List<Path> classpath;

    // Called via reflection by bootstrap
    public static void main(String[] args, Path projectDir, List<Path> classpath) {
        try {
            EntryGlobals.projectDir = projectDir;
            BrachyuraEntry.classpath = classpath;
            BuildscriptProject buildscriptProject = new BuildscriptProject();
            if (args.length >= 1 && "buildscript".equals(args[0])) {
                Tasks t = new Tasks();
                buildscriptProject.getTasks(t);
                if (args.length >= 2) {
                    Task task = t.get(args[1]);
                    task.doTask(args.length > 3 ? Arrays.copyOfRange(args, 2, args.length) : new String[]{});
                } else {
                    Logger.info("Avalible buildscript tasks: " + t.toString());
                }
            } else {
                Optional<Project> o = buildscriptProject.project.get();
                if (o.isPresent()) {
                    Project project = o.get();
                    Tasks t = new Tasks();
                    project.getTasks(t);
                    if (args.length >= 1) {
                        Task task = t.get(args[0]);
                        task.doTask(args.length > 2 ? Arrays.copyOfRange(args, 1, args.length) : new String[]{});
                    } else {
                        Logger.info("Avalible tasks: " + t.toString());
                    }
                } else {
                    Logger.warn("Invalid build script :(");
                }
            }
        } catch (Exception e) {
            throw Util.sneak(e);
        }
    }
}
