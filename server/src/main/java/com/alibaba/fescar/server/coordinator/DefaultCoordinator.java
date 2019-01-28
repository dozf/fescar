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

package com.alibaba.fescar.server.coordinator;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.alibaba.fescar.common.XID;
import com.alibaba.fescar.common.thread.NamedThreadFactory;
import com.alibaba.fescar.core.exception.TransactionException;
import com.alibaba.fescar.core.model.BranchStatus;
import com.alibaba.fescar.core.model.GlobalStatus;
import com.alibaba.fescar.core.model.ResourceManagerInbound;
import com.alibaba.fescar.core.protocol.AbstractMessage;
import com.alibaba.fescar.core.protocol.AbstractResultMessage;
import com.alibaba.fescar.core.protocol.transaction.AbstractTransactionRequestToTC;
import com.alibaba.fescar.core.protocol.transaction.AbstractTransactionResponse;
import com.alibaba.fescar.core.protocol.transaction.BranchCommitRequest;
import com.alibaba.fescar.core.protocol.transaction.BranchCommitResponse;
import com.alibaba.fescar.core.protocol.transaction.BranchRegisterRequest;
import com.alibaba.fescar.core.protocol.transaction.BranchRegisterResponse;
import com.alibaba.fescar.core.protocol.transaction.BranchReportRequest;
import com.alibaba.fescar.core.protocol.transaction.BranchReportResponse;
import com.alibaba.fescar.core.protocol.transaction.BranchRollbackRequest;
import com.alibaba.fescar.core.protocol.transaction.BranchRollbackResponse;
import com.alibaba.fescar.core.protocol.transaction.GlobalBeginRequest;
import com.alibaba.fescar.core.protocol.transaction.GlobalBeginResponse;
import com.alibaba.fescar.core.protocol.transaction.GlobalCommitRequest;
import com.alibaba.fescar.core.protocol.transaction.GlobalCommitResponse;
import com.alibaba.fescar.core.protocol.transaction.GlobalLockQueryRequest;
import com.alibaba.fescar.core.protocol.transaction.GlobalLockQueryResponse;
import com.alibaba.fescar.core.protocol.transaction.GlobalRollbackRequest;
import com.alibaba.fescar.core.protocol.transaction.GlobalRollbackResponse;
import com.alibaba.fescar.core.protocol.transaction.GlobalStatusRequest;
import com.alibaba.fescar.core.protocol.transaction.GlobalStatusResponse;
import com.alibaba.fescar.core.rpc.RpcContext;
import com.alibaba.fescar.core.rpc.ServerMessageSender;
import com.alibaba.fescar.core.rpc.TransactionMessageHandler;
import com.alibaba.fescar.server.AbstractTCInboundHandler;
import com.alibaba.fescar.server.session.BranchSession;
import com.alibaba.fescar.server.session.GlobalSession;
import com.alibaba.fescar.server.session.SessionHolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.alibaba.fescar.core.exception.TransactionExceptionCode.FailedToSendBranchCommitRequest;
import static com.alibaba.fescar.core.exception.TransactionExceptionCode.FailedToSendBranchRollbackRequest;

public class DefaultCoordinator extends AbstractTCInboundHandler
    implements TransactionMessageHandler, ResourceManagerInbound {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCoordinator.class);

    private ServerMessageSender messageSender;

    private Core core = CoreFactory.get();

    public DefaultCoordinator(ServerMessageSender messageSender) {
        this.messageSender = messageSender;
        core.setResourceManagerInbound(this);
    }

    // 处理全局事务开始的请求
    @Override
    protected void doGlobalBegin(GlobalBeginRequest request, GlobalBeginResponse response, RpcContext rpcContext)
        throws TransactionException {
        /**
        生成xid。 通过DefaultCore#begin方法生成一个全局会话GlobalSession，并把GlobalSession 交给 SessionManager 管理，最后根据GlobalSession的transactionId 生成xid
         */
        response.setXid(core.begin(rpcContext.getApplicationId(), rpcContext.getTransactionServiceGroup(),
            request.getTransactionName(), request.getTimeout()));
    }

    // 处理全局事务提交的请求
    @Override
    protected void doGlobalCommit(GlobalCommitRequest request, GlobalCommitResponse response, RpcContext rpcContext)
        throws TransactionException {
        response.setGlobalStatus(core.commit(XID.generateXID(request.getTransactionId())));

    }

    // 处理全局事务回滚的请求
    @Override
    protected void doGlobalRollback(GlobalRollbackRequest request, GlobalRollbackResponse response,
                                    RpcContext rpcContext) throws TransactionException {
        response.setGlobalStatus(core.rollback(XID.generateXID(request.getTransactionId())));

    }

    // 处理更改全局事务状态的请求
    @Override
    protected void doGlobalStatus(GlobalStatusRequest request, GlobalStatusResponse response, RpcContext rpcContext)
        throws TransactionException {
        response.setGlobalStatus(core.getStatus(XID.generateXID(request.getTransactionId())));
    }

    // 处理注册分支事务的请求
    @Override
    protected void doBranchRegister(BranchRegisterRequest request, BranchRegisterResponse response,
                                    RpcContext rpcContext) throws TransactionException {
        response.setTransactionId(request.getTransactionId());
        response.setBranchId(
            core.branchRegister(request.getBranchType(), request.getResourceId(), rpcContext.getClientId(),
                XID.generateXID(request.getTransactionId()), request.getLockKey()));

    }

    // 处理分支事务回复的请求
    @Override
    protected void doBranchReport(BranchReportRequest request, BranchReportResponse response, RpcContext rpcContext)
        throws TransactionException {
        core.branchReport(XID.generateXID(request.getTransactionId()), request.getBranchId(), request.getStatus(),
            request.getApplicationData());

    }

    // 处理全局锁查询的请求
    @Override
    protected void doLockCheck(GlobalLockQueryRequest request, GlobalLockQueryResponse response, RpcContext rpcContext)
        throws TransactionException {
        response.setLockable(core.lockQuery(request.getBranchType(), request.getResourceId(),
            XID.generateXID(request.getTransactionId()), request.getLockKey()));
    }

    // 分支事务提交
    @Override
    public BranchStatus branchCommit(String xid, long branchId, String resourceId, String applicationData)
        throws TransactionException {
        try {
            BranchCommitRequest
                request = new BranchCommitRequest();
            request.setXid(xid);
            request.setBranchId(branchId);
            request.setResourceId(resourceId);
            request.setApplicationData(applicationData);

            GlobalSession globalSession = SessionHolder.findGlobalSession(XID.getTransactionId(xid));
            BranchSession branchSession = globalSession.getBranch(branchId);

            BranchCommitResponse response = (BranchCommitResponse)messageSender.sendSyncRequest(resourceId,
                branchSession.getClientId(), request);
            return response.getBranchStatus();
        } catch (IOException e) {
            throw new TransactionException(FailedToSendBranchCommitRequest, branchId + "/" + xid, e);
        } catch (TimeoutException e) {
            throw new TransactionException(FailedToSendBranchCommitRequest, branchId + "/" + xid, e);
        }
    }

    // 分支事务回滚
    @Override
    public BranchStatus branchRollback(String xid, long branchId, String resourceId, String applicationData)
        throws TransactionException {
        try {
            BranchRollbackRequest
                request = new BranchRollbackRequest();
            request.setXid(xid);
            request.setBranchId(branchId);
            request.setResourceId(resourceId);
            request.setApplicationData(applicationData);

            GlobalSession globalSession = SessionHolder.findGlobalSession(XID.getTransactionId(xid));
            BranchSession branchSession = globalSession.getBranch(branchId);

            BranchRollbackResponse response = (BranchRollbackResponse)messageSender.sendSyncRequest(resourceId,
                branchSession.getClientId(), request);
            return response.getBranchStatus();
        } catch (IOException e) {
            throw new TransactionException(FailedToSendBranchRollbackRequest, branchId + "/" + xid, e);
        } catch (TimeoutException e) {
            throw new TransactionException(FailedToSendBranchRollbackRequest, branchId + "/" + xid, e);
        }
    }

    private void timeoutCheck() throws TransactionException {
        Collection<GlobalSession> allSessions = SessionHolder.getRootSessionManager().allSessions();
        if (allSessions.size() > 0 && LOGGER.isDebugEnabled()) {
            LOGGER.debug("Transaction Timeout Check Begin: " + allSessions.size());
        }
        for (GlobalSession globalSession : allSessions) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(globalSession.getTransactionId() + " " + globalSession.getStatus() + " " +
                    globalSession.getBeginTime() + " " + globalSession.getTimeout());
            }

            if (globalSession.getStatus() != GlobalStatus.Begin || !globalSession.isTimeout()) {
                continue;
            }

            globalSession.close();
            globalSession.changeStatus(GlobalStatus.TimeoutRollbacking);
            LOGGER.info(
                "Global transaction[" + globalSession.getTransactionId() + "] is timeout and will be rolled back.");

            globalSession.addSessionLifecycleListener(SessionHolder.getRetryRollbackingSessionManager());
            SessionHolder.getRetryRollbackingSessionManager().addGlobalSession(globalSession);

        }
        if (allSessions.size() > 0 && LOGGER.isDebugEnabled()) {
            LOGGER.debug("Transaction Timeout Check End. ");
        }

    }

    private void handleRetryRollbacking() {
        Collection<GlobalSession> rollbackingSessions = SessionHolder.getRetryRollbackingSessionManager().allSessions();
        for (GlobalSession rollbackingSession : rollbackingSessions) {
            try {
                core.doGlobalRollback(rollbackingSession, true);
            } catch (TransactionException ex) {
                LOGGER.info(
                    "Failed to retry rollbacking [" + rollbackingSession.getTransactionId() + "] " + ex.getCode() + " "
                        + ex.getMessage());
            }
        }
    }

    private void handleRetryCommitting() {
        Collection<GlobalSession> committingSessions = SessionHolder.getRetryCommittingSessionManager().allSessions();
        for (GlobalSession committingSession : committingSessions) {
            try {
                core.doGlobalCommit(committingSession, true);
            } catch (TransactionException ex) {
                LOGGER.info(
                    "Failed to retry committing [" + committingSession.getTransactionId() + "] " + ex.getCode() + " "
                        + ex.getMessage());
            }
        }
    }

    private void handleAsyncCommitting() {
        Collection<GlobalSession> asyncCommittingSessions = SessionHolder.getAsyncCommittingSessionManager()
            .allSessions();
        for (GlobalSession asyncCommittingSession : asyncCommittingSessions) {
            try {
                core.doGlobalCommit(asyncCommittingSession, true);
            } catch (TransactionException ex) {
                LOGGER.info(
                    "Failed to async committing [" + asyncCommittingSession.getTransactionId() + "] " + ex.getCode()
                        + " " + ex.getMessage());
            }
        }
    }

    // 初始化处理4个周期任务的线程池
    private ScheduledThreadPoolExecutor retryRollbacking = new ScheduledThreadPoolExecutor(1,
        new NamedThreadFactory("RetryRollbacking", 1));

    private ScheduledThreadPoolExecutor retryCommitting = new ScheduledThreadPoolExecutor(1,
        new NamedThreadFactory("RetryCommitting", 1));

    private ScheduledThreadPoolExecutor asyncCommitting = new ScheduledThreadPoolExecutor(1,
        new NamedThreadFactory("AsyncCommitting", 1));

    private ScheduledThreadPoolExecutor timeoutCheck = new ScheduledThreadPoolExecutor(1,
        new NamedThreadFactory("TxTimeoutCheck", 1));

    /**
     * 初始化4个周期任务
     */
    public void init() {
        retryRollbacking.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    // 全局事务回滚
                    handleRetryRollbacking();
                } catch (Exception e) {
                    LOGGER.info("Exception retry rollbacking ... ", e);
                }

            }
        }, 0, 5, TimeUnit.MILLISECONDS);

        retryCommitting.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    // 全局事务提交
                    handleRetryCommitting();
                } catch (Exception e) {
                    LOGGER.info("Exception retry committing ... ", e);
                }

            }
        }, 0, 5, TimeUnit.MILLISECONDS);

        asyncCommitting.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    // 全局事务异步提交
                    handleAsyncCommitting();
                } catch (Exception e) {
                    LOGGER.info("Exception async committing ... ", e);
                }

            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        timeoutCheck.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    //全局事务超时检测
                    timeoutCheck();
                } catch (Exception e) {
                    LOGGER.info("Exception timeout checking ... ", e);
                }

            }
        }, 0, 2, TimeUnit.MILLISECONDS);

    }


    // 处理事务请求
    @Override
    public AbstractResultMessage onRequest(AbstractMessage request, RpcContext context) {
        if (!(request instanceof AbstractTransactionRequestToTC)) {
            throw new IllegalArgumentException();
        }
        AbstractTransactionRequestToTC transactionRequest = (AbstractTransactionRequestToTC)request;
        transactionRequest.setTCInboundHandler(this);
        //由具体的事务请求(如： :GlobalBeginRequest、GlobalCommitRequest )进行处理
        return transactionRequest.handle(context);
    }

    @Override
    public void onResponse(AbstractResultMessage response, RpcContext context) {
        if (!(response instanceof AbstractTransactionResponse)) {
            throw new IllegalArgumentException();
        }

    }
}
