package com.example.javacodeagent.rag.util;

import java.util.ArrayList;
import java.util.List;

public final class SimHash {
    private static final int BITS = 64;
    /**
     * 判重阈值（汉明距离）。
     *
     * <p>取值依据实测分布：中文近义改写文本的距离约 15~16，主题无关文本约 30~35，
     * 取 22 同时给两侧留出余量。
     *
     * <p>注意：早期版本用 {@code h = h * 31 + c} 这类弱散列，64 位指纹分布极不均匀，
     * 实测近义文本 8、无关文本 9~12，几乎不具备区分度（阈值取 6 时近义对反而判不出来）。
     * 换成 FNV-1a + splitmix64 混淆后才拉开差距。
     */
    private static final int DUPLICATE_THRESHOLD = 22;
    private static final int NGRAM = 2;  // bigram: 对中文近义文本更稳定
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
        for (int i = 0; i <= text.length() - NGRAM; i++) feats.add(text.substring(i, i + NGRAM));
        return feats;
    }

    /**
     * 特征散列：FNV-1a 64 位 + splitmix64 终混。
     *
     * <p>SimHash 只关心每一位的正负投票，散列本身必须让 64 位均匀分布，
     * 否则特征之间的独立性不成立，指纹距离也就失去意义。
     */
    private static long featHash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int j = 0; j < s.length(); j++) {
            h ^= (long) s.charAt(j);
            h *= 0x100000001b3L;
        }
        h ^= (h >>> 30);
        h *= 0xbf58476d1ce4e5b9L;
        h ^= (h >>> 27);
        h *= 0x94d049bb133111ebL;
        h ^= (h >>> 31);
        return h;
    }
}
