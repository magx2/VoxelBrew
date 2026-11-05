package dev.voxelbrew.util.update;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Anonymous HTTPS release transport. No external executable, credentials or startup requests. */
public final class GitHubReleaseSource implements ReleaseSource {
    public static final String REPOSITORY = "magx2/VoxelBrew";
    private static final String FIXTURE_PROPERTY = "voxelbrew.update.fixtureUrl";
    private static final long MAX_METADATA_BYTES = 1024 * 1024;
    private final URI releases, downloads;
    private final Duration metadataTimeout, downloadTimeout;

    public GitHubReleaseSource() {
        this(System.getProperty(FIXTURE_PROPERTY));
    }

    private GitHubReleaseSource(String fixture) {
        this(
                fixture == null ? null : URI.create(fixture),
                Duration.ofSeconds(45),
                Duration.ofMinutes(10));
    }

    /** Loopback-only transport fixture for independent packaged and GUI acceptance tests. */
    public GitHubReleaseSource(URI fixtureRoot) {
        this(Objects.requireNonNull(fixtureRoot), Duration.ofSeconds(45), Duration.ofMinutes(10));
    }

    GitHubReleaseSource(URI fixtureRoot, Duration metadataTimeout, Duration downloadTimeout) {
        if (fixtureRoot == null) {
            releases = URI.create("https://api.github.com/repos/" + REPOSITORY + "/releases/");
            downloads = URI.create("https://github.com/" + REPOSITORY + "/releases/download/");
        } else {
            if (!"http".equals(fixtureRoot.getScheme())
                    || !"127.0.0.1".equals(fixtureRoot.getHost())
                    || fixtureRoot.getPort() <= 0
                    || fixtureRoot.getPort() > 65535
                    || !"/".equals(fixtureRoot.getPath())
                    || fixtureRoot.getUserInfo() != null
                    || fixtureRoot.getQuery() != null
                    || fixtureRoot.getFragment() != null)
                throw new IllegalArgumentException("Update fixture must be http://127.0.0.1:PORT/");
            releases = fixtureRoot.resolve("repos/" + REPOSITORY + "/releases/");
            downloads = fixtureRoot.resolve(REPOSITORY + "/releases/download/");
        }
        this.metadataTimeout = metadataTimeout;
        this.downloadTimeout = downloadTimeout;
    }

    @Override
    public Release fetch(ReleaseVersion requested, UpdateCancellation cancellation)
            throws IOException {
        var uri = releases.resolve(requested == null ? "latest" : "tags/v" + requested);
        byte[] data =
                request(
                        uri,
                        "application/vnd.github+json",
                        metadataTimeout,
                        MAX_METADATA_BYTES,
                        HttpResponse.BodySubscribers::ofByteArray,
                        cancellation);
        return parse(new String(data, StandardCharsets.UTF_8), requested);
    }

    static Release parse(String output, ReleaseVersion requested) throws IOException {
        try (var reader = new JsonReader(new StringReader(output))) {
            reader.setStrictness(Strictness.STRICT);
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new IOException("Invalid GitHub release metadata");
            if (booleanField(root, "draft") || booleanField(root, "prerelease"))
                throw new IOException("Only published stable releases can be installed");
            String tag = stringField(root, "tag_name");
            var version = ReleaseVersion.release(tag);
            if (!tag.equals("v" + version) || requested != null && !version.equals(requested))
                throw new IOException("GitHub returned a different release tag");
            var assets = new HashSet<String>();
            for (var entry : root.getAsJsonArray("assets")) {
                String asset = stringField(entry.getAsJsonObject(), "name");
                if (!asset.matches("[A-Za-z0-9._-]+") || !assets.add(asset))
                    throw new IOException("Invalid or duplicate release asset");
            }
            return new Release(version, assets);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid GitHub release metadata", failure);
        }
    }

    private static String stringField(JsonObject object, String name) throws IOException {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("Invalid GitHub release field: " + name);
        return value.getAsString();
    }

    private static boolean booleanField(JsonObject object, String name) throws IOException {
        var value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IOException("Invalid GitHub release field: " + name);
        return value.getAsBoolean();
    }

    @Override
    public void download(
            Release release, String asset, Path destination, UpdateCancellation cancellation)
            throws IOException {
        if (!release.assets().contains(asset) || !asset.matches("[A-Za-z0-9._-]+"))
            throw new IOException("Unknown release asset");
        cancellation.check();
        // Claim the path atomically. Failure/cancellation removes only the file we created.
        Files.createFile(destination);
        boolean complete = false;
        try {
            request(
                    downloads.resolve(release.tag() + "/" + asset),
                    "application/octet-stream",
                    downloadTimeout,
                    asset.equals("SHA256SUMS")
                            ? MAX_METADATA_BYTES
                            : UpdateArchive.MAX_ARCHIVE_BYTES,
                    () ->
                            HttpResponse.BodySubscribers.ofFile(
                                    destination, StandardOpenOption.WRITE),
                    cancellation);
            if (Files.size(destination) == 0) throw new IOException("Empty download: " + asset);
            cancellation.check();
            complete = true;
        } finally {
            if (!complete) Files.deleteIfExists(destination);
        }
    }

    private <T> T request(
            URI uri,
            String accept,
            Duration timeout,
            long limit,
            Supplier<HttpResponse.BodySubscriber<T>> body,
            UpdateCancellation cancellation)
            throws IOException {
        cancellation.check();
        // NORMAL follows GitHub's signed CDN redirect, but refuses HTTPS -> HTTP downgrades.
        // No Authenticator, CookieHandler, Authorization header, token or gh configuration is used.
        try (var client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()) {
            var request =
                    HttpRequest.newBuilder(uri)
                            .timeout(timeout)
                            .header("User-Agent", "VoxelBrew/" + BuildVersion.current())
                            .header("Accept", accept)
                            .header("X-GitHub-Api-Version", "2026-03-10")
                            .GET()
                            .build();
            var pending =
                    client.sendAsync(
                            request,
                            info -> {
                                IOException failure = responseFailure(info, limit);
                                return failure == null
                                        ? new LimitedBody<>(body.get(), limit)
                                        : new RejectedBody<T>(failure);
                            });
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                while (true) {
                    cancellation.check();
                    if (System.nanoTime() >= deadline)
                        throw new IOException("GitHub update request timed out; try again");
                    try {
                        var response = pending.get(100, TimeUnit.MILLISECONDS);
                        cancellation.check();
                        return response.body();
                    } catch (TimeoutException waiting) {
                        // Poll cancellation even during connection, headers or a stalled body.
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Update interrupted");
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof UncheckedIOException io) throw io.getCause();
                if (cause instanceof IOException io) throw io;
                throw new IOException(
                        "Cannot download the public GitHub release; try again", cause);
            } finally {
                pending.cancel(true);
                client.shutdownNow();
            }
        }
    }

    private static IOException responseFailure(HttpResponse.ResponseInfo info, long limit) {
        int status = info.statusCode();
        if (status == 404)
            return new IOException(
                    "Published VoxelBrew release or asset not found (HTTP 404). Check the selected version or try again after a release is published.");
        if (status == 429
                || status == 403
                        && (info.headers()
                                        .firstValue("X-RateLimit-Remaining")
                                        .orElse("")
                                        .equals("0")
                                || info.headers().firstValue("Retry-After").isPresent())) {
            String retry =
                    info.headers()
                            .firstValue("Retry-After")
                            .map(s -> " Retry-After: " + s + ".")
                            .orElse("");
            return new IOException(
                    "GitHub's public download rate limit was reached (HTTP "
                            + status
                            + "). Try again later."
                            + retry);
        }
        if (status != 200)
            return new IOException(
                    "Public GitHub update request failed (HTTP " + status + "); try again later.");
        try {
            if (info.headers().firstValueAsLong("Content-Length").orElse(0) > limit)
                return new IOException("GitHub response exceeds size limit");
        } catch (NumberFormatException invalid) {
            return new IOException("Invalid GitHub response length", invalid);
        }
        return null;
    }

    private record RejectedBody<T>(IOException failure) implements HttpResponse.BodySubscriber<T> {
        @Override
        public CompletionStage<T> getBody() {
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {}

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }

    private static final class LimitedBody<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> delegate;
        private final long limit;
        private Flow.Subscription subscription;
        private long received;
        private boolean failed;

        LimitedBody(HttpResponse.BodySubscriber<T> delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (var buffer : buffers) received += buffer.remaining();
            if (received > limit) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("GitHub response exceeds size limit"));
            } else delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable throwable) {
            if (!failed) delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (!failed) delegate.onComplete();
        }
    }
}
