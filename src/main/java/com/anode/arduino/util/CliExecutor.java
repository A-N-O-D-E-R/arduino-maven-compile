package com.anode.arduino.util;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Runs arduino-cli commands as child processes, streaming their stdout/stderr
 * into Maven's log at the appropriate level (INFO for stdout, WARN for stderr).
 *
 * Design decisions:
 * - We merge stdout/stderr into a single stream via ProcessBuilder.redirectErrorStream
 *   because arduino-cli mixes informational and error output across both, and Maven
 *   users expect a linear log.
 * - The working directory is always explicitly set — we never inherit the JVM's cwd,
 *   which avoids surprises in IDE vs. CLI vs. CI execution.
 * - We pass --no-color to suppress ANSI escape codes that would clutter Maven output.
 */
public final class CliExecutor {

    private final Path cliBinary;
    private final Log log;

    public CliExecutor(Path cliBinary, Log log) {
        this.cliBinary = cliBinary;
        this.log = log;
    }

    /**
     * Executes an arduino-cli command and returns the combined output.
     *
     * @param workingDir directory to run the command in
     * @param args       arguments after "arduino-cli", e.g. "core", "install", "arduino:avr"
     * @return the full stdout+stderr output as a single string
     * @throws MojoExecutionException if the process exits non-zero or cannot start
     */
    public String execute(Path workingDir, String... args) throws MojoExecutionException {
        return execute(workingDir, null, args);
    }

    /**
     * Executes an arduino-cli command with optional extra environment variables.
     *
     * @param workingDir directory to run the command in
     * @param extraEnv   additional environment variables (may be null)
     * @param args       arguments after "arduino-cli"
     * @return the full stdout+stderr output
     * @throws MojoExecutionException on non-zero exit or I/O failure
     */
    public String execute(Path workingDir, Map<String, String> extraEnv, String... args)
            throws MojoExecutionException {

        List<String> command = new ArrayList<>();
        command.add(cliBinary.toAbsolutePath().toString());
        command.add("--no-color");
        command.addAll(Arrays.asList(args));

        log.info("Running: " + String.join(" ", command));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);

            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            // Stream output line-by-line so the user sees progress in real time,
            // rather than a wall of text after the process finishes.
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[arduino-cli] " + line);
                    output.append(line).append(System.lineSeparator());
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new MojoExecutionException(
                        "arduino-cli exited with code " + exitCode
                                + ". Command: " + String.join(" ", command)
                                + "\nOutput:\n" + output);
            }

            return output.toString();

        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to execute arduino-cli. Is the binary at "
                            + cliBinary + " valid?\nCommand: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("arduino-cli execution interrupted", e);
        }
    }
}
