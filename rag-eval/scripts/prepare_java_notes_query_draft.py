#!/usr/bin/env python3
"""Write source-backed but unreviewed Java Notes retrieval query candidates."""
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET_ROOT = ROOT / "dataset-v2-java-notes-v1-draft"


# These are manually authored from the frozen Markdown sections.  They are
# deliberately not benchmark cases yet: quote-level evidence review is a
# separate step before anything is copied into cases/test.jsonl.
SPECS = [
    ("JDBC 执行用户输入的查询时，为什么应该使用预编译语句而不是自己拼接 SQL？", "java-note-dd10cc3be902", "s0006", "troubleshooting", "MEDIUM"),
    ("执行 DDL、DML 和查询语句时，Statement 的两个执行方法分别该怎么选？", "java-note-dd10cc3be902", "s0004", "single_hop_semantic", "EASY"),
    ("ResultSet 遍历结果集时，next 方法除了移动游标还承担什么判断？", "java-note-dd10cc3be902", "s0005", "single_hop_semantic", "EASY"),
    ("数据库连接池为什么既要复用连接，又要回收长时间空闲的连接？", "java-note-dd10cc3be902", "s0007", "paraphrase", "MEDIUM"),
    ("直接调用一个 Thread 的 run 方法，为什么没有真正并发执行？", "java-note-760bce499356", "s0009", "troubleshooting", "EASY"),
    ("一个线程依赖另一个线程的计算结果，但最多只能等两秒，应使用什么等待方式？", "java-note-760bce499356", "s0013", "scenario", "MEDIUM"),
    ("sleep、wait、join 被 interrupt 后，线程的中断标记会怎样变化？", "java-note-760bce499356", "s0014", "single_hop_semantic", "MEDIUM"),
    ("为什么在同步代码中不建议使用已经过时的线程强制停止 API？", "java-note-760bce499356", "s0015", "hard_negative", "MEDIUM"),
    ("条件不满足时调用 wait 后线程会进入哪里，谁负责把它唤醒？", "java-note-760bce499356", "s0028", "single_hop_semantic", "MEDIUM"),
    ("为什么 CAS 看见值从 A 变回 A 仍可能有并发风险，笔记里用什么方式解决？", "java-note-760bce499356", "s0046", "troubleshooting", "HARD"),
    ("LongAdder 在高竞争累加时为什么通常比单个原子变量更有优势？", "java-note-760bce499356", "s0051", "paraphrase", "HARD"),
    ("调用 shutdown 关闭线程池后，已提交任务和新任务分别会怎样？", "java-note-760bce499356", "s0060", "single_hop_semantic", "MEDIUM"),
    ("程序计数器为什么是线程私有的，它保存的是什么类型的信息？", "java-note-86e0e960dc57", "s0002", "single_hop_semantic", "MEDIUM"),
    ("直接内存不是 JVM 堆的一部分时，通常依靠什么机制释放？", "java-note-86e0e960dc57", "s0008", "single_hop_semantic", "MEDIUM"),
    ("垃圾回收器如何判断一个对象已经不再可达，可以被回收？", "java-note-86e0e960dc57", "s0009", "single_hop_semantic", "MEDIUM"),
    ("为什么 JVM 要按新生代和老年代进行回收，而不是每次扫描所有对象？", "java-note-86e0e960dc57", "s0011", "paraphrase", "HARD"),
    ("线上 Full GC 频繁时，笔记建议从哪些 JVM 参数或现象开始排查？", "java-note-86e0e960dc57", "s0015", "troubleshooting", "HARD"),
    ("volatile 主要解决 Java 内存模型中的哪类可见性问题，不能单独保证什么？", "java-note-86e0e960dc57", "s0031", "single_hop_semantic", "MEDIUM"),
    ("SQL 中 DDL、DML、DQL、DCL 分别面向什么操作？", "java-note-2752e1b09d5f", "s0001", "exact_lexical", "EASY"),
    ("事务的 ACID 四个特性分别约束了什么？", "java-note-2752e1b09d5f", "s0019", "single_hop_semantic", "EASY"),
    ("两个事务同时读写时可能产生哪些典型并发问题？", "java-note-4d77a4b70759", "s0045", "single_hop_semantic", "MEDIUM"),
    ("选择索引时，B+ 树为何适合作为数据库索引结构？", "java-note-2752e1b09d5f", "s0025", "paraphrase", "HARD"),
    ("排查一条 SQL 为什么没有按预期走索引，笔记给出的分析入口是什么？", "java-note-2752e1b09d5f", "s0028", "troubleshooting", "MEDIUM"),
    ("order by 在什么情况下能利用索引，哪些情况会额外排序？", "java-note-2752e1b09d5f", "s0032", "scenario", "HARD"),
    ("InnoDB 的 MVCC 用哪些版本信息让读操作避免不必要的锁等待？", "java-note-2752e1b09d5f", "s0046", "single_hop_semantic", "HARD"),
    ("二进制日志、错误日志和慢查询日志分别适合排查什么问题？", "java-note-2752e1b09d5f", "s0050", "multi_hop", "HARD"),
    ("Redis 默认连接到哪个逻辑数据库，不同逻辑库之间的 key 是否共享？", "java-note-5755ee70cba3", "s0002", "single_hop_semantic", "EASY"),
    ("需要按分值排序并查询排名时，Redis 应优先使用哪种数据结构？", "java-note-5755ee70cba3", "s0012", "scenario", "MEDIUM"),
    ("短信登录为什么可以把验证码和登录态放到 Redis，key 一般如何组织？", "java-note-5755ee70cba3", "s0015", "scenario", "MEDIUM"),
    ("缓存穿透、缓存击穿和缓存雪崩分别是什么，笔记的缓存章节如何处理它们？", "java-note-5755ee70cba3", "s0016", "multi_hop", "HARD"),
    ("用 Redis 实现分布式锁时，如何避免自己释放掉别的实例持有的锁？", "java-note-5755ee70cba3", "s0018", "troubleshooting", "HARD"),
    ("附近商户检索为什么使用 GEO 数据结构，而不是普通字符串 key？", "java-note-5755ee70cba3", "s0027", "paraphrase", "MEDIUM"),
    ("连续签到统计为什么适合 Bitmap，而不是为每一天创建一条独立记录？", "java-note-5755ee70cba3", "s0029", "scenario", "MEDIUM"),
    ("PV 很大但只需要近似 UV 时，笔记推荐 Redis 的哪种结构？", "java-note-5755ee70cba3", "s0032", "single_hop_semantic", "MEDIUM"),
    ("MyBatis 的 #{} 与 ${} 在参数替换和 SQL 注入风险上有什么关键区别？", "java-note-15b31b613328", "s0021", "hard_negative", "MEDIUM"),
    ("SqlSession、SqlSessionFactory 与 Mapper 在生命周期上分别应如何使用？", "java-note-15b31b613328", "s0016", "single_hop_semantic", "HARD"),
    ("查询字段名与 Java 属性名不一致时，如何通过 resultMap 完成映射？", "java-note-15b31b613328", "s0034", "troubleshooting", "MEDIUM"),
    ("MyBatis 一级缓存和二级缓存的作用域有什么区别？", "java-note-15b31b613328", "s0042", "single_hop_semantic", "MEDIUM"),
    ("查询条件需要按输入动态拼装时，MyBatis 应使用哪一类动态 SQL 能力？", "java-note-15b31b613328", "s0036", "scenario", "MEDIUM"),
    ("MyBatis-Plus 的条件构造器适合解决什么类型的动态查询问题？", "java-note-995867bdfae5", "s0004", "single_hop_semantic", "MEDIUM"),
    ("不物理删除历史数据时，MyBatis-Plus 如何实现逻辑删除？", "java-note-995867bdfae5", "s0009", "scenario", "MEDIUM"),
    ("MyBatis-Plus 分页查询需要开启什么插件能力？", "java-note-995867bdfae5", "s0013", "exact_lexical", "EASY"),
    ("服务拆分时，为什么不应只按技术层或数据库表机械拆服务？", "java-note-57c78ff47abe", "s0005", "paraphrase", "HARD"),
    ("Eureka 中服务实例如何注册、续约，以及消费者如何获取实例列表？", "java-note-57c78ff47abe", "s0007", "multi_hop", "HARD"),
    ("Nacos 的 namespace 解决的是什么隔离问题，和服务分组有什么关系？", "java-note-57c78ff47abe", "s0018", "single_hop_semantic", "MEDIUM"),
    ("修改 Nacos 配置后应用如何获得热更新，哪些配置前提需要满足？", "java-note-57c78ff47abe", "s0022", "troubleshooting", "HARD"),
    ("Feign 调用慢时，笔记中有哪些连接、日志或客户端层面的优化方向？", "java-note-57c78ff47abe", "s0027", "troubleshooting", "HARD"),
    ("网关路由如何根据请求特征匹配，下游前后又可以在哪些位置做过滤？", "java-note-57c78ff47abe", "s0032", "multi_hop", "HARD"),
    ("Elasticsearch 为什么同时存在正向索引和倒排索引，它们各服务于什么访问方式？", "java-note-57c78ff47abe", "s0043", "single_hop_semantic", "HARD"),
    ("Sentinel 的热点参数限流适合保护哪种请求模式？", "java-note-57c78ff47abe", "s0088", "scenario", "MEDIUM"),
    ("Spring Boot 的 starter 与 parent 分别在依赖管理中承担什么职责？", "java-note-b491ebe86ed1", "s0004", "single_hop_semantic", "MEDIUM"),
    ("开发配置与上线配置发生冲突时，Spring Boot 如何通过配置覆盖保留未覆盖的原有属性？", "java-note-b491ebe86ed1", "s0036", "troubleshooting", "MEDIUM"),
    ("多环境部署时，Spring Boot 如何选择并切换 profile 配置？", "java-note-b491ebe86ed1", "s0040", "scenario", "MEDIUM"),
    ("为什么 Spring Boot 的业务层测试常常需要事务回滚，如何避免污染测试数据？", "java-note-b491ebe86ed1", "s0060", "troubleshooting", "MEDIUM"),
    ("自动配置并非无条件创建 Bean，Spring Boot 用什么机制控制 Bean 是否加载？", "java-note-b491ebe86ed1", "s0070", "single_hop_semantic", "HARD"),
    ("@Transactional 的 isolation 属性解决什么问题，传播行为又解决什么问题？", "java-note-7f4e7bd6ab34", "s0002", "multi_hop", "HARD"),
    ("不使用注解时，如何通过 TransactionTemplate 组织编程式事务？", "java-note-7f4e7bd6ab34", "s0004", "scenario", "MEDIUM"),
    ("Spring 条件装配中，如何根据环境或配置决定一个 Bean 是否实例化？", "java-note-8c3f1c10e633", "s0007", "scenario", "MEDIUM"),
    ("原型作用域 Bean 与单例 Bean 在创建和复用行为上有什么差别？", "java-note-8c3f1c10e633", "s0009", "single_hop_semantic", "MEDIUM"),
    ("同一路径需要按 HTTP 方法分派到不同 Controller 时，RequestMapping 应如何声明？", "java-note-1058b24f7d78", "s0010", "scenario", "MEDIUM"),
    ("Controller 如何接收 query 参数、请求头和 Cookie，各自对应什么注解？", "java-note-1058b24f7d78", "s0016", "multi_hop", "MEDIUM"),
    ("forward 与 redirect 在地址栏、请求次数和数据传递上有什么区别？", "java-note-1058b24f7d78", "s0022", "single_hop_semantic", "MEDIUM"),
    ("为什么静态资源请求可能被 DispatcherServlet 拦截，SpringMVC 如何放行？", "java-note-1058b24f7d78", "s0024", "troubleshooting", "HARD"),
    ("@RestController 为什么等价于 Controller 加上 ResponseBody？", "java-note-1058b24f7d78", "s0029", "exact_lexical", "EASY"),
    ("SpringMVC 接收 PUT、DELETE 表单请求时，为什么需要额外的过滤器并注意其顺序？", "java-note-1058b24f7d78", "s0026", "troubleshooting", "HARD"),
    ("多个 SpringMVC 拦截器的 preHandle、postHandle、afterCompletion 分别按什么顺序执行？", "java-note-1058b24f7d78", "s0040", "single_hop_semantic", "HARD"),
    ("消息队列为什么能解耦、异步提速和削峰，但又会引入哪些代价？", "java-note-7418ccec12c8", "s0001", "multi_hop", "MEDIUM"),
    ("RocketMQ 的 Producer、Broker、Consumer、NameServer、Topic 分别是什么角色？", "java-note-7418ccec12c8", "s0004", "single_hop_semantic", "MEDIUM"),
    ("同一消费者组与不同消费者组订阅同一个 Topic 时，负载均衡和广播语义有什么不同？", "java-note-7418ccec12c8", "s0009", "multi_hop", "HARD"),
    ("为什么顺序消息要让同一业务键落到同一个队列，普通轮询投递会带来什么问题？", "java-note-7418ccec12c8", "s0013", "troubleshooting", "HARD"),
    ("RocketMQ 为什么要求消费者具备幂等性，哪些情况会导致重复消费？", "java-note-7418ccec12c8", "s0024", "single_hop_semantic", "HARD"),
    ("Broker、Producer、Consumer 启动时分别怎样通过 NameServer 建立和获取路由信息？", "java-note-7418ccec12c8", "s0017", "multi_hop", "HARD"),
    ("git stash 保存的是什么状态，恢复后工作区会发生什么？", "java-note-0ad95f352160", "s0005", "single_hop_semantic", "MEDIUM"),
    ("git reset 的 soft、mixed、hard、keep 模式分别会如何影响工作区和暂存区？", "java-note-0ad95f352160", "s0008", "single_hop_semantic", "HARD"),
    ("团队协作中为什么不应直接在 main 分支开发，分支命名和合并有哪些约束？", "java-note-0ad95f352160", "s0009", "scenario", "MEDIUM"),
    ("提交信息里的 feat、fix、docs、style 分别表示哪类变更？", "java-note-0ad95f352160", "s0010", "exact_lexical", "EASY"),
    ("哪些后台场景适合用定时任务做补偿或扫描，而不是把所有工作放到同步请求里？", "java-note-980158bbfaef", "s0001", "scenario", "MEDIUM"),
    ("简单单机任务与需要分布式管理的任务，笔记建议分别选择哪些调度方案？", "java-note-980158bbfaef", "s0002", "scenario", "MEDIUM"),
    ("XXL-Job、Quartz 和 Spring Schedule 在工程中应怎样分层和注册？", "java-note-980158bbfaef", "s0003", "multi_hop", "HARD"),
    ("WebSocket 相比轮询、长轮询等消息推送方式，解决了什么通信问题？", "java-note-2dad73701c4d", "s0001", "paraphrase", "MEDIUM"),
    ("常用 ls 参数中，怎样同时查看隐藏文件、详细信息并按时间排序？", "java-note-46f42b408028", "s0001", "scenario", "EASY"),
]


def main() -> None:
    chunks = {
        row["chunkId"]
        for row in (json.loads(line) for line in (DATASET_ROOT / "corpus" / "chunks.jsonl").read_text(encoding="utf-8").splitlines())
        if row
    }
    rows = []
    for index, (query, document_id, section, query_type, difficulty) in enumerate(SPECS, start=1):
        chunk_id = f"{document_id}#{section}"
        if chunk_id not in chunks:
            raise ValueError(f"unknown target chunk: {chunk_id}")
        rows.append({
            "queryId": f"java-notes-draft-{index:03d}",
            "suite": "java_backend_notes",
            "query": query,
            "queryType": query_type,
            "difficulty": difficulty,
            "answerable": True,
            "candidateDocumentIds": [document_id],
            "candidateChunkIds": [chunk_id],
            "generationMethod": "manual-source-reading",
            "reviewStatus": "UNREVIEWED",
            "notes": "Draft only: requires quote-level evidence review before scoring.",
        })
    cases_dir = DATASET_ROOT / "cases"
    cases_dir.mkdir(exist_ok=True)
    (cases_dir / "draft.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8"
    )
    dataset_path = DATASET_ROOT / "dataset.json"
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    dataset["queryStatus"] = "UNREVIEWED_DRAFT_CREATED"
    dataset["draftQueryCount"] = len(rows)
    dataset_path.write_text(json.dumps(dataset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(rows)} unreviewed query candidates: {cases_dir / 'draft.jsonl'}")


if __name__ == "__main__":
    main()
