package io.github.coolcrabs.brachyura.decompiler.fernflower;

import java.nio.file.Path;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.mappingio.tree.MappingTree;

import io.github.coolcrabs.brachyura.decompiler.BrachyuraDecompiler;
import io.github.coolcrabs.brachyura.decompiler.DecompileLineNumberTable;
import io.github.coolcrabs.brachyura.decompiler.DecompileLineNumberTable.ClassLineMap;
import io.github.coolcrabs.brachyura.decompiler.LineNumberTableReplacer;
import io.github.coolcrabs.brachyura.dependency.JavaJarDependency;
import io.github.coolcrabs.brachyura.maven.MavenId;
import io.github.coolcrabs.fernutil.FernUtil;

public class FernflowerDecompiler extends BrachyuraDecompiler {
    JavaJarDependency ff;

    public FernflowerDecompiler(JavaJarDependency ff) {
        this.ff = ff;
    }

    @Override
    public String getName() {
        MavenId mavenid = ff.mavenId;
        return mavenid.artifactId + " (" + mavenid.groupId + ")";
    }

    @Override
    public String getVersion() {
        return ff.mavenId.version;
    }

    @Override
    public int getThreadCount() {
        return -1;
    }

    @Override
    protected void decompileAndLinemap(@NotNull Path jar, List<Path> classpath, Path resultDir, @Nullable MappingTree tree, int namespace) {
        DecompileResult r = getDecompileResult(jar, resultDir);
        DecompileLineNumberTable table = new DecompileLineNumberTable();
        FernUtil.decompile(ff.jar, jar, r.sourcesJar, classpath, l -> {
            if (l.mapping != null) table.classes.put(l.clazz, new ClassLineMap(l.mapping));
        }, tree == null ? null : new FFJavadocProvider(tree, namespace));
        LineNumberTableReplacer.replaceLineNumbers(jar, r.jar, table);
    }
    
}
