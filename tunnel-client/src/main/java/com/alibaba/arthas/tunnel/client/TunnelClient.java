package com.alibaba.arthas.tunnel.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import javax.net.ssl.SSLException;

import io.netty.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.arthas.tunnel.common.MethodConstants;
import com.alibaba.arthas.tunnel.common.URIConstans;
import com.taobao.arthas.common.ArthasConstants;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * 隧道客户端，负责与Arthas隧道服务器建立WebSocket连接
 * 实现代理注册、命令接收和结果回传等功能
 *
 * @author hengyunabc 2019-08-28
 */
public class TunnelClient {
    private final static Logger logger = LoggerFactory.getLogger(TunnelClient.class);

    // 隧道服务器URL
    private String tunnelServerUrl;

    // 重连延迟时间(秒)
    private int reconnectDelay = 5;

    // 事件循环组，使用2个线程处理连接和重连(#1284问题)
    private EventLoopGroup eventLoopGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("arthas-TunnelClient", true));

    // 应用名称
    private String appName;
    // 代理ID，由隧道服务器生成，重连时复用
    volatile private String id;

    /**
     * Arthas版本号
     */
    private String version = "unknown";

    // 连接状态标记
    private volatile boolean connected = false;

    /**
     * 启动隧道客户端，建立与隧道服务器的连接
     */
    public ChannelFuture start() throws IOException, InterruptedException, URISyntaxException {
        return connect(false);
    }

    /**
     * 连接到隧道服务器
     * @param reconnect 是否为重连
     */
    public ChannelFuture connect(boolean reconnect) throws SSLException, URISyntaxException, InterruptedException {
        // 构建查询参数编码器
        QueryStringEncoder queryEncoder = new QueryStringEncoder(this.tunnelServerUrl);
        // 添加代理注册方法参数
        queryEncoder.addParam(URIConstans.METHOD, MethodConstants.AGENT_REGISTER);
        // 添加Arthas版本参数
        queryEncoder.addParam(URIConstans.ARTHAS_VERSION, this.version);
        // 添加应用名称参数(如果有)
        if (appName != null) {
            queryEncoder.addParam(URIConstans.APP_NAME, appName);
        }
        // 添加代理ID参数(如果有)
        if (id != null) {
            queryEncoder.addParam(URIConstans.ID, id);
        }
        // 生成代理注册URI
        final URI agentRegisterURI = queryEncoder.toUri();

        logger.info("Try to register arthas agent, uri: {}", agentRegisterURI);

        // 解析URI方案(ws或wss)
        String scheme = agentRegisterURI.getScheme() == null ? "ws" : agentRegisterURI.getScheme();
        // 解析主机名
        final String host = agentRegisterURI.getHost() == null ? "127.0.0.1" : agentRegisterURI.getHost();
        // 解析端口
        final int port;
        if (agentRegisterURI.getPort() == -1) {
            // 根据方案设置默认端口
            if ("ws".equalsIgnoreCase(scheme)) {
                port = 80;
            } else if ("wss".equalsIgnoreCase(scheme)) {
                port = 443;
            } else {
                port = -1;
            }
        } else {
            port = agentRegisterURI.getPort();
        }

        // 检查是否支持的方案
        if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only WS(S) is supported. tunnelServerUrl: " + tunnelServerUrl);
        }

        // 判断是否使用SSL
        final boolean ssl = "wss".equalsIgnoreCase(scheme);
        // 初始化SSL上下文(仅wss方案)
        final SslContext sslCtx;
        if (ssl) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } else {
            sslCtx = null;
        }

        // 构建WebSocket客户端协议配置
        WebSocketClientProtocolConfig clientProtocolConfig = WebSocketClientProtocolConfig.newBuilder()
                .webSocketUri(agentRegisterURI)
                .maxFramePayloadLength(ArthasConstants.MAX_HTTP_CONTENT_LENGTH).build();

        // 创建WebSocket客户端协议处理器
        final WebSocketClientProtocolHandler websocketClientHandler = new WebSocketClientProtocolHandler(
                clientProtocolConfig);
        // 创建隧道客户端套接字处理器
        final TunnelClientSocketClientHandler handler = new TunnelClientSocketClientHandler(TunnelClient.this);

        // 初始化Netty客户端引导类
        Bootstrap bs = new Bootstrap();

        // 配置客户端引导参数
        bs.group(eventLoopGroup)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000) // 连接超时时间5秒
                .option(ChannelOption.TCP_NODELAY, true) // 禁用Nagle算法，提高实时性
                .channel(NioSocketChannel.class) // 使用NIO套接字通道
                .remoteAddress(host, port)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // 添加SSL处理器(如果需要)
                        if (sslCtx != null) {
                            p.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                        }

                        // 添加管道处理器
                        p.addLast(
                                new HttpClientCodec(), // HTTP编解码器
                                new HttpObjectAggregator(ArthasConstants.MAX_HTTP_CONTENT_LENGTH), // HTTP对象聚合器
                                websocketClientHandler, // WebSocket协议处理器
                                new IdleStateHandler(0, 0, ArthasConstants.WEBSOCKET_IDLE_SECONDS), // 空闲状态处理器
                                handler // 隧道客户端套接字处理器
                        );
                    }
                });

        // 连接到服务器
        ChannelFuture connectFuture = bs.connect();
        // 重连时添加监听器
        if (reconnect) {
            connectFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.cause() != null) {
                        logger.error("connect to tunnel server error, uri: {}", tunnelServerUrl, future.cause());
                    }
                }
            });
        }
        // 等待连接完成
        connectFuture.sync();

        return handler.registerFuture();
    }

    /**
     * 停止隧道客户端，释放资源
     */
    public void stop() {
        eventLoopGroup.shutdownGracefully();
    }

    // getter和setter方法
    public String getTunnelServerUrl() {
        return tunnelServerUrl;
    }

    public void setTunnelServerUrl(String tunnelServerUrl) {
        this.tunnelServerUrl = tunnelServerUrl;
    }

    public int getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(int reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }
}