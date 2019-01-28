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

package com.alibaba.fescar.server;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.alibaba.fescar.common.XID;
import com.alibaba.fescar.common.util.NetUtil;
import com.alibaba.fescar.core.rpc.netty.RpcServer;
import com.alibaba.fescar.server.coordinator.DefaultCoordinator;
import com.alibaba.fescar.server.session.SessionHolder;

public class Server {

    private static final ThreadPoolExecutor WORKING_THREADS = new ThreadPoolExecutor(100, 500, 500, TimeUnit.SECONDS,
            new LinkedBlockingQueue(20000), new ThreadPoolExecutor.CallerRunsPolicy());

    public static void main(String[] args) throws IOException {
        //1、创建 一个netty 服务端
        RpcServer rpcServer = new RpcServer(WORKING_THREADS);
        
        int port = 8091;
        if (args.length == 0) {
            rpcServer.setListenPort(port);
        }

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Usage: sh fescar-server.sh $LISTEN_PORT $PATH_FOR_PERSISTENT_DATA");
                System.exit(0);
            }
            rpcServer.setListenPort(port);
        }
        
        String dataDir = null;
        if (args.length > 1) {
            dataDir = args[1];
        }
        //根据路径初始化会话持有者
        SessionHolder.init(dataDir);

        //2、创建一个默认的事务协调器
        DefaultCoordinator coordinator = new DefaultCoordinator(rpcServer);
        coordinator.init();
        /**
         里面会初始化四个周期任务：
         RetryRollbacking：
         RetryCommitting
         AsyncCommitting
         TxTimeoutCheck ： 全局事务超时检测
         */


        //3、给rpcServer设置一个TransactionMessageHandler（rpc 事务消息处理器）
        rpcServer.setHandler(new DefaultCoordinator(rpcServer));

        //4、初始化UUID（根据服务节点），后期用于生成 transactionId(在构建GlobalSession时)
        UUIDGenerator.init(1);

        //XID 生成器设置
        XID.setIpAddress(NetUtil.getLocalIp());
        XID.setPort(rpcServer.getListenPort());

        //5、初始化和启动 rpcServer（netty 服务端）
        rpcServer.init();

        System.exit(0);
    }
}
