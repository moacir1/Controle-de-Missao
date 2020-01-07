package org.yamcs.client;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

import org.yamcs.api.MediaType;
import org.yamcs.client.ClientException.RestExceptionData;
import org.yamcs.protobuf.RestExceptionMessage;
import org.yamcs.protobuf.Table;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.Extension;
import com.google.protobuf.ExtensionRegistry;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder.ErrorDataEncoderException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

public class HttpClient {
    // extensions for the RestExceptionMessage
    static ExtensionRegistry exceptionRegistry = ExtensionRegistry.newInstance();
    static Set<Extension<RestExceptionMessage, ?>> exceptionExtensions = new HashSet<>(1);
    static {
        exceptionRegistry.add(Table.rowsLoaded);
        exceptionExtensions.add(Table.rowsLoaded);
    }

    MediaType sendMediaType = MediaType.PROTOBUF;
    MediaType acceptMediaType = MediaType.PROTOBUF;
    EventLoopGroup group;
    private List<Cookie> cookies;
    private SslContext sslCtx;
    // if set, do not verify server certificate
    private boolean insecureTls;

    KeyStore caKeyStore;
    private int maxResponseLength = 1024 * 1024;// max length of the expected response

    private String tokenUrl;
    private Credentials credentials;

    public synchronized void login(String tokenUrl, String username, char[] password) throws ClientException {
        this.tokenUrl = tokenUrl;
        Map<String, String> attrs = new HashMap<>();
        attrs.put("grant_type", "password");
        attrs.put("username", username);
        attrs.put("password", new String(password));

        try {
            credentials = requestTokens(tokenUrl, attrs).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ClientException) {
                throw (ClientException) e.getCause();
            } else {
                throw new ClientException(e.getCause());
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new ClientException(e);
        }
    }

    public synchronized void loginWithAuthorizationCode(String tokenUrl, String authorizationCode)
            throws ClientException {
        this.tokenUrl = tokenUrl;
        Map<String, String> attrs = new HashMap<>();
        attrs.put("grant_type", "authorization_code");
        attrs.put("code", authorizationCode);

        try {
            credentials = requestTokens(tokenUrl, attrs).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ClientException) {
                throw (ClientException) e.getCause();
            } else {
                throw new ClientException(e.getCause());
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new ClientException(e);
        }
    }

    public synchronized void refreshAccessToken() throws ClientException {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("grant_type", "refresh_token");
        attrs.put("refresh_token", credentials.getRefreshToken());

        try {
            credentials = requestTokens(tokenUrl, attrs).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ClientException) {
                throw (ClientException) e.getCause();
            } else {
                throw new ClientException(e.getCause());
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new ClientException(e);
        }
    }

    public Credentials getCredentials() {
        return credentials;
    }

    private CompletableFuture<Credentials> requestTokens(String url, Map<String, String> attrs)
            throws ClientException, IOException, GeneralSecurityException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ClientException(e);
        }

        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();
        HttpObjectAggregator aggregator = new HttpObjectAggregator(maxResponseLength);

        ResponseHandler respHandler = new ResponseHandler(responseFuture);

        HttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, uri.getRawPath());
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        request.headers().set(HttpHeaderNames.HOST, host);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded");

        try {
            HttpPostRequestEncoder formEncoder = new HttpPostRequestEncoder(request, false);
            for (Entry<String, String> attr : attrs.entrySet()) {
                formEncoder.addBodyAttribute(attr.getKey(), attr.getValue());
            }
            formEncoder.finalizeRequest();
        } catch (ErrorDataEncoderException e) {
            throw new ClientException(e);
        }

        ChannelFuture channelFuture = setupChannel(uri, aggregator, respHandler);
        channelFuture.addListener(f -> {
            if (!f.isSuccess()) {
                responseFuture.completeExceptionally(f.cause());
                return;
            }
            channelFuture.channel().writeAndFlush(request);
        });

        return responseFuture.thenApply(data -> {
            Map<String, Object> map = jsonToMap(new String(data));
            return new Credentials(map);
        });
    }

    public CompletableFuture<byte[]> doAsyncRequest(String url, HttpMethod httpMethod, byte[] body)
            throws ClientException, IOException, GeneralSecurityException {
        return doAsyncRequest(url, httpMethod, body, null);
    }

    public CompletableFuture<byte[]> doAsyncRequest(String url, HttpMethod httpMethod, byte[] body,
            HttpHeaders extraHeaders) throws ClientException, IOException, GeneralSecurityException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ClientException(e);
        }
        HttpObjectAggregator aggregator = new HttpObjectAggregator(maxResponseLength);

        CompletableFuture<byte[]> responseFuture = new CompletableFuture<>();

        HttpRequest request = setupRequest(uri, httpMethod, body);
        if (extraHeaders != null) {
            request.headers().add(extraHeaders);
        }

        ResponseHandler respHandler = new ResponseHandler(responseFuture);

        ChannelFuture channelFuture = setupChannel(uri, aggregator, respHandler);
        channelFuture.addListener(f -> {
            if (!f.isSuccess()) {
                responseFuture.completeExceptionally(f.cause());
                return;
            }
            channelFuture.channel().writeAndFlush(request);
        });
        return responseFuture;
    }

    public CompletableFuture<BulkRestDataSender> doBulkSendRequest(String url, HttpMethod httpMethod)
            throws ClientException, IOException, GeneralSecurityException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ClientException(e);
        }
        CompletableFuture<BulkRestDataSender> cf = new CompletableFuture<>();
        BulkRestDataSender.ContinuationHandler chandler = new BulkRestDataSender.ContinuationHandler(cf);

        ChannelFuture chf = setupChannel(uri, chandler);
        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, httpMethod, getPathWithQuery(uri));
        fillInHeaders(request, uri);
        request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        HttpUtil.set100ContinueExpected(request, true);

        chf.addListener(f -> {
            if (!f.isSuccess()) {
                cf.completeExceptionally(f.cause());
                return;
            }
            chf.channel().writeAndFlush(request);
        });

        return cf;
    }

    static ClientException decodeException(HttpObject httpObj) throws IOException {
        if (!(httpObj instanceof HttpResponse)) {
            return getInvalidHttpResponseException(httpObj.toString());
        }
        if (!(httpObj instanceof FullHttpResponse)) {
            return getInvalidHttpResponseException(((HttpResponse) httpObj).status().toString());
        }

        FullHttpResponse fullResp = (FullHttpResponse) httpObj;

        if (fullResp.status() == HttpResponseStatus.UNAUTHORIZED) {
            return new UnauthorizedException();
        }

        byte[] data = getByteArray(fullResp.content());
        String contentType = fullResp.headers().get(HttpHeaderNames.CONTENT_TYPE);

        // We could probably simplify this by using Any type once using protobuf3
        // Reason for the complexity is that 'extensions' are not supported by JsonFormat
        // (note that extensions were removed from proto3)
        if (MediaType.JSON.is(contentType)) {
            Map<String, Object> obj = jsonToMap(new String(data));
            String type = (String) obj.get("type");
            String message = (String) obj.get("msg");
            RestExceptionData excData = new RestExceptionData(type, message);
            for (Entry<String, Object> entry : obj.entrySet()) {
                if (!"type".equals(entry.getKey()) && !"msg".equals(entry.getKey())) {
                    excData.addDetail(entry.getKey(), entry.getValue());
                }
            }
            return new ClientException(excData);
        } else if (MediaType.PROTOBUF.is(contentType)) {
            RestExceptionMessage msg = RestExceptionMessage.parseFrom(data, exceptionRegistry);
            RestExceptionData excData = new RestExceptionData(msg.getType(), msg.getMsg());
            for (Extension<RestExceptionMessage, ?> extension : exceptionExtensions) {
                if (msg.hasExtension(extension)) {
                    String key = extension.getDescriptor().getJsonName();
                    excData.addDetail(key, msg.getExtension(extension));
                }
            }
            return new ClientException(excData);
        } else {
            return new ClientException(fullResp.status() + ": " + new String(data));
        }
    }

    private static Map<String, Object> jsonToMap(String json) {
        Type gsonType = new TypeToken<Map<String, Object>>() {
        }.getType();
        return new Gson().fromJson(json, gsonType);
    }

    static private ClientException getInvalidHttpResponseException(String resp) {
        return new ClientException("Received http response: " + resp);
    }

    /**
     * Sets the maximum size of the responses - this is not applicable to bulk requests whose response is practically
     * unlimited and delivered piece by piece
     * 
     * @param length
     */
    public void setMaxResponseLength(int length) {
        this.maxResponseLength = length;
    }

    /**
     * Perform a request that potentially retrieves large amount of data. The data is forwarded to the client.
     * 
     * @param receiver
     *            send all the data to this receiver. To find out when the request has been finished, the Future has to
     *            be used
     * @return a future indicating when the operation is completed.
     * @throws GeneralSecurityException
     * @throws IOException
     */
    public CompletableFuture<Void> doBulkReceiveRequest(String url, HttpMethod httpMethod, byte[] body,
            BulkRestDataReceiver receiver) throws ClientException, IOException, GeneralSecurityException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ClientException(e);
        }
        BulkChannelHandler channelHandler = new BulkChannelHandler(receiver);

        ChannelFuture chf = setupChannel(uri, channelHandler);
        HttpRequest request = setupRequest(uri, httpMethod, body);
        CompletableFuture<Void> cf = new CompletableFuture<>();

        chf.addListener(f -> {
            if (!f.isSuccess()) {
                cf.completeExceptionally(f.cause());
                return;
            }
            Channel ch = chf.channel();
            ch.writeAndFlush(request);
            ChannelFuture closeFuture = ch.closeFuture();
            closeFuture.addListener(f1 -> {
                if (channelHandler.exception != null) {
                    cf.completeExceptionally(channelHandler.exception);
                } else {
                    cf.complete(null);
                }
            });

        });
        cf.whenComplete((v, t) -> {
            if (t instanceof CancellationException) {
                chf.channel().close();
            }
        });
        return cf;
    }

    public CompletableFuture<Void> doBulkRequest(String url, HttpMethod httpMethod, String body,
            BulkRestDataReceiver receiver) throws ClientException, IOException, GeneralSecurityException {
        return doBulkReceiveRequest(url, httpMethod, body.getBytes(), receiver);
    }

    private ChannelFuture setupChannel(URI uri, ChannelHandler... channelHandler)
            throws IOException, GeneralSecurityException {
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            port = 80;
        }

        if ("https".equalsIgnoreCase(scheme)) {
            sslCtx = getSslContext();
        } else if (!"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only HTTP and HTTPS are supported.");
        }

        if (group == null) {
            group = new NioEventLoopGroup();
        }

        Bootstrap b = new Bootstrap();
        b.group(group).channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (sslCtx != null) {
                            p.addLast(sslCtx.newHandler(ch.alloc()));
                        }
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpContentDecompressor());
                        p.addLast(channelHandler);
                    }
                });
        return b.connect(host, port);
    }

    private SslContext getSslContext() throws GeneralSecurityException, SSLException {
        if (insecureTls) {
            return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

        if (caKeyStore != null) {
            tmf.init(caKeyStore);
        } // else the default trustStore configured with -Djavax.net.ssl.trustStore is used

        return SslContextBuilder.forClient().trustManager(tmf).build();
    }

    /**
     * In case of https connections, this file contains the CA certificates that are used to verify server certificate
     * 
     * @param caCertFile
     */
    public void setCaCertFile(String caCertFile) throws IOException, GeneralSecurityException {
        caKeyStore = CertUtil.loadCertFile(caCertFile);
    }

    public boolean isInsecureTls() {
        return insecureTls;
    }

    /**
     * if true and https connections are used, do not verify server certificate
     * 
     * @param insecureTls
     */
    public void setInsecureTls(boolean insecureTls) {
        this.insecureTls = insecureTls;
    }

    private void fillInHeaders(HttpRequest request, URI uri) throws ClientException {
        String host = uri.getHost() == null ? "127.0.0.1" : uri.getHost();
        request.headers().set(HttpHeaderNames.HOST, host);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, sendMediaType);
        request.headers().set(HttpHeaderNames.ACCEPT, acceptMediaType);
        if (cookies != null) {
            String c = ClientCookieEncoder.STRICT.encode(cookies);
            request.headers().set(HttpHeaderNames.COOKIE, c);
        }
        if (credentials != null) {
            if (credentials.isExpired()) {
                refreshAccessToken(); // This blocks
            }
            credentials.modifyRequest(request);
        }
    }

    private HttpRequest setupRequest(URI uri, HttpMethod httpMethod, byte[] body) throws ClientException {
        ByteBuf content = (body == null) ? Unpooled.EMPTY_BUFFER : Unpooled.copiedBuffer(body);
        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, httpMethod, getPathWithQuery(uri),
                content);
        fillInHeaders(request, uri);
        int length = body == null ? 0 : body.length;
        HttpUtil.setContentLength(request, length);
        return request;
    }

    private String getPathWithQuery(URI uri) {
        String r = uri.getRawPath();
        if (uri.getRawQuery() != null) {
            r += "?" + uri.getRawQuery();
        }
        return r;
    }

    public void addCookie(Cookie c) {
        if (cookies == null) {
            cookies = new ArrayList<>();
        }
        cookies.add(c);
    }

    public List<Cookie> getCookies() {
        return Collections.unmodifiableList(cookies);
    }

    public MediaType getSendMediaType() {
        return sendMediaType;
    }

    public void setSendMediaType(MediaType sendMediaType) {
        this.sendMediaType = sendMediaType;
    }

    public MediaType getAcceptMediaType() {
        return acceptMediaType;
    }

    public void setAcceptMediaType(MediaType acceptMediaType) {
        this.acceptMediaType = acceptMediaType;
    }

    public void close() {
        if (group != null) {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
    }

    static byte[] getByteArray(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.readBytes(b);
        return b;
    }

    class ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        Throwable exception;
        CompletableFuture<byte[]> cf;

        public ResponseHandler(CompletableFuture<byte[]> cf) {
            this.cf = cf;
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, FullHttpResponse fullHttpResp) {
            if (fullHttpResp.status().code() != HttpResponseStatus.OK.code()) {
                try {
                    exception = decodeException(fullHttpResp);
                } catch (IOException e) {
                    exception = e;
                }
                cf.completeExceptionally(exception);
            } else {
                cf.complete(getByteArray(fullHttpResp.content()));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // cause.printStackTrace();
            exception = cause;
            ctx.close();
            cf.completeExceptionally(cause);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (!cf.isDone()) {
                cf.completeExceptionally(new IOException("connection closed: empty response received"));
            }
        }
    }

    static class BulkChannelHandler extends SimpleChannelInboundHandler<HttpObject> {
        final BulkRestDataReceiver receiver;
        Throwable exception;

        BulkChannelHandler(BulkRestDataReceiver receiver) {
            this.receiver = receiver;
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws IOException {
            if (msg instanceof HttpResponse) {
                HttpResponse resp = (HttpResponse) msg;
                if (resp.status().code() != HttpResponseStatus.OK.code()) {
                    exception = decodeException(msg);
                    receiver.receiveException(exception);
                    ctx.close();
                }
            }
            if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                try {
                    receiver.receiveData(getByteArray(content.content()));
                } catch (ClientException e) {
                    exceptionCaught(ctx, e);
                }
                if (content instanceof LastHttpContent) {
                    ctx.close();
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            receiver.receiveException(cause);
            exception = cause;
            ctx.close();
        }
    }
}