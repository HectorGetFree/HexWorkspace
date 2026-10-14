package com.hector.infrastructure.rag;

import com.hector.domain.knowledge.model.valobj.RetrievalCandidateVO;
import java.util.*;
import java.util.regex.Pattern;

/** 与 SciFact 评测一致的英文 BM25（k1=1.2，b=0.75）；不声称支持中文分词。 */
final class Bm25Index {
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+(?:[-/][a-z0-9]+)*");
    private static final Set<String> STOP = Set.of(("a an and are as at be been being by for from has have if in into is it its of on or that the their this to was were will with without while whether why how what when where which who whose all any can could did do does during each either both than then these those they them we you your our not no nor only also show shows shown").split(" "));
    private final List<RetrievalCandidateVO> documents;
    private final Map<String, Map<Integer, Integer>> postings = new HashMap<>();
    private final int[] lengths;
    private final double average;
    Bm25Index(List<RetrievalCandidateVO> values) {
        documents = List.copyOf(values);
        lengths = new int[values.size()];
        long total = 0;
        for (int i = 0; i < values.size(); i++) {
            var terms = terms(values.get(i).content());
            int position = i;
            terms.forEach((term, count) -> postings.computeIfAbsent(term, ignored -> new HashMap<>()).put(position, count));
            lengths[i] = terms.values().stream().mapToInt(Integer::intValue).sum();
            total += lengths[i];
        }
        average = values.isEmpty() ? 0 : (double) total / values.size();
    }
    private static Map<String, Integer> terms(String text) {
        Map<String, Integer> result = new HashMap<>();
        var matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            if (!STOP.contains(term)) result.merge(term, 1, Integer::sum);
        }
        return result;
    }
    List<RetrievalCandidateVO> search(String query, int limit) {
        if (average == 0 || limit <= 0) return List.of();
        Map<Integer, Double> scores = new HashMap<>();
        terms(query).forEach((term, frequency) -> {
            var posting = postings.get(term);
            if (posting == null) return;
            double idf = Math.log(1 + (documents.size() - posting.size() + .5) / (posting.size() + .5));
            posting.forEach((index, tf) -> scores.merge(index, frequency * idf * tf * 2.2 /
                    (tf + 1.2 * (.25 + .75 * lengths[index] / average)), Double::sum));
        });
        return scores.keySet().stream().sorted(Comparator.<Integer>comparingDouble(scores::get).reversed()
                .thenComparingInt(Integer::intValue)).limit(limit).map(i -> {
                    var c = documents.get(i);
                    return new RetrievalCandidateVO(c.pointId(), c.workspaceId(), c.documentId(), c.versionNo(),
                            c.indexInputRevision(), c.generationId(), c.chunkNo(), c.headingPath(), c.content(),
                            c.pageStart(), c.pageEnd(), scores.get(i));
                }).toList();
    }
}
