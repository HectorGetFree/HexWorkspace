#!/usr/bin/env python3
"""Broaden Java Notes query draft coverage without promoting it to a test split."""
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET_ROOT = ROOT / "dataset-v2-java-notes-v1-draft"


# Each heading fragment is resolved against exactly one frozen canonical chunk.
EXTRA_SPECS = [
    ("JDK、JRE 与 JVM 在开发、编译和运行阶段各自负责什么？", "java-note-4baadf694f3e", "JVM JDK JRE的关系", "single_hop_semantic", "EASY"),
    ("单例对象为什么可能被反序列化重新创建，笔记给出的防护思路是什么？", "java-note-f9e91a5323a8", "防止反序列化破坏单例", "troubleshooting", "HARD"),
    ("Stream 流适合怎样组织集合处理，和直接循环的关注点有什么不同？", "java-note-f9e91a5323a8", "Stream流", "paraphrase", "MEDIUM"),
    ("Java Web 页面中 CSS 有哪些引入方式，什么时候适合使用各自方式？", "java-note-efa8e32e20e3", "CSS引入方式", "single_hop_semantic", "EASY"),
    ("CSS 选择器如何按元素、类或层级定位页面元素？", "java-note-efa8e32e20e3", "CSS选择器", "single_hop_semantic", "MEDIUM"),
    ("两数之和题在笔记中怎样利用哈希结构避免双重循环？", "java-note-7458129a7629", "1 两数之和", "scenario", "MEDIUM"),
    ("求最长无重复子串时，滑动窗口怎样移动边界？", "java-note-7458129a7629", "无重复字符的最长子串", "troubleshooting", "HARD"),
    ("接雨水问题为什么需要同时考虑左右边界高度？", "java-note-24fa1a7c50cb", "接雨水", "single_hop_semantic", "HARD"),
    ("字母异位词分组时，如何构造可以归并同一组的 key？", "java-note-24fa1a7c50cb", "字母异位词分组", "scenario", "MEDIUM"),
    ("使用 MQ 时，哪些业务条件下反而不应为了异步而引入消息队列？", "java-note-7b4ad11852de", "使用MQ的注意事项", "hard_negative", "MEDIUM"),
    ("建表时命名、主键和字段类型方面有哪些基础规范？", "java-note-42183495e6a8", "建表规范", "single_hop_semantic", "MEDIUM"),
    ("MySQL 的 JSON 字段需要按路径筛选时，笔记给出了怎样的查询思路？", "java-note-42183495e6a8", "语句操作 / 查询", "scenario", "MEDIUM"),
    ("HashMap 在没有碰撞与发生碰撞时，查询复杂度为什么不同？", "java-note-fcef2905a0a7", "HashMap复杂度分析 / 查询复杂度", "single_hop_semantic", "MEDIUM"),
    ("动态规划题目通常怎样识别状态、选择和转移关系？", "java-note-fcef2905a0a7", "动态规划", "paraphrase", "HARD"),
    ("Java 中方法重载与方法重写分别依赖什么判定条件？", "java-note-259bfe8bfb61", "方法重载", "single_hop_semantic", "MEDIUM"),
    ("Record 适合表达什么类型的数据对象，与普通可变类有什么区别？", "java-note-259bfe8bfb61", "不可变数据类型Record", "single_hop_semantic", "MEDIUM"),
    ("AQS 在 Java 并发工具中承担什么同步基础职责？", "java-note-b0955cc03329", "AQS介绍", "single_hop_semantic", "HARD"),
    ("停止一个正在运行的线程时，interrupt 的语义和直接强杀有什么差别？", "java-note-b0955cc03329", "如何停止一个线程的运行", "troubleshooting", "MEDIUM"),
    ("堆和栈在 JVM 内存模型中分别保存什么，生命周期有什么差异？", "java-note-900973e0765a", "堆和栈有什么区别", "single_hop_semantic", "MEDIUM"),
    ("双亲委派模型为什么能避免同名核心类被随意替换？", "java-note-900973e0765a", "类加载器", "paraphrase", "HARD"),
    ("Java 为什么可以跨平台，JVM、字节码与不同操作系统之间怎样衔接？", "java-note-4baadf694f3e", "跨平台", "single_hop_semantic", "MEDIUM"),
    ("Java 反射能够解决哪些运行期访问类型信息的场景？", "java-note-4baadf694f3e", "什么是反射", "scenario", "MEDIUM"),
    ("MySQL 如何通过唯一约束或其他手段避免重复插入？", "java-note-4d77a4b70759", "避免重复插入", "troubleshooting", "MEDIUM"),
    ("char 与 varchar 在存储方式和适用场景上有什么区别？", "java-note-4d77a4b70759", "char和varchar", "single_hop_semantic", "MEDIUM"),
    ("Redis 的 ZSet 为什么适合排行榜，score 如何参与查询和排序？", "java-note-64cac3a1adbe", "ZSet由哪些命令", "scenario", "MEDIUM"),
    ("Redis 跳表为什么适合作为有序集合的一种底层结构？", "java-note-64cac3a1adbe", "为什么使用SkipList而不是B+树等类似的结构", "paraphrase", "HARD"),
    ("Spring 的 IoC、DI 与 AOP 分别解决什么问题，彼此如何配合？", "java-note-3c440933e17d", "Spring生态体系结构", "multi_hop", "HARD"),
    ("切点、连接点、通知和切面在 AOP 中分别是什么概念？", "java-note-3c440933e17d", "介绍一下Spring的AOP", "single_hop_semantic", "HARD"),
    ("db-router 路由组件希望解决哪类多数据源或分库访问问题？", "java-note-190fbdf52471", "需求分析", "scenario", "MEDIUM"),
    ("谷粒商城的微服务工程中，OpenFeign 是怎样引入并发起远程调用的？", "java-note-10b7dc6a7f81", "OpenFeign引入方式 + 远程调用步骤", "scenario", "MEDIUM"),
    ("配置中心的多配置集加载顺序会如何影响最终生效配置？", "java-note-10b7dc6a7f81", "配置中心详解 / **加载多配置集**", "troubleshooting", "HARD"),
    ("项目使用读写分离时，主库和从库分别适合承载哪些请求？", "java-note-2752e1b09d5f", "读写分离 / 一主一从读写分离", "scenario", "MEDIUM"),
    ("Spring Boot 需要接入缓存时，笔记中的第三方技术整合入口在哪里？", "java-note-b491ebe86ed1", "整合第三方技术 / 缓存", "scenario", "MEDIUM"),
    ("RocketMQ 消费失败后为什么要关注重试导致的阻塞，顺序消息又有什么额外风险？", "java-note-7418ccec12c8", "消息重试", "troubleshooting", "HARD"),
    ("Linux 软链接与普通文件路径之间是什么关系，ls 常用选项怎样组合排查目录？", "java-note-46f42b408028", "常见命令", "single_hop_semantic", "EASY"),
    ("读多写少的共享数据为什么可以考虑 ReentrantReadWriteLock，而不是所有操作抢同一把互斥锁？", "java-note-760bce499356", "ReentrantReadWriteLock", "scenario", "HARD"),
    ("StampedLock 相比读写锁在乐观读场景中提供了什么思路，又应注意什么校验？", "java-note-760bce499356", "StampedLock", "single_hop_semantic", "HARD"),
    ("G1 垃圾回收器的分区思路与传统分代收集器相比有什么特点？", "java-note-86e0e960dc57", "G1垃圾回收器", "single_hop_semantic", "HARD"),
    ("类加载器的双亲委派模型如何工作，为什么它有助于避免核心类冲突？", "java-note-86e0e960dc57", "类加载器", "multi_hop", "HARD"),
    ("创建联合索引时为什么要关注字段顺序，以及它会如何影响查询？", "java-note-4d77a4b70759", "创建联合索引时要注意什么", "troubleshooting", "HARD"),
    ("哪些常见写法会让本应可用的 MySQL 索引失效？", "java-note-4d77a4b70759", "索引失效情况", "troubleshooting", "HARD"),
    ("MySQL 默认隔离级别下仍可能出现幻读吗，笔记如何解释和处理？", "java-note-4d77a4b70759", "默认隔离级别下的幻读", "hard_negative", "HARD"),
    ("为什么数据库更新不能只依赖直接修改 B+ 树，还需要写入 Redo Log？", "java-note-4d77a4b70759", "为什么要写入RedoLog，而不是直接写入B+树", "paraphrase", "HARD"),
    ("Redis 分布式锁适合哪些跨实例互斥场景，获取和释放时各有什么关键约束？", "java-note-64cac3a1adbe", "Redis分布式锁的实现原理，什么场景下用到分布式锁", "scenario", "HARD"),
    ("缓存雪崩、缓存击穿和缓存穿透的差异是什么，分别应怎样防护？", "java-note-64cac3a1adbe", "缓存雪崩、击穿、穿透", "multi_hop", "HARD"),
    ("热点 key 造成单点压力时，如何识别并采取缓解措施？", "java-note-64cac3a1adbe", "如何解决热Key问题", "troubleshooting", "HARD"),
    ("MyBatis 多对一或一对多映射中，延迟加载适合解决什么问题？", "java-note-15b31b613328", "映射延迟加载", "scenario", "MEDIUM"),
    ("MyBatis-Plus 需要把复杂字段保存为 JSON 时，应该使用哪类类型处理器配置？", "java-note-995867bdfae5", "JSON处理器", "scenario", "MEDIUM"),
    ("Spring 通过哪些机制管理 Bean 的创建、依赖注入和生命周期扩展点？", "java-note-3c440933e17d", "Bean的生命周期", "multi_hop", "HARD"),
    ("为什么 Spring 的事务可能因同类 this 调用而失效？", "java-note-3c440933e17d", "使用this调用是否生效", "troubleshooting", "HARD"),
    ("Spring Boot 的 @ConfigurationProperties 与 @EnableConfigurationProperties 如何配合绑定配置？", "java-note-b491ebe86ed1", "@ConfigurationProperties", "single_hop_semantic", "MEDIUM"),
    ("SpringMVC 文件上传为什么必须是 POST，并且 form 的 enctype 应如何设置？", "java-note-1058b24f7d78", "文件上传与下载 / 上传", "scenario", "MEDIUM"),
    ("RocketMQ 的事务消息在本地事务与消息投递之间解决了什么一致性问题？", "java-note-7418ccec12c8", "事务消息", "single_hop_semantic", "HARD"),
    ("XXL-Job 的任务处理器如何通过注解注册，任务内部又应如何调用业务逻辑？", "java-note-980158bbfaef", "XXL_Job", "scenario", "MEDIUM"),
    ("Git 远程仓库地址或名称变化时，应如何查看、添加或重命名 remote？", "java-note-0ad95f352160", "远程操作", "single_hop_semantic", "EASY"),
    ("两数之和以外，三数之和如何通过排序和双指针降低枚举成本？", "java-note-24fa1a7c50cb", "三数之和", "scenario", "HARD"),
    ("Java 泛型主要怎样在编译期限制集合或 API 的类型错误？", "java-note-4baadf694f3e", "什么是泛型", "single_hop_semantic", "MEDIUM"),
    ("Spring Cloud Gateway 处理跨域时，配置应该落在路由、过滤器还是全局跨域层？", "java-note-57c78ff47abe", "网关跨域配置", "troubleshooting", "HARD"),
    ("Elasticsearch 全文检索与精确查询在 DSL 选择和匹配语义上有什么差别？", "java-note-57c78ff47abe", "DSL查询文档 / 精确查询", "multi_hop", "HARD"),
    ("Spring 中 IoC、DI 与控制反转之间分别是什么关系，常见的依赖注入形式有哪些？", "java-note-f57f3f3f9ae0", "导言", "single_hop_semantic", "MEDIUM"),
    ("为什么 JDK 动态代理只能代理接口，而 CGLIB 可以代理类？两者的生成方式有什么差异？", "java-note-f57f3f3f9ae0", "Gof之代理模式 / 动态代理", "multi_hop", "HARD"),
    ("Spring 声明式事务的 timeout 与 readOnly 属性各自约束什么，哪些代码会被 timeout 计入？", "java-note-f57f3f3f9ae0", "Spring对事务的支持 / 基于注解", "scenario", "HARD"),
]


def main() -> None:
    draft_path = DATASET_ROOT / "cases" / "draft.jsonl"
    base = [json.loads(line) for line in draft_path.read_text(encoding="utf-8").splitlines() if line]
    chunks = [json.loads(line) for line in (DATASET_ROOT / "corpus" / "chunks.jsonl").read_text(encoding="utf-8").splitlines() if line]
    by_document = {}
    for chunk in chunks:
        by_document.setdefault(chunk["documentId"], []).append(chunk)

    extras = []
    for offset, (query, document_id, heading_fragment, query_type, difficulty) in enumerate(EXTRA_SPECS, start=len(base) + 1):
        matches = [
            chunk for chunk in by_document.get(document_id, [])
            if heading_fragment.lower() in " / ".join(chunk["headingPath"]).lower()
        ]
        if len(matches) != 1:
            raise ValueError(f"expected one chunk for {document_id!r} / {heading_fragment!r}, got {len(matches)}")
        extras.append({
            "queryId": f"java-notes-draft-{offset:03d}",
            "suite": "java_backend_notes",
            "query": query,
            "queryType": query_type,
            "difficulty": difficulty,
            "answerable": True,
            "candidateDocumentIds": [document_id],
            "candidateChunkIds": [matches[0]["chunkId"]],
            "generationMethod": "manual-source-reading",
            "reviewStatus": "UNREVIEWED",
            "notes": "Draft only: requires quote-level evidence review before scoring.",
        })
    output = DATASET_ROOT / "cases" / "draft-expanded.jsonl"
    output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in base + extras), encoding="utf-8")
    print(f"wrote {len(base) + len(extras)} unreviewed query candidates: {output}")


if __name__ == "__main__":
    main()
