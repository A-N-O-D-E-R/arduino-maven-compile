package com.anode.arduino.mojo;

import com.anode.arduino.util.CliExecutor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles local Arduino libraries by building their example sketches.
 *
 * Arduino libraries are not standalone compilation units — they are compiled
 * as part of a sketch that #includes them. The standard way to verify a
 * library compiles is to compile its example sketches. This goal automates
 * that pattern.
 *
 * For each library found under {@code libraryDir}, this goal:
 * <ol>
 *   <li>Locates example sketches in the library's {@code examples/} directory</li>
 *   <li>Compiles each example with {@code --library} pointing to the lib</li>
 *   <li>Fails the build on the first compilation error</li>
 * </ol>
 *
 * Library discovery: a directory is considered an Arduino library if it
 * contains a {@code library.properties} file (standard Arduino convention)
 * or a {@code src/} subdirectory with at least one header file.
 *
 * Expected layout:
 * <pre>
 *   src/arduino/library/
 *     MyLib/
 *       library.properties
 *       src/
 *         MyLib.h
 *         MyLib.cpp
 *       examples/
 *         BasicUsage/
 *           BasicUsage.ino
 * </pre>
 */
@Mojo(name = "compile-library", defaultPhase = LifecyclePhase.COMPILE)
public class CompileLibraryMojo extends AbstractMojo {

    @Parameter(property = "arduino.cli.path", required = true)
    private String arduinoCliPath;

    /** Fully Qualified Board Name used to compile library examples. */
    @Parameter(property = "arduino.fqbn", defaultValue = "arduino:avr:uno")
    private String fqbn;

    /**
     * Root directory containing local Arduino library directories.
     * Each immediate subdirectory that looks like an Arduino library
     * will be discovered and compiled.
     */
    @Parameter(property = "arduino.library.dir",
               defaultValue = "${project.basedir}/src/arduino/library")
    private String libraryDir;

    /**
     * Directory where compiled library example artifacts are written.
     * Each library/example pair gets its own subdirectory.
     */
    @Parameter(property = "arduino.output.dir",
               defaultValue = "${project.build.directory}/arduino")
    private String outputDir;

    @Parameter
    private List<String> compileFlags;

    @Override
    public void execute() throws MojoExecutionException {
        Path cliPath    = Paths.get(arduinoCliPath);
        Path libRoot    = Paths.get(libraryDir);
        Path outputRoot = Paths.get(outputDir).resolve("libraries");

        if (!Files.isDirectory(libRoot)) {
            getLog().info("Library directory does not exist: " + libRoot + " — skipping.");
            return;
        }

        CliExecutor cli = new CliExecutor(cliPath, getLog());

        List<Path> libs = discoverLibraries(libRoot);
        if (libs.isEmpty()) {
            getLog().warn("No Arduino libraries found under " + libRoot);
            return;
        }

        getLog().info("Found " + libs.size() + " local library(ies) to compile");

        int totalExamples = 0;
        for (Path lib : libs) {
            totalExamples += compileLibrary(cli, lib, libRoot, outputRoot);
        }

        getLog().info("All library examples compiled successfully ("
                + totalExamples + " example(s) across " + libs.size()
                + " library(ies)). Artifacts written to " + outputRoot);
    }

    /**
     * Discovers Arduino library directories under the given root.
     *
     * A directory is an Arduino library if it has:
     * - a library.properties file (the standard marker), OR
     * - a src/ subdirectory containing at least one .h file
     */
    private List<Path> discoverLibraries(Path root) throws MojoExecutionException {
        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(this::isLibraryDir)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to scan library directory: " + root, e);
        }
    }

    private boolean isLibraryDir(Path dir) {
        // Standard Arduino library has library.properties
        if (Files.isRegularFile(dir.resolve("library.properties"))) {
            return true;
        }
        // Fallback: src/ directory with at least one header
        Path srcDir = dir.resolve("src");
        if (Files.isDirectory(srcDir)) {
            try (Stream<Path> files = Files.list(srcDir)) {
                return files.anyMatch(f -> f.toString().endsWith(".h"));
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Compiles all example sketches for a single library.
     *
     * @return the number of example sketches compiled
     */
    private int compileLibrary(CliExecutor cli, Path lib, Path libRoot, Path outputRoot)
            throws MojoExecutionException {

        String libName = lib.getFileName().toString();
        getLog().info("Compiling library: " + libName);

        Path examplesDir = lib.resolve("examples");
        if (!Files.isDirectory(examplesDir)) {
            getLog().warn("Library " + libName + " has no examples/ directory — "
                    + "cannot verify compilation. Skipping.");
            return 0;
        }

        List<Path> examples = discoverExampleSketches(examplesDir);
        if (examples.isEmpty()) {
            getLog().warn("Library " + libName + " has an examples/ directory "
                    + "but no valid sketches inside it. Skipping.");
            return 0;
        }

        for (Path example : examples) {
            compileExample(cli, lib, libRoot, example, outputRoot.resolve(libName));
        }

        return examples.size();
    }

    /**
     * Finds sketch directories under examples/. Supports both flat and nested
     * layouts:
     *   examples/BasicUsage/BasicUsage.ino          (standard)
     *   examples/Category/AdvancedUsage/AdvancedUsage.ino  (nested)
     */
    private List<Path> discoverExampleSketches(Path examplesDir) throws MojoExecutionException {
        List<Path> sketches = new ArrayList<>();
        try (Stream<Path> children = Files.list(examplesDir)) {
            List<Path> dirs = children
                    .filter(Files::isDirectory)
                    .sorted()
                    .collect(Collectors.toList());

            for (Path dir : dirs) {
                if (isSketchDir(dir)) {
                    sketches.add(dir);
                } else {
                    // Check one level deeper for categorized examples
                    try (Stream<Path> nested = Files.list(dir)) {
                        nested.filter(Files::isDirectory)
                              .filter(this::isSketchDir)
                              .sorted()
                              .forEach(sketches::add);
                    }
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to scan examples directory: " + examplesDir, e);
        }
        return sketches;
    }

    private boolean isSketchDir(Path dir) {
        String dirName = dir.getFileName().toString();
        return Files.isRegularFile(dir.resolve(dirName + ".ino"));
    }

    private void compileExample(CliExecutor cli, Path lib, Path libRoot,
                                Path example, Path libOutputDir)
            throws MojoExecutionException {

        String libName     = lib.getFileName().toString();
        String exampleName = example.getFileName().toString();
        Path exampleOutput = libOutputDir.resolve(exampleName);

        try {
            Files.createDirectories(exampleOutput);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to create output directory: " + exampleOutput, e);
        }

        getLog().info("  Compiling example: " + libName + "/" + exampleName
                + " for board " + fqbn);

        List<String> args = new ArrayList<>();
        args.add("compile");
        args.add("--fqbn");
        args.add(fqbn);
        args.add("--output-dir");
        args.add(exampleOutput.toAbsolutePath().toString());

        // --library tells arduino-cli where to find the local library.
        // We pass each library under libraryDir so cross-references work too.
        try (Stream<Path> siblings = Files.list(libRoot)) {
            List<Path> allLibs = siblings
                    .filter(Files::isDirectory)
                    .filter(this::isLibraryDir)
                    .collect(Collectors.toList());
            for (Path sibling : allLibs) {
                args.add("--library");
                args.add(sibling.toAbsolutePath().toString());
            }
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to list libraries under " + libRoot, e);
        }

        if (compileFlags != null) {
            args.addAll(compileFlags);
        }

        args.add(example.toAbsolutePath().toString());

        cli.execute(example.getParent(), args.toArray(new String[0]));
    }
}
