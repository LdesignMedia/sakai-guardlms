package com.guardlms.sakai;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.nio.ByteBuffer;

/** HTTPS only, normal JVM trust validation, no credential-bearing redirects. */
final class GuardLmsClient {
    private static final Gson JSON = new Gson();
    private final URI base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    GuardLmsClient(String baseUrl) { base = httpsUrl(baseUrl); }

    static URI httpsUrl(String value) {
        URI uri = URI.create(value.replaceAll("/+$", ""));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
            || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.normalize().equals(uri)) {
            throw new IllegalArgumentException("A canonical HTTPS URL without credentials, query or fragment is required");
        }
        return uri;
    }

    JsonObject post(String path, Object payload, String token) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(base.toString() + path)).timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("User-Agent", "GuardLMS-Sakai/0.1.0")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(payload)));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<byte[]> response;
        var pending = client.sendAsync(builder.build(), info -> new LimitedBody());
        try { response = pending.get(30, TimeUnit.SECONDS); }
        catch (TimeoutException e) { pending.cancel(true); throw new IOException("GuardLMS request timed out"); }
        catch (InterruptedException e) { pending.cancel(true); throw e; }
        catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("GuardLMS connection failed");
        }
        byte[] body = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GuardLMS HTTP " + response.statusCode());
        }
        try { return JsonParser.parseString(new String(body, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("GuardLMS returned invalid JSON"); }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int bytes;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                bytes += buffer.remaining();
                if (bytes > 262144) {
                    subscription.cancel(); delegate.onError(new IOException("GuardLMS response exceeded limit")); return;
                }
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }

}
