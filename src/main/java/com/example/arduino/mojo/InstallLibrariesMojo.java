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
import java.util.List;

/**
 * Installs Arduino libraries using arduino-cli.
 *
 * Runs:
 *   arduino-cli lib install {library}   (for each configured library)
 *
 * Like core install, "lib install" is idempotent — already-installed libraries
 * are skipped with a message. We don't parse the output to check; we rely on
 * arduino-cli's built-in behaviour and its exit code.
 */
@Mojo(name = "install-libraries", defaultPhase = LifecyclePhase.INITIALIZE)
public class InstallLibrariesMojo extends AbstractMojo {

    @Parameter(property = "arduino.cli.path", required = true)
    private String arduinoCliPath;

    /**
     * Libraries to install. Names must match the Arduino Library Manager
     * index exactly — e.g. "Servo", "Adafruit NeoPixel", "ArduinoJson".
     *
     * Version pinning: append @version, e.g. "ArduinoJson@6.21.3".
     */
    @Parameter
    private List<String> libraries;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        if (libraries == null || libraries.isEmpty()) {
            getLog().info("No libraries configured — skipping.");
            return;
        }

        Path cliPath = Paths.get(arduinoCliPath);
        CliExecutor cli = new CliExecutor(cliPath, getLog());
        Path workDir =project.getBasedir().toPath();

        // Update the library index so installs resolve correctly
        getLog().info("Updating library index...");
        cli.execute(workDir, "lib", "update-index");

        for (String library : libraries) {
            getLog().info("Installing library: " + library);
            cli.execute(workDir, "lib", "install", library);
        }
    }
}
