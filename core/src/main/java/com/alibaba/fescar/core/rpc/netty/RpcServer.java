/*
 *  Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.alibaba.fescar.core.rpc.netty;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;

import com.alibaba.fescar.common.util.NetUtil;
import com.alibaba.fescar.core.protocol.HeartbeatMessage;
import com.alibaba.fescar.core.protocol.RegisterRMRequest;
import com.alibaba.fescar.core.protocol.RegisterTMRequest;
import com.alibaba.fescar.core.protocol.RpcMessage;
import com.alibaba.fescar.core.rpc.ChannelManager;
import com.alibaba.fescar.core.rpc.DefaultServerMessageListenerImpl;
import com.alibaba.fescar.core.rpc.RpcContext;
import com.alibaba.fescar.core.rpc.ServerMessageListener;
import com.alibaba.fescar.core.rpc.ServerMessageSender;
import com.alibaba.fescar.core.rpc.TransactionMessageHandler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The type Abstract rpc server.
 *
 * @Author: jimin.jm @alibaba-inc.com
 * @Project: fescar-all
 * @DateTime: 2018 /10/15 16:35
 * @FileName: RpcServer
 * @Description:
 */
@Sharable
public class RpcServer extends AbstractRpcRemotingServer implements ServerMessageSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(RpcServer.class);

    /**
     * The Server message listener.
     */
    protected ServerMessageListener serverMessageListener;

    private TransactionMessageHandler transactionMessageHandler;
    private RegisterCheckAuthHandler checkAuthHandler;

    /**
     * Sets transactionMessageHandler.
     *
     * @param transactionMessageHandler the transactionMessageHandler
     */
    public void setHandler(TransactionMessageHandler transactionMessageHandler) {
        setHandler(transactionMessageHandler, null);
    }

    /**
     * Sets transactionMessageHandler.
     *
     * @param transactionMessageHandler the transactionMessageHandler
     * @param checkAuthHandler          the check auth handler
     */
    public void setHandler(TransactionMessageHandler transactionMessageHandler,
                           RegisterCheckAuthHandler checkAuthHandler) {
        this.transactionMessageHandler = transactionMessageHandler;
        this.checkAuthHandler = checkAuthHandler;
    }

    /**
     * Instantiates a new Abstract rpc server.
     *
     * @param messageExecutor the message executor
     */
    public RpcServer(ThreadPoolExecutor messageExecutor) {
        super(new NettyServerConfig(), messageExecutor);
    }

    /**
     * Gets server message listener.
     *
     * @return the server message listener
     */
    public ServerMessageListener getServerMessageListener() {
        return serverMessageListener;
    }

    /**
     * Sets server message listener.
     *
     * @param serverMessageListener the server message listener
     */
    public void setServerMessageListener(ServerMessageListener serverMessageListener) {
        this.serverMessageListener = serverMessageListener;
    }

    /**
     * Debug log.
     *
     * @param info the info
     */
    public void debugLog(String info) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(info);
        }
    }

    /**
     * com.alibaba.fescar.server.Server #main 方法里调用
     * Init.
     */
    @Override
    public void init() {
        super.init();
        setChannelHandlers(RpcServer.this);
        /**
         * 维护的TransactionMessageHandler（com.alibaba.fescar.server.Server 初始化时设置的DefaultCoordinator） 监听器里有处理各种消息的方法：
            onRegRmMessage (处理注册RM的消息)
            onRegTmMessage (处理注册TM的消息)
            onCheckMessage (处理心跳检测的消息)
            onTrxMessage(处理事务消息):里面会调用TransactionMessageHandler(由上面可知是DefaultCoordinator类) 来处理消息，具体会根据请求类型进行处理
                GlobalBeginRequest ：事务开始请求
                GlobalCommitRequest：事务提交请求
                GlobalRollbackRequest：事务回滚请求
                ....
                这些请求最终调用com.alibaba.fescar.server.AbstractTCInboundHandler(DefaultCoordinator的父类) 里的handle 方法处理，最后到DefaultCoordinator相应的方法

         */
        DefaultServerMessageListenerImpl defaultServerMessageListenerImpl = new DefaultServerMessageListenerImpl(
            transactionMessageHandler);
        //维护的ServerMessageSender是RpcServer实例
        defaultServerMessageListenerImpl.setServerMessageSender(this);
        this.setServerMessageListener(defaultServerMessageListenerImpl);
        super.start();

    }

    private void closeChannelHandlerContext(ChannelHandlerContext ctx) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("closeChannelHandlerContext channel:" + ctx.channel());
        }
        ctx.disconnect();
        ctx.close();
    }

    /**
     * User event triggered.
     *
     * @param ctx the ctx
     * @param evt the evt
     * @throws Exception the exception
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            // 心跳检测
            debugLog("idle:" + evt);
            IdleStateEvent idleStateEvent = (IdleStateEvent)evt;
            if (idleStateEvent.state() == IdleState.READER_IDLE) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info("channel:" + ctx.channel() + " read idle.");
                }
                handleDisconnect(ctx);
                try {
                    closeChannelHandlerContext(ctx);
                } catch (Exception e) {
                    LOGGER.error(e.getMessage());
                }
            }
        }
    }

    /**
     * Destroy.
     */
    @Override
    public void destroy() {
        super.destroy();
        super.shutdown();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("destroyed rpcServer");
        }
    }

    /**
     * Send response.
     * rm reg,rpc reg,inner response
     *
     * @param msgId   the msg id
     * @param channel the channel
     * @param msg     the msg
     */
    @Override
    public void sendResponse(long msgId, Channel channel, Object msg) {
        Channel clientChannel = channel;
        if (!(msg instanceof HeartbeatMessage)) {
            clientChannel = ChannelManager.getSameClientChannel(channel);
        }
        if (clientChannel != null) {
            super.sendResponse(msgId, clientChannel, msg);
        } else {
            throw new RuntimeException("channel is error. channel:" + clientChannel);
        }
    }

    /**
     * Send request with response object.
     * send syn request for rm
     *
     * @param resourceId         the db key
     * @param clientId      the client ip
     * @param message           the message
     * @param timeout       the timeout
     * @return the object
     * @throws TimeoutException the timeout exception
     */
    @Override
    public Object sendSyncRequest(String resourceId, String clientId, Object message,
                                  long timeout) throws TimeoutException {
        Channel clientChannel = ChannelManager.getChannel(resourceId, clientId);
        if (clientChannel == null) {
            throw new RuntimeException("rm client is not connected. dbkey:" + resourceId
                + ",clientId:" + clientId);

        }
        return sendAsyncRequestWithResponse(null, clientChannel, message, timeout);
    }

    /**
     * Send request with response object.
     *
     * @param resourceId         the db key
     * @param clientId      the client ip
     * @param message           the msg
     * @return the object
     * @throws TimeoutException the timeout exception
     */
    @Override
    public Object sendSyncRequest(String resourceId, String clientId, Object message)
        throws TimeoutException {
        return sendSyncRequest(resourceId, clientId, message, NettyServerConfig.getRpcRequestTimeout());
    }

    /**
     * Dispatch.  分发RPC 消息
     *
     * @param msgId the msg id
     * @param ctx   the ctx
     * @param msg   the msg
     */
    @Override
    public void dispatch(long msgId, ChannelHandlerContext ctx, Object msg) {
        // 注册TM 请求
        if (msg instanceof RegisterRMRequest) {
            /**
             * 通过DefaultServerMessageListenerImpl#onRegRmMessage 处理 注册TM 请求
             * @see DefaultServerMessageListenerImpl#onRegRmMessage(long, io.netty.channel.ChannelHandlerContext, com.alibaba.fescar.core.protocol.RegisterRMRequest, com.alibaba.fescar.core.rpc.ServerMessageSender, com.alibaba.fescar.core.rpc.netty.RegisterCheckAuthHandler)
             */
            serverMessageListener.onRegRmMessage(msgId, ctx, (RegisterRMRequest)msg, this,
                checkAuthHandler);
        } else {
            if (ChannelManager.isRegistered(ctx.channel())) {
                serverMessageListener.onTrxMessage(msgId, ctx, msg, this);
            } else {
                try {
                    closeChannelHandlerContext(ctx);
                } catch (Exception exx) {
                    LOGGER.error(exx.getMessage());
                }
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(String.format("close a unhandled connection! [%s]", ctx.channel().toString()));
                }
            }
        }
    }

    /**
     * Channel inactive.
     *
     * @param ctx the ctx
     * @throws Exception the exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        debugLog("inactive:" + ctx);
        if (messageExecutor.isShutdown()) {
            return;
        }
        handleDisconnect(ctx);
        super.channelInactive(ctx);
    }

    private void handleDisconnect(ChannelHandlerContext ctx) {
        final String ipAndPort = NetUtil.toStringAddress(ctx.channel().remoteAddress());
        RpcContext rpcContext = ChannelManager.getContextFromIdentified(ctx.channel());
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(ipAndPort + " to server channel inactive.");
        }
        if (null != rpcContext && null != rpcContext.getClientRole()) {
            rpcContext.release();
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("remove channel:" + ctx.channel() + "context:" + rpcContext);
            }
        } else {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("remove unused channel:" + ctx.channel());
            }
        }
    }

    /**
     * Channel read. 接收客户端消息
     *
     * @param ctx the ctx
     * @param msg the msg
     * @throws Exception the exception
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof RpcMessage) {
            RpcMessage rpcMessage = (RpcMessage)msg;
            debugLog("read:" + rpcMessage.getBody().toString());
            if (rpcMessage.getBody() instanceof RegisterTMRequest) {
                RegisterTMRequest request
                    = (RegisterTMRequest)rpcMessage
                    .getBody();
                serverMessageListener.onRegTmMessage(rpcMessage.getId(), ctx, request, this, checkAuthHandler);
                return;
            }
            if (rpcMessage.getBody() == HeartbeatMessage.PING) {
                serverMessageListener.onCheckMessage(rpcMessage.getId(), ctx, this);
                return;
            }
        }

         /**
         *  //使用父类的channelRead 方法 接收客户端消息, 最后会调用本类的dispatch 方法
         *  @see RpcServer#dispatch(long, io.netty.channel.ChannelHandlerContext, java.lang.Object)
         */
        super.channelRead(ctx, msg);
    }

    /**
     * Exception caught.
     *
     * @param ctx   the ctx
     * @param cause the cause
     * @throws Exception the exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("channel exx:" + cause.getMessage() + ",channel:" + ctx.channel());
        }
        ChannelManager.releaseRpcContext(ctx.channel());
        super.exceptionCaught(ctx, cause);
    }
}
