package com.example.arduino.mojo;

import com.example.arduino.util.CliExecutor;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/**
 * Updates the board index and installs Arduino cores.
 *
 * Runs:
 *   arduino-cli core update-index
 *   arduino-cli core install {core}   (for each configured core)
 *
 * arduino-cli's "core install" is already idempotent — re-installing an
 * already-present core is a fast no-op. We still log what we're doing so
 * the build output is understandable.
 */
@Mojo(name = "install-cores", defaultPhase = LifecyclePhase.INITIALIZE)
public class InstallCoresMojo extends AbstractMojo {

    /**
     * Path to the arduino-cli binary. Normally set automatically by the
     * install-cli goal via a project property, but can be overridden to
     * point at a system-installed binary.
     */
    @Parameter(defaultValue = "${arduino.cli.path}", property = "arduino.cli.path", required = true)
    private String arduinoCliPath;

    /**
     * Board cores to install. Each entry is a core identifier like
     * "arduino:avr" or "esp32:esp32".
     */
    @Parameter
    private List<String> cores;

   @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        if (cores == null || cores.isEmpty()) {
            cores = Collections.singletonList("arduino:avr");
            getLog().info("No cores configured — defaulting to arduino:avr");
        }
        Path cliPath = Paths.get(arduinoCliPath);
        CliExecutor cli = new CliExecutor(cliPath, getLog());
        Path workDir = project.getBasedir().toPath();

        // Update the package index first so core install can resolve dependencies
        getLog().info("Updating board package index...");
        cli.execute(workDir, "core", "update-index");

        for (String core : cores) {
            getLog().info("Installing core: " + core);
            cli.execute(workDir, "core", "install", core);
        }
    }
}
