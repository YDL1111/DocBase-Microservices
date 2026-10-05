# Java 线程池与并发配置

应用使用独立的 HTTP 请求线程、定时调度线程和 Chat 流式工作线程。以下参数是单机部署的初始值，需要根据容器 CPU、数据库连接数、P95 延迟和积压情况压测调整。

## 当前配置

| 用途 | 服务 | 初始值 | 配置环境变量 |
| --- | --- | --- | --- |
| Tomcat 请求线程 | Knowledge、Ingest、Chat | 最少空闲 8，最大 64 | HTTP_MIN_SPARE_THREADS、HTTP_MAX_THREADS |
| HTTP 连接及等待连接 | 上述服务 | 最大连接 256，等待连接 64 | HTTP_MAX_CONNECTIONS、HTTP_ACCEPT_COUNT |
| 定时任务 | Knowledge | 2 个线程 | KNOWLEDGE_SCHEDULER_THREADS |
| 定时任务 | Ingest | 4 个线程 | INGEST_SCHEDULER_THREADS |
| Chat 流式回调 | Chat | 核心 4，最大 8，队列 128，空闲存活 60 秒 | CHAT_STREAM_CORE_THREADS、CHAT_STREAM_MAX_THREADS、CHAT_STREAM_QUEUE_CAPACITY、CHAT_STREAM_KEEP_ALIVE_SECONDS |

Ingest 的任务扫描、重试扫描及两个 Outbox 发布器可独立执行，避免一个发布器等待 Broker Confirm 时阻塞全部定时工作。单个 fixed-delay 定时任务仍按完成后的间隔执行；跨实例重复扫描继续由数据库条件更新防重。

Chat 使用 Spring 管理的 ThreadPoolTaskExecutor，并将 WebClient 响应通过 publishOn 切换至该线程池。正常响应解析、SSE 写出和终态持久化不再占用 Netty 网络事件线程；每条流的预取为 16 个上游数据块，保持流内顺序。取消回调仍可能由取消发起线程执行，不能声称所有清理路径都在工作线程。

## Chat 对应的七个参数

- corePoolSize：4。
- maximumPoolSize：8。
- keepAliveTime：60。
- unit：秒，由 Spring 的 setKeepAliveSeconds 指定。
- workQueue：容量 128 的有界队列。
- threadFactory：由 Spring 创建，名称前缀 chat-stream-。
- handler：AbortPolicy。满载时明确拒绝，不能通过 CallerRunsPolicy 把阻塞任务退回 Netty 线程，也不能静默丢弃。

线程池先使用核心线程，然后入队，队列满后才扩展至最大线程数。队列容量是待调度工作数，不等于允许的会话数；publishOn 的工作单元也不是一个完整模型请求。

线程池正常关闭等待最长 15 秒。RabbitMQ 消费并发仍保持 1 至 2、prefetch 为 1；调度线程数与消息消费者数量是不同维度。

## 配置思路

本地 BGE-M3 推理和大量文本计算偏 CPU 密集，初始并发应接近容器可用核数，并考虑模型底层已有多线程。MinIO、数据库和远端 LLM 调用主要涉及 IO 等待，允许较高并发，但必须受连接池、远端限额和内存约束。Java 数据库连接池最大为 5，因此不能通过无限增加线程提升数据库吞吐。

观察 CPU 热点、线程栈、连接等待、队列长度、拒绝数和 P95 延迟来定位瓶颈。队列持续增长说明到达速度超过处理能力，应该控制流量或扩容，扩大队列只能延后失败。

上述变量已在 Compose 中传入对应容器，.env.example 提供默认值；在根目录 .env 覆盖后重新创建对应服务即可生效。也可以通过 Nacos 对应属性或 Spring 外部配置覆盖，存在多个配置来源时应核实实际值。

本轮仅调整 Java 服务，Python 默认线程池及模型计算并发需另行根据实测资源控制。Gateway 使用 Reactor Netty，不套用 Tomcat 配置。
