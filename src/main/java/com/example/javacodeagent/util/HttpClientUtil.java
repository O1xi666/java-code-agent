package com.example.javacodeagent.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public final class HttpClientUtil {

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 15000;
    private static final int MAX_RETRIES = 3;
    private static final int MAX_RETRY_DELAY_MS = 3000;
    private static final Random RETRY_RANDOM = new Random();

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private HttpClientUtil() {}

    private static String getCharset(HttpURLConnection conn) {
        String ct = conn.getContentType();
        if (ct != null) {
            String[] parts = ct.split("charset=");
            if (parts.length > 1) return parts[1].split(";")[0].trim();
        }
        return "UTF-8";
    }

    public static String get(String urlString) {
        Exception lastEx = null;
        for (int at = 1; at <= MAX_RETRIES; at++) {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Referer", "https://finance.sina.com.cn/");
                conn.setRequestProperty("Accept", "text/html,*/*");

                int code = conn.getResponseCode();
                if (code != 200) throw new RuntimeException("HTTP " + code);

                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), getCharset(conn)));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                conn.disconnect();

                String body = sb.toString();
                if (body == null || body.isBlank()) throw new RuntimeException("空响应");
                return body;

            } catch (Exception e) {
                lastEx = e;
                if (at < MAX_RETRIES) {
                    int delayMs = 1000 + RETRY_RANDOM.nextInt(MAX_RETRY_DELAY_MS - 1000 + 1);
                    try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                }
            }
        }
        throw new RuntimeException("HTTP 失败: " + urlString + " -> " + lastEx.getMessage());
    }
}
