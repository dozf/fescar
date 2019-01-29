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

package com.alibaba.fescar.tm.api;

import com.alibaba.fescar.core.exception.TransactionException;

/**
 * TM 处理分布式事务的核心
 * Template of executing business logic with a global transaction.
 * 使用全局事务执行业务逻辑的模板
 */
public class TransactionalTemplate {

    /**
     * Execute object.
     *
     * @param business the business
     * @return the object
     * @throws TransactionalExecutor.ExecutionException the execution exception
     */
    public Object execute(TransactionalExecutor business) throws TransactionalExecutor.ExecutionException {

        // 1. get or create a transaction
        /**
         * 获取或创建一个全局事务(默认是DefaultGlobalTransaction,初始化时 状态是GlobalStatus.UnKnown,角色是事务发起者[GlobalTransactionRole.Launcher],xid 是null ) ，
         * 并放入GlobalTransactionContext 上下文中
         *
         */
        GlobalTransaction tx = GlobalTransactionContext.getCurrentOrCreate();

        // 2. begin transaction
        try {
            /**
             * 调用DefaultGlobalTransaction开启全局事务 (由DefaultTransactionManager，通过TmRpcClient 发送GlobalBeginRequest 给TC ,同时会返回xid，格式："ip : port : tranId" ,如：172.20.21.167:8091:1785900)
             * GlobalTransaction 状态设置为GlobalStatus.Begin
             * xid 绑定到RootContext
             */
            tx.begin(business.timeout(), business.name());

        } catch (TransactionException txe) {
            throw new TransactionalExecutor.ExecutionException(tx, txe,
                TransactionalExecutor.Code.BeginFailure);

        }

        Object rs = null;
        try {

            // Do Your Business
            // 执行业务的原始方法，由于集成的是Dubbo,原始方法调用其他服务时会经过 TransactionPropagationFilter把xid 传递到其他服务。
            rs = business.execute();

        } catch (Throwable ex) {

            // 3. any business exception, rollback.
            try {
                //发起全局事务异常回滚
                tx.rollback();

                // 3.1 Successfully rolled back
                throw new TransactionalExecutor.ExecutionException(tx, TransactionalExecutor.Code.RollbackDone, ex);

            } catch (TransactionException txe) {
                // 3.2 Failed to rollback
                throw new TransactionalExecutor.ExecutionException(tx, txe,
                    TransactionalExecutor.Code.RollbackFailure, ex);

            }

        }

        // 4. everything is fine, commit.
        try {
            //全局事务提交
            tx.commit();

        } catch (TransactionException txe) {
            // 4.1 Failed to commit
            throw new TransactionalExecutor.ExecutionException(tx, txe,
                TransactionalExecutor.Code.CommitFailure);

        }
        return rs;
    }

}
