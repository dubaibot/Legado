package com.github.catvod.net;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.interceptor.AuthInterceptor;
import com.github.catvod.net.interceptor.RequestInterceptor;
import com.github.catvod.net.interceptor.ResponseInterceptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Interceptor;

public class OkHttp {

    public static final String POST = "POST";
    public static final String GET = "GET";
    public static final String VERSION = "okhttp/5.3.2";

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(30);

    private volatile ResponseInterceptor responseInterceptor;
    private volatile RequestInterceptor requestInterceptor;
    private volatile AuthInterceptor authInterceptor;
    private volatile OkAuthenticator authenticator;
    private volatile OkProxySelector selector;
    private volatile OkHttpClient client;
    private volatile OkHttpClient player;
    private volatile OkDns dns;

    public static OkHttp get() {
        return Loader.INSTANCE;
    }

    public static synchronized OkDns dns() {
        if (get().dns != null) return get().dns;
        return get().dns = new OkDns();
    }

    public static synchronized ResponseInterceptor responseInterceptor() {
        if (get().responseInterceptor != null) return get().responseInterceptor;
        return get().responseInterceptor = new ResponseInterceptor();
    }

    public static synchronized RequestInterceptor requestInterceptor() {
        if (get().requestInterceptor != null) return get().requestInterceptor;
        return get().requestInterceptor = new RequestInterceptor();
    }

    public static synchronized AuthInterceptor authInterceptor() {
        if (get().authInterceptor != null) return get().authInterceptor;
        return get().authInterceptor = new AuthInterceptor();
    }

    public static synchronized OkAuthenticator authenticator() {
        if (get().authenticator != null) return get().authenticator;
        return get().authenticator = new OkAuthenticator(selector());
    }

    public static synchronized OkProxySelector selector() {
        if (get().selector != null) return get().selector;
        return get().selector = new OkProxySelector();
    }

    public static void closeIdleConnections() {
        new Thread(OkHttp::evictIdleConnections, "okhttp-evict-idle").start();
    }

    private static synchronized void evictIdleConnections() {
        try {
            if (get().client != null) get().client.connectionPool().evictAll();
            if (get().player != null) get().player.connectionPool().evictAll();
            SpiderDebug.log("proxy", "connection pool evicted");
        } catch (Throwable e) {
            SpiderDebug.log("proxy", "connection pool evict failed error=%s", e.getMessage());
        }
    }

    public static synchronized OkHttpClient client() {
        if (get().client != null) return get().client;
        return get().client = getBuilder().build();
    }

    public static synchronized OkHttpClient player() {
        if (get().player != null) return get().player;
        return get().player = getBuilder().eventListenerFactory(call -> new DebugEventListener()).build();
    }

    public static OkHttpClient client(long timeout) {
        return client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).build();
    }

    public static OkHttpClient noRedirect() {
        return noRedirect(TIMEOUT);
    }

    public static OkHttpClient noRedirect(long timeout) {
        return client().newBuilder().connectTimeout(timeout, TimeUnit.MILLISECONDS).readTimeout(timeout, TimeUnit.MILLISECONDS).writeTimeout(timeout, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false).build();
    }

    public static OkHttpClient client(boolean redirect, long timeout) {
        return redirect ? client(timeout) : noRedirect(timeout);
    }

    public static String string(String url) {
        if (!url.startsWith("http")) return "";
        try (Response res = newCall(url)) {
            return res.body().string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "";
        }
    }

    public static String string(String url, Map<String, String> header) {
        return string(url, null, header);
    }

    public static String string(String url, Map<String, String> params, Map<String, String> header) {
        if (!url.startsWith("http")) return "";
        try {
            return new OkRequest(GET, url, params, header).execute(client()).getBody();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "";
        }
    }

    public static String string(String url, long timeout) {
        if (!url.startsWith("http")) return "";
        try (Response res = client(timeout).newCall(new Request.Builder().url(url).build()).execute()) {
            return res.body().string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "";
        }
    }

    public static String string(String url, Map<String, String> headers, long timeout) {
        if (!url.startsWith("http")) return "";
        try (Response res = client(timeout).newCall(new Request.Builder().url(url).headers(Headers.of(headers)).build()).execute()) {
            return res.body().string();
        } catch (Exception e) {
            SpiderDebug.log(e);
            return "";
        }
    }

    public static Response newCall(Request request) throws IOException {
        return client().newCall(request).execute();
    }

    public static Response newCall(String url) throws IOException {
        return client().newCall(new Request.Builder().url(url).build()).execute();
    }

    public static Response newCall(String url, Map<String, String> header) throws IOException {
        return client().newCall(new Request.Builder().url(url).headers(Headers.of(header)).build()).execute();
    }

    public static Call newCall(OkHttpClient client, String url) {
        return client.newCall(new Request.Builder().url(url).build());
    }

    public static Call newCall(OkHttpClient client, String url, Map<String, String> headers) {
        return client.newCall(new Request.Builder().url(url).headers(Headers.of(headers)).build());
    }

    public static Call newCall(OkHttpClient client, String url, RequestBody body) {
        return client.newCall(new Request.Builder().url(url).post(body).build());
    }

    public static OkResult get(String url, Map<String, String> params, Map<String, String> header) {
        return new OkRequest(GET, url, params, header).execute(client());
    }

    public static String post(String url, Map<String, String> params) {
        return post(url, params, null).getBody();
    }

    public static OkResult post(String url, Map<String, String> params, Map<String, String> header) {
        return new OkRequest(POST, url, params, header).execute(client());
    }

    public static String post(String url, String json) {
        return post(url, json, null).getBody();
    }

    public static OkResult post(String url, String json, Map<String, String> header) {
        return new OkRequest(POST, url, json, header).execute(client());
    }

    public static String getLocation(String url, Map<String, String> header) throws IOException {
        OkHttpClient noRedirect = client().newBuilder().followRedirects(false).followSslRedirects(false).build();
        return getLocation(noRedirect.newCall(new Request.Builder().url(url).headers(Headers.of(header)).build()).execute().headers().toMultimap());
    }

    public static String getLocation(Map<String, List<String>> headers) {
        if (headers == null) return null;
        if (headers.containsKey("location")) return headers.get("location").get(0);
        if (headers.containsKey("Location")) return headers.get("Location").get(0);
        return null;
    }

    public static void cancel(String tag) {
        cancel(client(), tag);
    }

    public static void cancel(OkHttpClient client, String tag) {
        for (Call call : client.dispatcher().queuedCalls()) if (tag.equals(call.request().tag())) call.cancel();
        for (Call call : client.dispatcher().runningCalls()) if (tag.equals(call.request().tag())) call.cancel();
    }

    public static void cancelAll() {
        cancelAll(client());
    }

    public static void cancelAll(OkHttpClient client) {
        client.dispatcher().cancelAll();
    }

    public static FormBody toBody(ArrayMap<String, String> params) {
        FormBody.Builder body = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) body.add(entry.getKey(), entry.getValue());
        return body.build();
    }

    private static OkHttpClient.Builder getBuilder() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder().addInterceptor(httpsUpgradeInterceptor()).addInterceptor(requestInterceptor()).addInterceptor(authInterceptor()).addNetworkInterceptor(responseInterceptor()).connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS).readTimeout(TIMEOUT, TimeUnit.MILLISECONDS).writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS).dns(dns());
        builder.proxyAuthenticator(authenticator());
        builder.proxySelector(selector());
        return builder;
    }

    private static Interceptor httpsUpgradeInterceptor() {
        return chain -> {
            Request request = chain.request();
            HttpUrl url = request.url();
            if (!url.isHttps() && isHttpsOnlyHost(url.host())) {
                HttpUrl httpsUrl = url.newBuilder().scheme("https").build();
                request = request.newBuilder().url(httpsUrl).build();
            }
            return chain.proceed(request);
        };
    }

    private static boolean isHttpsOnlyHost(String host) {
        return host.equals("cnb.cool") || host.endsWith(".cnb.cool") || host.equals("github.com") || host.endsWith(".github.com") || host.equals("githubusercontent.com") || host.endsWith(".githubusercontent.com") || host.equals("raw.githubusercontent.com") || host.endsWith(".raw.githubusercontent.com");
    }

    private static class DebugEventListener extends EventListener {

        private final long startNs;

        private DebugEventListener() {
            this.startNs = System.nanoTime();
        }

        @Override
        public void callStart(@NonNull Call call) {
            log(call, "start", "method=" + call.request().method() + ", headers=" + redact(call.request().headers()));
        }

        @Override
        public void connectStart(@NonNull Call call, @NonNull InetSocketAddress inetSocketAddress, @NonNull java.net.Proxy proxy) {
            log(call, "connectStart", "address=" + inetSocketAddress + ", proxy=" + proxy);
        }

        @Override
        public void connectEnd(@NonNull Call call, @NonNull InetSocketAddress inetSocketAddress, @NonNull java.net.Proxy proxy, @Nullable Protocol protocol) {
            log(call, "connectEnd", "address=" + inetSocketAddress + ", proxy=" + proxy + ", protocol=" + protocol);
        }

        @Override
        public void connectFailed(@NonNull Call call, @NonNull InetSocketAddress inetSocketAddress, @NonNull java.net.Proxy proxy, @Nullable Protocol protocol, @NonNull IOException ioe) {
            log(call, "connectFailed", "address=" + inetSocketAddress + ", proxy=" + proxy + ", protocol=" + protocol + ", error=" + error(ioe));
        }

        @Override
        public void connectionAcquired(@NonNull Call call, @NonNull Connection connection) {
            log(call, "connectionAcquired", "route=" + connection.route());
        }

        @Override
        public void responseHeadersEnd(@NonNull Call call, @NonNull Response response) {
            log(call, "response", "code=" + response.code() + ", message=" + response.message() + ", contentLength=" + response.header("Content-Length") + ", contentType=" + response.header("Content-Type") + ", contentRange=" + response.header("Content-Range"));
        }

        @Override
        public void callEnd(@NonNull Call call) {
            log(call, "end", "elapsedMs=" + elapsedMs());
        }

        @Override
        public void callFailed(@NonNull Call call, @NonNull IOException ioe) {
            log(call, "failed", "elapsedMs=" + elapsedMs() + ", error=" + error(ioe));
        }

        private void log(Call call, String event, String message) {
            SpiderDebug.log("okhttp-player", "%s url=%s %s", event, redactUrl(call.request().url()), message);
        }

        private String redactUrl(HttpUrl url) {
            if (url == null) return "";
            HttpUrl.Builder builder = url.newBuilder().query(null);
            for (String name : url.queryParameterNames()) builder.addQueryParameter(name, "***");
            return builder.build().toString();
        }

        private String redact(Headers headers) {
            StringBuilder builder = new StringBuilder();
            for (String name : headers.names()) {
                for (String value : headers.values(name)) builder.append(name).append(": ").append(isSensitive(name) ? "***" : value).append('\n');
            }
            return builder.toString().trim();
        }

        private boolean isSensitive(String name) {
            String lower = name.toLowerCase();
            return lower.contains("authorization") || lower.contains("cookie") || lower.contains("token") || lower.contains("password") || lower.contains("passwd") || lower.contains("secret");
        }

        private long elapsedMs() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        }

        private String error(Throwable error) {
            StringBuilder builder = new StringBuilder();
            Throwable current = error;
            int depth = 0;
            while (current != null && depth++ < 8) {
                if (builder.length() > 0) builder.append(" <- ");
                builder.append(current.getClass().getName());
                if (current.getMessage() != null) builder.append(": ").append(current.getMessage());
                current = current.getCause();
            }
            return builder.toString();
        }
    }

    public synchronized void clear() {
        cancelAll();
        dns().clear();
        selector().clear();
        authInterceptor().clear();
        requestInterceptor().clear();
        responseInterceptor().clear();
    }

    private static class Loader {
        static volatile OkHttp INSTANCE = new OkHttp();
    }
}
