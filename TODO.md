1. logout 功能添加 -- 跳转到登陆页
2. JWT 自动续期 refreshToken + accessToken





1. ✅同一文件上传按钮幂等处理

优先级	问题	当前表现	建议
P0	RUNNING 任务会永久卡死	消费者抢占任务后，进程若在 MinIO 写入前崩溃，任务停在 RUNNING；扫描器只处理 RETRY_WAIT，后续不会恢复	为任务增加 running_lease_until；扫描超时 RUNNING 任务，重新置为 RETRY_WAIT
P0	Outbox 事件没有发布抢占	多实例都能查到同一条 PENDING Outbox，并同时发送 MQ	Outbox 增加 PUBLISHING、publish_lease_until，通过条件更新抢占后再投递
P1	无限重试	MinIO 路径错误、权限错误等永久失败会每分钟无限重试	设置 max_retry_count；达到上限后任务进入 FAILED，Document 进入可识别的失败状态
P1	staging 孤儿对象	先写 staging，再进入 MySQL 事务；进程若在两者之间崩溃，MySQL 无记录可追踪该对象	定时按 staging/ 前缀和保留期清理无引用对象
P1	staging_path 有冗余	Document 与 UploadTask 都保存 staging 路径；当前正式执行只读取 Task	若不做“无任务 CREATING 文档修复”，删除 Document.stagingPath；否则补齐对应恢复任务
P2	文件被完整读入内存两次	Controller 使用 file.getBytes()；消费者又 download() 为 byte[]，50MB 文件并发时内存压力明显	改为流式上传，或使用 MinIO 服务端 CopyObject
P2	正式对象 MIME 丢失	消费者写正式对象时固定使用 application/octet-stream	Task 保存 contentType，或由消费者读取 Document 的 contentType
P2	并发首次重试会短暂重复写 staging	两个请求都在查不到 Document 后先写 staging，数据库唯一键只在后面阻止重复 Document	当前最终结果正确，但浪费 I/O；后续可用“先创建 CREATING Document，再写 staging”的两阶段设计优化