package pane5250.update;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.LongConsumer;
import pane5250.Main;

final class UpdateHttp implements Updates.Transport {
    private HttpClient client;
    private boolean closed;
    private synchronized HttpClient client() throws IOException {
        if (closed) throw new IOException("Updater is closed.");
        if (client==null) client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return client;
    }
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread=new Thread(r,"update-timeout"); thread.setDaemon(true); return thread;
    });
    private static boolean assetOrigin(URI uri) {
        return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost()) && uri.getPort()==-1
                && uri.getRawUserInfo()==null && uri.getRawQuery()==null && uri.getRawFragment()==null
                && uri.getRawPath().matches("/tsafago/Fish/releases/download/v?(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})/[A-Za-z0-9._-]+");
    }
    static boolean allowed(URI original,URI next) {
        if (!"https".equals(next.getScheme()) || next.getRawUserInfo()!=null || next.getRawFragment()!=null
                || next.getPort()!=-1 && next.getPort()!=443) return false;
        if (original.equals(Updates.API)) return original.equals(next);
        if (!assetOrigin(original)) return false;
        return next.equals(original) || Set.of("release-assets.githubusercontent.com","objects.githubusercontent.com").contains(next.getHost());
    }
    @Override public void fetch(URI url,OutputStream target,long limit,Updates.Cancel cancel,LongConsumer progress) throws IOException {
        if (limit<1 || limit>Updates.MAX_INSTALLER || !"https".equals(url.getScheme()))
            throw new IOException("Invalid update transfer.");
        URI current=url;
        ScheduledFuture<?> deadline=deadlines.schedule(() -> cancel.cancel("GitHub update request timed out."),
                url.equals(Updates.API) ? 12 : 180,TimeUnit.SECONDS);
        try {
            for (int redirects=0;redirects<4;redirects++) {
                cancel.check();
                if (!allowed(url,current)) throw new IOException("Update redirect left the allowed GitHub origin.");
                HttpRequest request=HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(10))
                        .header("User-Agent","Fish/"+Main.VERSION).header("Accept","application/vnd.github+json")
                        .GET().build();
                CompletableFuture<HttpResponse<InputStream>> future=client().sendAsync(request,HttpResponse.BodyHandlers.ofInputStream());
                cancel.abortWith(() -> future.cancel(true));
                HttpResponse<InputStream> response;
                try { response=future.get(12,TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Update cancelled."); }
                catch (ExecutionException | TimeoutException | CancellationException e) {
                    future.cancel(true); throw new IOException("GitHub update request failed: "+e.getClass().getSimpleName(),e);
                }
                InputStream body=response.body();
                cancel.abortWith(() -> close(body));
                try (body) {
                    int status=response.statusCode();
                    if (Set.of(301,302,303,307,308).contains(status)) {
                        String location=response.headers().firstValue("location").orElseThrow(() -> new IOException("Missing update redirect."));
                        try { current=current.resolve(location); }
                        catch (IllegalArgumentException e) { throw new IOException("Invalid update redirect.",e); }
                        continue;
                    }
                    if (status!=200) throw new IOException(status==403 || status==429
                            ? "GitHub is rate-limiting update checks. Try again later."
                            : "GitHub returned HTTP "+status+". Check your connection and try again.");
                    if (response.headers().firstValueAsLong("Content-Length").orElse(0)>limit)
                        throw new IOException("Update response exceeds its size limit.");
                    if (!response.headers().firstValue("Content-Encoding").orElse("identity").equals("identity"))
                        throw new IOException("Unexpected update response encoding.");
                    byte[] buffer=new byte[65536]; long count=0; int size;
                    while ((size=body.read(buffer))!=-1) {
                        cancel.check(); count+=size;
                        if (count>limit) throw new IOException("Update response exceeds its size limit.");
                        target.write(buffer,0,size); progress.accept(count);
                    }
                    cancel.check(); return;
                } finally { cancel.detach(); }
            }
            throw new IOException("Too many update redirects.");
        } finally { deadline.cancel(false); cancel.detach(); }
    }
    private static void close(InputStream stream) {
        try { stream.close(); }
        catch (IOException e) { System.getLogger(UpdateHttp.class.getName()).log(System.Logger.Level.DEBUG,"Cancelled update stream: "+e.getMessage()); }
    }
    @Override public synchronized void close() { closed=true; deadlines.shutdownNow(); if (client!=null) client.shutdownNow(); }
}
