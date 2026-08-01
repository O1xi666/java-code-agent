# -*- coding: utf-8 -*-
import re

# ── Step 1: Create SimHash.java ──
simhash_code = '''package com.example.javacodeagent.rag.util;

import java.util.ArrayList;
import java.util.List;

public final class SimHash {
    private static final int BITS = 64;
    private static final int DUPLICATE_THRESHOLD = 3;
    private SimHash() {}

    public static long compute(String text) {
        if (text == null || text.isBlank()) return 0L;
        int[] bits = new int[BITS];
        List<String> features = extractFeatures(text);
        for (String feat : features) {
            long hash = featHash(feat);
            for (int i = 0; i < BITS; i++) {
                if ((hash & (1L << i)) != 0) bits[i]++; else bits[i]--;
            }
        }
        long fp = 0L;
        for (int i = 0; i < BITS; i++) { if (bits[i] > 0) fp |= (1L << i); }
        return fp;
    }

    public static int hammingDistance(long a, long b) { return Long.bitCount(a ^ b); }
    public static boolean isDuplicate(long a, long b) { return hammingDistance(a, b) <= DUPLICATE_THRESHOLD; }

    private static List<String> extractFeatures(String text) {
        List<String> feats = new ArrayList<>();
        for (int i = 0; i < text.length() - 2; i++) feats.add(text.substring(i, i + 3));
        return feats;
    }

    private static long featHash(String s) {
        long h = 0L;
        for (int j = 0; j < s.length(); j++) h = h * 31L + (long) s.charAt(j);
        return h;
    }
}
'''

path_sim = r'E:\github\JavaAgent\java-code-agent\src\main\java\com\example\javacodeagent\rag\util\SimHash.java'
with open(path_sim, 'w', encoding='utf-8') as f:
    f.write(simhash_code)
print('1. SimHash.java created')

# ── Step 2: Modify KnowledgeBaseService.java ──
path_kb = r'E:\github\JavaAgent\java-code-agent\src\main\java\com\example\javacodeagent\rag\service\KnowledgeBaseService.java'
with open(path_kb, 'r', encoding='utf-8') as f:
    c = f.read()

# Add imports
imp = '\nimport com.example.javacodeagent.rag.util.SimHash;\nimport java.time.Instant;\nimport java.time.temporal.ChronoUnit;'
if 'import com.example.javacodeagent.rag.util.SimHash;' not in c:
    c = c.replace('import com.example.javacodeagent.rag.util.BM25Searcher;', 'import com.example.javacodeagent.rag.util.BM25Searcher;' + imp)

# Update retrieve call: pass query to fuseAndFilter
c = c.replace('fuseAndFilter(vectorMatches, bm25Results, filterByStock ? stockCode.trim() : null, k)',
              'fuseAndFilter(vectorMatches, bm25Results, filterByStock ? stockCode.trim() : null, k, query)')

# Update fuseAndFilter signature
old_sig = '    private List<RetrievedKnowledge> fuseAndFilter(\n            List<EmbeddingMatch<TextSegment>> vectorMatches,\n            List<BM25Searcher.Bm25Result> bm25Results,\n            String stockCodeFilter, int topK) {'
new_sig = '    private List<RetrievedKnowledge> fuseAndFilter(\n            List<EmbeddingMatch<TextSegment>> vectorMatches,\n            List<BM25Searcher.Bm25Result> bm25Results,\n            String stockCodeFilter, int topK,\n            String query) {'
c = c.replace(old_sig, new_sig)

# Insert reranking before the final sort+return
rerank = '''
        // ── 细排：1. 股票名/代码匹配 ──
        if (query != null && !query.isBlank()) {
            for (ScoreAcc a : scoreMap.values()) {
                if (a.stockCode != null && !a.stockCode.isBlank()) {
                    String code = a.stockCode.contains(".") ? a.stockCode.substring(2) : a.stockCode;
                    if (query.contains(code)) { a.totalScore += 0.30; continue; }
                }
                if (a.stockName != null && !a.stockName.isBlank()) {
                    if (query.contains(a.stockName)) {
                        a.totalScore += 0.25;
                    } else {
                        for (int i = 0; i < a.stockName.length() - 1; i++) {
                            if (query.contains(a.stockName.substring(i, i + 2))) {
                                a.totalScore += 0.10; break;
                            }
                        }
                    }
                }
            }
        }

        // ── 细排：2. 时间权重 ──
        {
            Instant now = Instant.now();
            for (java.util.Map.Entry<String, ScoreAcc> e : scoreMap.entrySet()) {
                KnowledgeEntry ke = entryMap.get(e.getKey());
                if (ke != null && ke.getCreatedAt() != null) {
                    long days = ChronoUnit.DAYS.between(ke.getCreatedAt(), now);
                    double m;
                    if (days <= 7) m = 1.50;
                    else if (days <= 90) m = 1.20;
                    else if (days <= 365) m = 0.80;
                    else m = 0.30;
                    e.getValue().totalScore *= m;
                }
            }
        }

        // ── 细排：3. simHash 去重 ──
        {
            java.util.List<String> idList = new java.util.ArrayList<>(scoreMap.keySet());
            long[] fps = new long[idList.size()];
            for (int i = 0; i < idList.size(); i++) {
                ScoreAcc a = scoreMap.get(idList.get(i));
                fps[i] = (a != null && a.content != null) ? SimHash.compute(a.content) : 0L;
            }
            for (int i = 0; i < idList.size(); i++) {
                for (int j = i + 1; j < idList.size(); j++) {
                    if (fps[i] != 0L && fps[j] != 0L && SimHash.isDuplicate(fps[i], fps[j])) {
                        ScoreAcc a = scoreMap.get(idList.get(i));
                        ScoreAcc b = scoreMap.get(idList.get(j));
                        if (a != null && b != null) {
                            if (a.totalScore > b.totalScore) b.totalScore -= 0.50;
                            else a.totalScore -= 0.50;
                        }
                    }
                }
            }
        }
'''

# Find position to insert rerank (after the BM25 scoring loop, before the return)
insert_marker = '''
        int totalB = bm25Results.size();
        for (int i = 0; i < bm25Results.size(); i++) {
            BM25Searcher.Bm25Result bm25 = bm25Results.get(i);
            KnowledgeEntry entry = entryMap.get(bm25.chunkId());
            if (entry == null) continue;'''

c = c.replace(insert_marker, insert_marker)  # verify marker exists

# Insert after the closing brace of BM25 scoring and before the return
after_bm25 = '''            if (acc.stockCode == null) acc.fill(entry);
        }
'''

c = c.replace(after_bm25, after_bm25 + rerank)

with open(path_kb, 'w', encoding='utf-8') as f:
    f.write(c)
print('2. KnowledgeBaseService.java updated')

# Verify
with open(path_kb, 'r', encoding='utf-8') as f:
    c2 = f.read()
checks = [
    ('SimHash imported', 'import com.example.javacodeagent.rag.util.SimHash;' in c2),
    ('fuseAndFilter has query param', 'String query' in c2.split('private List<RetrievedKnowledge> fuseAndFilter')[1].split('\n')[0] if 'fuseAndFilter' in c2 else False),
    ('simHash dedup', 'SimHash.isDuplicate' in c2),
    ('time weighting', 'ChronoUnit.DAYS.between' in c2),
    ('stock bonus 0.30', 'totalScore += 0.30' in c2),
    ('stock bonus 0.25', 'totalScore += 0.25' in c2),
    ('stock bonus 0.10', 'totalScore += 0.10' in c2),
]
for name, ok in checks:
    print(f'  {"OK" if ok else "FAIL"}: {name}')
print('Done!')
