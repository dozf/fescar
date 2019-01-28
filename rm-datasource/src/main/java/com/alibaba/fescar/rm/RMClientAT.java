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

package com.alibaba.fescar.rm;

import com.alibaba.fescar.core.rpc.netty.RmMessageListener;
import com.alibaba.fescar.core.rpc.netty.RmRpcClient;
import com.alibaba.fescar.rm.datasource.AsyncWorker;
import com.alibaba.fescar.rm.datasource.DataSourceManager;

/**
 * AT模式 RM客户端
 */
public class RMClientAT {

    public static void init(String applicationId, String transactionServiceGroup) {
        //创建 一个RM 的netty 客户端
        RmRpcClient rmRpcClient = RmRpcClient.getInstance(applicationId, transactionServiceGroup);
        //异步工作者
        AsyncWorker asyncWorker = new AsyncWorker();
        asyncWorker.init();
        // 初始化数据库管理器
        DataSourceManager.init(asyncWorker);
        rmRpcClient.setResourceManager(DataSourceManager.get());
        // 设置RmMessageListener(使用RMHandlerAT 作为AT模式的RM处理器)，接收处理TC发送的消息
        rmRpcClient.setClientMessageListener(new RmMessageListener(new RMHandlerAT()));
        rmRpcClient.init();
    }
}
