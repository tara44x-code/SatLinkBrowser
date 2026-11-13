package com.satlink.geckoviewtest;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a shared web link without changing signed query parameters. */
final class SharedUrlParser {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"'\\u200e\\u200f]+", Pattern.CASE_INSENSITIVE);

    static String firstHttpUrl(CharSequence sharedText) {
        if (sharedText == null) return "";
        String text = sharedText.toString();
        if (text.length() > 100_000) text = text.substring(0, 100_000);
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            while (!candidate.isEmpty()) {
                char tail = candidate.charAt(candidate.length()-1);
                boolean punctuation = candidate.indexOf('?') < 0 && ".,،؛!؟".indexOf(tail) >= 0;
                boolean unmatched = tail == ')' && count(candidate, ')') > count(candidate, '(')
                        || tail == ']' && count(candidate, ']') > count(candidate, '[');
                if (!punctuation && !unmatched) break;
                candidate = candidate.substring(0, candidate.length()-1);
            }
            try {
                URI uri = new URI(candidate);
                if (uri.getHost() != null && uri.getRawUserInfo() == null
                        && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                    return candidate;
            } catch (Exception ignored) { }
        }
        return "";
    }

    private static int count(String text, char value) {
        int result = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == value) result++;
        return result;
    }
}
