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
 * Compiles Arduino sketches using arduino-cli.
 *
 * Bound to the COMPILE phase so it participates naturally in "mvn compile".
 * For each sketch directory found under {@code sketchDir}, runs:
 *
 *   arduino-cli compile --fqbn {fqbn} --output-dir {outputDir}/{sketchName} {sketchPath}
 *
 * The build fails fast on the first compilation error — there's no value in
 * continuing to compile subsequent sketches when one is broken, and the error
 * output from arduino-cli is usually enough to diagnose the problem.
 *
 * Sketch discovery: a "sketch" is a directory containing a .ino file whose
 * name matches the directory name. This matches Arduino's own convention.
 * If sketchDir itself is a single sketch, it compiles just that one.
 */
@Mojo(name = "compile", defaultPhase = LifecyclePhase.COMPILE)
public class CompileMojo extends AbstractMojo {

    @Parameter(property = "arduino.cli.path", required = true)
    private String arduinoCliPath;

    /**
     * Fully Qualified Board Name — identifies the target board.
     * Examples: "arduino:avr:uno", "arduino:avr:mega:cpu=atmega2560",
     *           "esp32:esp32:esp32"
     */
    @Parameter(property = "arduino.fqbn", defaultValue = "arduino:avr:uno")
    private String fqbn;

    /**
     * Root directory containing Arduino sketch(es). Can be either:
     * - A single sketch directory (contains a .ino file matching the dir name)
     * - A parent directory containing multiple sketch subdirectories
     */
    @Parameter(property = "arduino.sketch.dir",
               defaultValue = "${project.basedir}/src/arduino")
    private String sketchDir;

    /**
     * Directory where compiled artifacts (.hex, .elf, etc.) are written.
     * Each sketch gets its own subdirectory under this path.
     */
    @Parameter(property = "arduino.output.dir",
               defaultValue = "${project.build.directory}/arduino")
    private String outputDir;

    /**
     * Root directory containing local Arduino libraries. When set, each
     * library directory found here is passed as {@code --library <path>}
     * to arduino-cli compile, allowing sketches to #include local libs.
     */
    @Parameter(property = "arduino.library.dir",
               defaultValue = "${project.basedir}/src/arduino/library")
    private String libraryDir;

    /**
     * Extra flags to pass to arduino-cli compile. Useful for build properties,
     * verbose output, etc. Example: "--build-property build.extra_flags=-DDEBUG"
     */
    @Parameter
    private List<String> compileFlags;

    @Override
    public void execute() throws MojoExecutionException {
        Path cliPath    = Paths.get(arduinoCliPath);
        Path sketchRoot = Paths.get(sketchDir);
        Path outputRoot = Paths.get(outputDir);

        if (!Files.isDirectory(sketchRoot)) {
            throw new MojoExecutionException(
                    "Sketch directory does not exist: " + sketchRoot
                            + "\nCreate it and place your .ino sketches there.");
        }

        CliExecutor cli = new CliExecutor(cliPath, getLog());

        List<Path> sketches = discoverSketches(sketchRoot);
        if (sketches.isEmpty()) {
            throw new MojoExecutionException(
                    "No Arduino sketches found under " + sketchRoot
                            + "\nA sketch is a directory containing a .ino file "
                            + "with the same name as the directory.");
        }

        getLog().info("Found " + sketches.size() + " sketch(es) to compile");

        for (Path sketch : sketches) {
            compileSketch(cli, sketch, outputRoot);
        }

        getLog().info("All sketches compiled successfully. "
                + "Artifacts written to " + outputRoot);
    }

    /**
     * Finds sketch directories under the given root.
     *
     * Arduino convention: a sketch directory "Blink" must contain "Blink.ino".
     * We look one level deep (the root itself, then its immediate children).
     */
    private List<Path> discoverSketches(Path root) throws MojoExecutionException {
        List<Path> sketches = new ArrayList<>();

        // Check if root itself is a sketch
        if (isSketchDir(root)) {
            sketches.add(root);
            return sketches;
        }

        // Otherwise, scan immediate subdirectories
        try (Stream<Path> children = Files.list(root)) {
            sketches = children
                    .filter(Files::isDirectory)
                    .filter(this::isSketchDir)
                    .sorted()  // deterministic build order
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to scan sketch directory: " + root, e);
        }

        return sketches;
    }

    /** A directory is a sketch if it contains a .ino file matching its name. */
    private boolean isSketchDir(Path dir) {
        String dirName = dir.getFileName().toString();
        return Files.isRegularFile(dir.resolve(dirName + ".ino"));
    }

    /**
     * Scans libraryDir for local Arduino libraries and appends a
     * {@code --library <path>} argument for each one. This lets sketches
     * #include headers from libraries developed in the same project.
     */
    private void appendLocalLibraryFlags(List<String> args) {
        if (libraryDir == null) return;
        Path libRoot = Paths.get(libraryDir);
        if (!Files.isDirectory(libRoot)) return;

        try (Stream<Path> children = Files.list(libRoot)) {
            children.filter(Files::isDirectory)
                    .filter(this::isLibraryDir)
                    .sorted()
                    .forEach(lib -> {
                        args.add("--library");
                        args.add(lib.toAbsolutePath().toString());
                    });
        } catch (IOException e) {
            getLog().warn("Could not scan library directory " + libRoot
                    + " — local libraries will not be available: " + e.getMessage());
        }
    }

    private boolean isLibraryDir(Path dir) {
        if (Files.isRegularFile(dir.resolve("library.properties"))) {
            return true;
        }
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

    private void compileSketch(CliExecutor cli, Path sketch, Path outputRoot)
            throws MojoExecutionException {

        String sketchName = sketch.getFileName().toString();
        Path sketchOutput = outputRoot.resolve(sketchName);

        try {
            Files.createDirectories(sketchOutput);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to create output directory: " + sketchOutput, e);
        }

        getLog().info("Compiling sketch: " + sketchName + " for board " + fqbn);

        List<String> args = new ArrayList<>();
        args.add("compile");
        args.add("--fqbn");
        args.add(fqbn);
        args.add("--output-dir");
        args.add(sketchOutput.toAbsolutePath().toString());

        // Include local libraries so sketches can #include them
        appendLocalLibraryFlags(args);

        // Append any user-supplied extra flags
        if (compileFlags != null) {
            args.addAll(compileFlags);
        }

        args.add(sketch.toAbsolutePath().toString());

        cli.execute(sketch.getParent(), args.toArray(new String[0]));
    }
}
