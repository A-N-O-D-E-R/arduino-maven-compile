package com.example.arduino.util;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and caches the arduino-cli binary.
 *
 * Cache layout mirrors frontend-maven-plugin's approach: we store the binary
 * under the local Maven repository so it survives clean builds and is shared
 * across projects on the same machine.
 *
 *   ~/.m2/repository/com/example/arduino-cli/{version}/{platform}/arduino-cli
 *
 * The download is skipped entirely when the cached binary already exists,
 * making repeated builds fast and offline-capable after the first run.
 */
public final class CliDownloader {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 60_000;
    private static final int BUFFER_SIZE         = 8192;

    private final Log log;

    public CliDownloader(Log log) {
        this.log = log;
    }

    /**
     * Returns the path to a usable arduino-cli binary, downloading it first
     * if necessary.
     *
     * @param version  arduino-cli version, e.g. "0.35.2"
     * @param platform detected host platform
     * @return absolute path to the executable
     */
    public Path ensureInstalled(String version, Platform platform) throws MojoExecutionException {
        Path cacheDir   = getCacheDir(version, platform);
        Path executable = cacheDir.resolve(platform.getExecutableName());

        if (Files.isRegularFile(executable)) {
            log.info("arduino-cli " + version + " already cached at " + executable);
            return executable;
        }

        String url = platform.getDownloadUrl(version);
        log.info("Downloading arduino-cli " + version + " from " + url);

        Path archive = download(url, cacheDir, platform.getArchiveExtension());
        extract(archive, cacheDir, platform);

        if (!Files.isRegularFile(executable)) {
            throw new MojoExecutionException(
                    "Archive extracted but expected binary not found: " + executable);
        }

        makeExecutable(executable);
        cleanupArchive(archive);

        log.info("arduino-cli installed to " + executable);
        return executable;
    }

    /**
     * Cache directory lives under the local Maven repo so it persists across
     * clean builds. We key on version + platform to avoid collisions.
     */
    private Path getCacheDir(String version, Platform platform) throws MojoExecutionException {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            throw new MojoExecutionException("Cannot determine user home directory");
        }
        Path dir = Paths.get(userHome, ".m2", "repository", "com", "example",
                "arduino-cli", version, platform.toString());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to create cache directory: " + dir, e);
        }
        return dir;
    }

    /** Streams the URL content to a local file, following redirects. */
    private Path download(String urlStr, Path destDir, String extension) throws MojoExecutionException {
        Path archivePath = destDir.resolve("arduino-cli." + extension);
        try {
            HttpURLConnection conn = openConnection(urlStr);

            int status = conn.getResponseCode();
            // GitHub releases redirect to S3 — follow one hop
            if (status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == 307 || status == 308) {
                String redirect = conn.getHeaderField("Location");
                conn.disconnect();
                log.debug("Following redirect to " + redirect);
                conn = openConnection(redirect);
                status = conn.getResponseCode();
            }

            if (status != HttpURLConnection.HTTP_OK) {
                throw new MojoExecutionException(
                        "Download failed with HTTP " + status + ": " + urlStr);
            }

            long totalBytes = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(archivePath)) {
                byte[] buf = new byte[BUFFER_SIZE];
                long written = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    written += n;
                    if (totalBytes > 0 && log.isDebugEnabled()) {
                        log.debug(String.format("  %d / %d bytes (%.0f%%)",
                                written, totalBytes, 100.0 * written / totalBytes));
                    }
                }
            }
            conn.disconnect();
            return archivePath;

        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to download " + urlStr, e);
        }
    }

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        URI uri;
        try {
            uri = new URI(urlStr);
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid URL: " + urlStr, e);
        }
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    /** Dispatches to tar.gz or zip extraction based on platform. */
    private void extract(Path archive, Path destDir, Platform platform) throws MojoExecutionException {
        log.debug("Extracting " + archive);
        try {
            if (archive.toString().endsWith(".tar.gz")) {
                extractTarGz(archive, destDir, platform);
            } else {
                extractZip(archive, destDir);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to extract " + archive, e);
        }
    }

    /**
     * Extracts a .tar.gz archive.
     *
     * We use a minimal tar parser here rather than pulling in Apache Commons
     * Compress, keeping the dependency footprint at zero. arduino-cli archives
     * are simple (a single binary, no deep nesting) so this is sufficient.
     *
     * tar format: 512-byte headers, name at offset 0 (100 bytes),
     * size in octal at offset 124 (12 bytes). Two consecutive zero blocks
     * mark the end.
     */
    private void extractTarGz(Path archive, Path destDir, Platform platform) throws IOException {
        try (InputStream fileIn = Files.newInputStream(archive);
             GZIPInputStream gzIn = new GZIPInputStream(fileIn);
             BufferedInputStream tarIn = new BufferedInputStream(gzIn)) {

            byte[] header = new byte[512];
            while (true) {
                int bytesRead = readFully(tarIn, header);
                if (bytesRead < 512 || isZeroBlock(header)) {
                    break;
                }

                String entryName = extractString(header, 0, 100);
                long entrySize   = parseOctal(header, 124, 12);

                // Skip directories and entries we don't care about
                if (entryName.isEmpty() || entryName.endsWith("/") || entrySize == 0) {
                    skipBlocks(tarIn, entrySize);
                    continue;
                }

                // Only extract the binary itself (ignore LICENSE, etc.)
                String baseName = Paths.get(entryName).getFileName().toString();
                if (!baseName.equals(platform.getExecutableName())) {
                    skipBlocks(tarIn, entrySize);
                    continue;
                }

                Path outFile = destDir.resolve(baseName);
                try (OutputStream out = Files.newOutputStream(outFile)) {
                    long remaining = entrySize;
                    byte[] buf = new byte[BUFFER_SIZE];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int n = tarIn.read(buf, 0, toRead);
                        if (n == -1) break;
                        out.write(buf, 0, n);
                        remaining -= n;
                    }
                }

                // Tar entries are padded to 512-byte boundaries
                long padding = (512 - (entrySize % 512)) % 512;
                tarIn.skip(padding);
                // We found what we need — stop parsing
                break;
            }
        }
    }

    /** Reads exactly buf.length bytes, returns actual count. */
    private int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) return off;
            off += n;
        }
        return off;
    }

    private boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private String extractString(byte[] buf, int offset, int length) {
        int end = offset;
        while (end < offset + length && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset).trim();
    }

    private long parseOctal(byte[] buf, int offset, int length) {
        String s = extractString(buf, offset, length).trim();
        if (s.isEmpty()) return 0;
        return Long.parseLong(s, 8);
    }

    private void skipBlocks(InputStream in, long entrySize) throws IOException {
        long totalToSkip = entrySize + ((512 - (entrySize % 512)) % 512);
        long skipped = 0;
        while (skipped < totalToSkip) {
            long n = in.skip(totalToSkip - skipped);
            if (n <= 0) break;
            skipped += n;
        }
    }

    /** Extracts a .zip archive (Windows). */
    private void extractZip(Path archive, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String baseName = Paths.get(entry.getName()).getFileName().toString();
                // Only extract the binary
                if (!baseName.startsWith("arduino-cli")) continue;

                Path outFile = destDir.resolve(baseName);
                try (OutputStream out = Files.newOutputStream(outFile)) {
                    byte[] buf = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
                break;
            }
        }
    }

    private void makeExecutable(Path binary) throws MojoExecutionException {
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                boolean success = binary.toFile().setExecutable(true, false);
                if (!success) {
                    log.warn("Could not set executable permission on " + binary
                            + " — you may need to chmod +x manually");
                }
            }
        } catch (SecurityException e) {
            throw new MojoExecutionException("Failed to make binary executable: " + binary, e);
        }
    }

    private void cleanupArchive(Path archive) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException e) {
            log.debug("Could not remove archive file: " + archive);
        }
    }
}
