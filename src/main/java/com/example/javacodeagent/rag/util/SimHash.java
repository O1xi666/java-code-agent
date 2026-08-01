package com.example.javacodeagent.rag.util;

import java.util.ArrayList;
import java.util.List;

public final class SimHash {
    private static final int BITS = 64;
    private static final int DUPLICATE_THRESHOLD = 6;
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

    private static long featHash(String s) {
        long h = 0L;
        for (int j = 0; j < s.length(); j++) h = h * 31L + (long) s.charAt(j);
        return h;
    }
}
