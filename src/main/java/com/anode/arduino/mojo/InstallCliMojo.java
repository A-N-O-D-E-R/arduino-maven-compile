package com.anode.arduino.mojo;

import com.anode.arduino.util.CliDownloader;
import com.anode.arduino.util.Platform;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Path;

/**
 * Downloads and caches the arduino-cli binary for the current platform.
 *
 * Bound to INITIALIZE so it runs before any other arduino goals. The resolved
 * binary path is stored as a Maven project property ("arduino.cli.path") so
 * downstream Mojos can pick it up without re-detecting the platform.
 *
 * Idempotent: skips the download when the cached binary already exists.
 */
@Mojo(name = "install-cli", defaultPhase = LifecyclePhase.INITIALIZE)
public class InstallCliMojo extends AbstractMojo {

    /**
     * arduino-cli version to download. Pinned rather than "latest" so builds
     * are reproducible — a core requirement for CI.
     */
    @Parameter(property = "arduino.cli.version", defaultValue = "0.35.2")
    private String arduinoCliVersion;

    /**
     * The Maven project — injected so we can set properties for later Mojos.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private org.apache.maven.project.MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        Platform platform = Platform.detect();
        getLog().info("Detected platform: " + platform);

        CliDownloader downloader = new CliDownloader(getLog());
        Path cliBinary = downloader.ensureInstalled(arduinoCliVersion, platform);

        // Expose the resolved path to other Mojos via a project property.
        // This avoids each Mojo independently re-detecting the platform.
        project.getProperties().setProperty("arduino.cli.path",
                cliBinary.toAbsolutePath().toString());

        getLog().info("arduino-cli path: " + cliBinary.toAbsolutePath());
    }
}
