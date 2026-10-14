"""固定文档集合，对照 Workspace get_document 的串行与并发耗时。"""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import random
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import httpx

sys.path.insert(0, '/Users/hector/dev/hexgent')
from hexgent.integration.capability import WorkspaceCapabilityClient


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--csv', default='/Users/hector/Downloads/doc.csv')
    parser.add_argument('--count', type=int, choices=(8, 16), default=8)
    parser.add_argument('--seed', type=int, default=20261013)
    parser.add_argument('--repeats', type=int, default=5)
    parser.add_argument('--url', default='http://127.0.0.1:8098')
    parser.add_argument('--workspace', type=int, default=14)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--prepare-only', action='store_true', help='仅选择文档，不连接服务')
    args = parser.parse_args()
    if args.repeats < 1:
        parser.error('repeats 必须大于零')
    with open(args.csv, newline='', encoding='utf-8-sig') as source:
        rows = list(csv.reader(source))
    documents = []
    for row in rows:
        if len(row) != 10:
            raise ValueError('CSV 必须使用约定的十列查询结果，不含表头')
        if row[5] == '2' and row[9] == '1' and row[2]:
            documents.append(dict(code=row[0], documentId=int(row[1]),
                                  versionId=int(row[2]), ownerUserId=int(row[4]),
                                  contentSize=int(row[8])))
    if len({d['documentId'] for d in documents}) != len(documents):
        raise ValueError('CSV 中有重复文档')
    # 1. 固定随机顺序；16 篇实验的前 8 篇与 8 篇实验相同。
    random.Random(args.seed).shuffle(documents)
    selected = documents[:args.count]
    if len(selected) != args.count:
        raise ValueError('符合条件的文档不足')
    output = args.output or Path('/Users/hector/dev/hex-workspace/.local/get-document-bench') / (
        time.strftime('%Y%m%d-%H%M%S') + '-' + uuid4().hex[:6])
    output.mkdir(parents=True, exist_ok=False)
    report = dict(workspaceId=args.workspace, seed=args.seed, documents=selected,
                  startLine=1, lineCount=200, rounds=[], errors=[],
                  measurement='每批全部读取完成的墙钟耗时；不含 Run 创建、预热、取消')

    def save():
        (output / 'results.json').write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    save()
    print('选中文档：', ', '.join(d['code'] for d in selected), flush=True)
    print('结果目录：', output, flush=True)
    if args.prepare_only:
        return
    jwt = os.environ.get('WS_BENCH_JWT', '')
    token = os.environ.get('HEXGENT_WORKSPACE_CAPABILITY_TOKEN', '')
    if not jwt or not token:
        raise ValueError('请设置 WS_BENCH_JWT 和 HEXGENT_WORKSPACE_CAPABILITY_TOKEN')
    api = httpx.Client(base_url=args.url.rstrip('/'), timeout=30,
                       headers={'Authorization': 'Bearer ' + jwt})
    conversation_id = run_id = None

    def post(path, data):
        response = api.post(path, json={'data': data})
        response.raise_for_status()
        body = response.json()
        if body.get('code') != '0000':
            raise RuntimeError('WS 接口失败：' + str(body.get('code')) + ' ' + str(body.get('info', '')))
        return body['data']

    def read(document):
        call_id = 'bench-' + uuid4().hex
        started = time.perf_counter()
        result = WorkspaceCapabilityClient(base_url=args.url, token=token).invoke(
            run_id, call_id, 'get_document',
            dict(documentId=document['documentId'], versionId=document['versionId'],
                 startLine=1, lineCount=200))
        digest = hashlib.sha256(json.dumps(result.data, ensure_ascii=False,
            sort_keys=True).encode()).hexdigest() if result.status == 'SUCCEEDED' else None
        return dict(documentId=document['documentId'], callId=call_id,
                    elapsedMs=(time.perf_counter() - started) * 1000,
                    status=result.status, errorCode=result.error_code, digest=digest)

    try:
        # 2. 使用真实入口创建 Run；等待指令只用于维持实验窗口，不读取业务资料。
        conversation_id = post(f'/api/v1/workspaces/{args.workspace}/conversations',
                               {'title': 'get_document 并发对照实验'})['conversationId']
        report['conversationId'] = conversation_id
        save()
        instruction = ('这是外部只读性能实验的等待 Run。请立即用 bash 执行 sleep 90，'
                       '连续执行十次，每次等待完成再执行下一次。不要调用任何 Workspace 能力，'
                       '不要读取或修改文件。全部等待结束后回复 ok。外部脚本会提前取消此 Run。')
        run = post(f'/api/v1/conversations/{conversation_id}/runs',
                   dict(instruction=instruction, allowedTools='["get_document"]', resourceScope='{}'))
        run_id = run['runId']
        report['runId'] = run_id
        save()
        print('Run：', run_id, flush=True)
        # 3. Run 刚创建可能仍是 PENDING，短暂轮询真实读取，随后预热全量集合。
        deadline = time.monotonic() + 30
        while True:
            probe = read(selected[0])
            if probe['status'] == 'SUCCEEDED':
                break
            if probe['errorCode'] != 'RUN_NOT_ACTIVE' or time.monotonic() >= deadline:
                raise RuntimeError('Run/首篇文档未就绪：' + str(probe))
            time.sleep(0.5)
        warmup = [read(d) for d in selected]
        report['warmup'] = warmup
        if any(r['status'] != 'SUCCEEDED' for r in warmup):
            raise RuntimeError('预热失败，请检查 Run、拥有者和解析产物')
        expected = {r['documentId']: r['digest'] for r in warmup}
        # 4. 轮换模式执行顺序，线程池创建在计时之外；每次请求独立 callId。
        modes = [1, 4, 8]
        for repeat in range(args.repeats):
            order = modes[repeat % 3:] + modes[:repeat % 3]
            for workers in order:
                with ThreadPoolExecutor(max_workers=workers) as pool:
                    started = time.perf_counter()
                    results = list(pool.map(read, selected)) if workers > 1 else [read(d) for d in selected]
                    elapsed = (time.perf_counter() - started) * 1000
                valid = all(r['status'] == 'SUCCEEDED' and
                            r['digest'] == expected[r['documentId']] for r in results)
                report['rounds'].append(dict(repeat=repeat + 1, workers=workers,
                                             elapsedMs=elapsed, valid=valid, requests=results))
                save()
                print(f'第 {repeat + 1} 轮，并发 {workers}：{elapsed:.2f} ms，有效={valid}', flush=True)
                if not valid:
                    raise RuntimeError('请求失败或内容变化，本次实验中止，不报告加速结论')
        medians = {w: statistics.median(r['elapsedMs'] for r in report['rounds']
                                       if r['workers'] == w) for w in modes}
        report['summary'] = {w: dict(medianMs=medians[w],
            speedup=medians[1] / medians[w], reduction=1 - medians[w] / medians[1]) for w in modes}
        print(json.dumps(report['summary'], ensure_ascii=False, indent=2))
    except Exception as exc:
        report['errors'].append(str(exc))
        raise
    finally:
        # 5. 保留实验会话作为证据；取消 Run 的失败也明确记录。
        if run_id:
            try:
                report['cleanup'] = post(
                    f'/api/v1/conversations/{conversation_id}/runs/{run_id}/cancel', {})
            except Exception as exc:
                report['cleanupError'] = str(exc)
                print('取消 Run 失败，请在 WS 中检查：', run_id, file=sys.stderr)
        api.close()
        save()


if __name__ == '__main__':
    main()
