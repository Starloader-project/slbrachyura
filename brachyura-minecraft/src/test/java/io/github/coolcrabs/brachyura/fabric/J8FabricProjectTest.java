package io.github.coolcrabs.brachyura.fabric;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import net.fabricmc.mappingio.tree.MappingTree;

import io.github.coolcrabs.brachyura.TestUtil;
import io.github.coolcrabs.brachyura.decompiler.BrachyuraDecompiler;
import io.github.coolcrabs.brachyura.dependency.JavaJarDependency;
import io.github.coolcrabs.brachyura.fabric.FabricContext.ModDependencyCollector;
import io.github.coolcrabs.brachyura.fabric.FabricContext.ModDependencyFlag;
import io.github.coolcrabs.brachyura.maven.MavenId;
import io.github.coolcrabs.brachyura.maven.MavenResolver;
import io.github.coolcrabs.brachyura.minecraft.Minecraft;
import io.github.coolcrabs.brachyura.minecraft.VersionMeta;
import io.github.coolcrabs.brachyura.util.JvmUtil;

class J8FabricProjectTest {
    SimpleFabricProject fabricProject = new SimpleFabricProject() {
        @Override
        public VersionMeta createMcVersion() {
            return Minecraft.getVersion("1.16.5");
        }

        @Override
        public MappingTree createMappings() {
            MavenResolver resolver = new MavenResolver(MavenResolver.MAVEN_LOCAL);
            resolver.addRepository(FabricMaven.REPOSITORY);
            MappingTree tree = Yarn.ofMaven(resolver, FabricMaven.yarn("1.16.5+build.10")).tree;
            return tree;
        }

        @Override
        public FabricLoader getLoader() {
            return new FabricLoader(FabricMaven.URL, FabricMaven.loader("0.12.5"));
        }

        @Override
        @NotNull
        public Path getProjectDir() {
            Path result = TestUtil.ROOT.resolve("testmod");
            assertTrue(Files.isDirectory(result)); 
            return result;
        }

        @Override
        public void getModDependencies(ModDependencyCollector d) {
            MavenResolver resolver = new MavenResolver(MavenResolver.MAVEN_LOCAL);
            resolver.addRepository(FabricMaven.REPOSITORY);
            resolver.addRepository(MavenResolver.MAVEN_CENTRAL_REPO);
            d.add(resolver.getJarDepend(new MavenId("org.ini4j", "ini4j", "0.5.4")), ModDependencyFlag.RUNTIME, ModDependencyFlag.COMPILE);
            d.add(resolver.getJarDepend(new MavenId(FabricMaven.GROUP_ID + ".fabric-api", "fabric-resource-loader-v0", "0.4.8+3cc0f0907d")), ModDependencyFlag.RUNTIME, ModDependencyFlag.COMPILE);
            d.add(resolver.getJarDepend(new MavenId(FabricMaven.GROUP_ID + ".fabric-api", "fabric-game-rule-api-v1", "1.0.7+3cc0f0907d")), ModDependencyFlag.RUNTIME, ModDependencyFlag.COMPILE);
        };

       @Override
       public BrachyuraDecompiler decompiler() {
           return null;
       };
    };

    @Test
    void compile() {
        try {
            JavaJarDependency b = fabricProject.build();
            if (Boolean.TRUE == true) return;
            if (JvmUtil.CURRENT_JAVA_VERSION == 8) { // TestMod.java produces different cp order in j8 and j17
                TestUtil.assertSha256(b.jar, "e0dbaa897a5f86f77e3fec2a7fd43dbf4df830a10b9c75dd0ae23f28e5da3c67");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    @Test
    void ide() {
        try {
            fabricProject.runTask("netbeans");
            fabricProject.runTask("idea");
            fabricProject.runTask("jdt");
        } catch (Exception e) {
            e.printStackTrace(); // Slbrachyura: print stacktraces, properly
            throw e;
        }
    }

    @Disabled
    @Test
    void bruh() {
        fabricProject.runTask("runMinecraftClient");
    }
}
