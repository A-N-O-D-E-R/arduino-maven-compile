package com.example.arduino.util;

import org.apache.maven.plugin.MojoExecutionException;

/**
 * Detects OS and CPU architecture, then maps them to the naming conventions
 * used by the arduino-cli GitHub release assets.
 *
 * Release asset names follow the pattern:
 *   arduino-cli_{version}_{os}_{arch}.tar.gz   (Linux/macOS)
 *   arduino-cli_{version}_Windows_64bit.zip     (Windows)
 *
 * We intentionally keep this as a simple value object — no caching, no
 * singletons — so callers can construct it freely without side effects.
 */
public final class Platform {

    private final String os;
    private final String arch;
    private final String archiveExtension;

    private Platform(String os, String arch, String archiveExtension) {
        this.os = os;
        this.arch = arch;
        this.archiveExtension = archiveExtension;
    }

    /** Sniff the current JVM's host platform. */
    public static Platform detect() throws MojoExecutionException {
        String osName  = System.getProperty("os.name", "").toLowerCase();
        String osArch  = System.getProperty("os.arch", "").toLowerCase();

        String os;
        String archiveExt;

        if (osName.contains("linux")) {
            os = "Linux";
            archiveExt = "tar.gz";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            os = "macOS";
            archiveExt = "tar.gz";
        } else if (osName.contains("win")) {
            os = "Windows";
            archiveExt = "zip";
        } else {
            throw new MojoExecutionException(
                    "Unsupported operating system: " + osName);
        }

        String arch = mapArch(osArch);
        return new Platform(os, arch, archiveExt);
    }

    /**
     * Maps JVM os.arch values to arduino-cli's naming convention.
     * The JVM uses names like "amd64" and "aarch64", while arduino-cli
     * uses "64bit", "ARM64", "ARMv7", etc.
     */
    private static String mapArch(String jvmArch) throws MojoExecutionException {
        if (jvmArch.equals("amd64") || jvmArch.equals("x86_64")) {
            return "64bit";
        }
        if (jvmArch.equals("aarch64")) {
            return "ARM64";
        }
        if (jvmArch.startsWith("arm")) {
            // Covers armv7l, arm — broad but correct for 32-bit ARM Linux boards
            return "ARMv7";
        }
        if (jvmArch.equals("x86") || jvmArch.equals("i386") || jvmArch.equals("i686")) {
            return "32bit";
        }
        throw new MojoExecutionException(
                "Unsupported CPU architecture: " + jvmArch);
    }

    /** e.g. "Linux", "macOS", "Windows" */
    public String getOs() {
        return os;
    }

    /** e.g. "64bit", "ARM64" */
    public String getArch() {
        return arch;
    }

    /** "tar.gz" or "zip" */
    public String getArchiveExtension() {
        return archiveExtension;
    }

    /** Name of the CLI binary on this platform. */
    public String getExecutableName() {
        return "Windows".equals(os) ? "arduino-cli.exe" : "arduino-cli";
    }

    /**
     * Builds the full download URL for a given arduino-cli version.
     * Example: https://github.com/arduino/arduino-cli/releases/download/
     *          v0.35.2/arduino-cli_0.35.2_Linux_64bit.tar.gz
     */
    public String getDownloadUrl(String version) {
        return String.format(
                "https://github.com/arduino/arduino-cli/releases/download/v%s/arduino-cli_%s_%s_%s.%s",
                version, version, os, arch, archiveExtension);
    }

    @Override
    public String toString() {
        return os + "_" + arch;
    }
}
