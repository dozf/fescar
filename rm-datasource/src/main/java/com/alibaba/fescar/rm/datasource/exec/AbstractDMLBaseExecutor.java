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

package com.alibaba.fescar.rm.datasource.exec;

import java.sql.SQLException;
import java.sql.Statement;

import com.alibaba.fescar.rm.datasource.ConnectionProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fescar.rm.datasource.AbstractConnectionProxy;
import com.alibaba.fescar.rm.datasource.StatementProxy;
import com.alibaba.fescar.rm.datasource.sql.SQLRecognizer;
import com.alibaba.fescar.rm.datasource.sql.struct.TableRecords;

public abstract class AbstractDMLBaseExecutor<T, S extends Statement> extends BaseTransactionalExecutor<T, S> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractDMLBaseExecutor.class);
    
    public AbstractDMLBaseExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback, SQLRecognizer sqlRecognizer) {
        super(statementProxy, statementCallback, sqlRecognizer);
    }

    @Override
    public T doExecute(Object... args) throws Throwable {
        //获取代理连接
        AbstractConnectionProxy connectionProxy = statementProxy.getConnectionProxy();
        if (connectionProxy.getAutoCommit()) {
            // 按自动提交方式执行SQL
            return executeAutoCommitTrue(args);
        } else {
            // 按非自动提交方式执行SQL
            return executeAutoCommitFalse(args);
        }
    }

    protected T executeAutoCommitFalse(Object[] args) throws Throwable {
        // 构建sql 执行前的镜像
        TableRecords beforeImage = beforeImage();
        // 执行sql
        T result = statementCallback.execute(statementProxy.getTargetStatement(), args);
        // 构建sql 执行后的镜像
        TableRecords afterImage = afterImage(beforeImage);
        // 解析成undolog,并把内容插入undo_log表， 详见：com.alibaba.fescar.rm.datasource.ConnectionProxy.prepareUndoLog()
        statementProxy.getConnectionProxy().prepareUndoLog(sqlRecognizer.getSQLType(), sqlRecognizer.getTableName(), beforeImage, afterImage);
        return result;
    }

    protected T executeAutoCommitTrue(Object[] args) throws Throwable {
        T result = null;
        AbstractConnectionProxy connectionProxy = statementProxy.getConnectionProxy();
        LockRetryController lockRetryController = new LockRetryController();
        try {
            //设置成不自动提交
            connectionProxy.setAutoCommit(false);
            while (true) {
                try {
                    // 按非自动提交方式执行SQL
                    result = executeAutoCommitFalse(args);
                    /**
                     * 提交本地事务
                     * @see  ConnectionProxy#commit()
                     */
                    connectionProxy.commit();
                    break;
                } catch (LockConflictException lockConflict) {
                    lockRetryController.sleep(lockConflict);
                }
            }

        } catch (Exception e) {
            // when exception occur in finally,this exception will lost, so just print it here
            LOGGER.error("exception occur", e);
            throw e;
        } 
        finally {
            //设置成自动提交
            connectionProxy.setAutoCommit(true);
        }
        return result;
    }

    protected abstract TableRecords beforeImage() throws SQLException;

    protected abstract TableRecords afterImage(TableRecords beforeImage) throws SQLException;

}
