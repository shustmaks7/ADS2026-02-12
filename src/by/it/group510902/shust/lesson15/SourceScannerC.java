package by.it.group510902.shust.lesson15;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SourceScannerC {

    private static final int MAX_DIST = 9; // строго < 10 правок
    private static final int FREQ_BUCKETS = 96; // символы 32..126 + один бакет для >= 127

    private static final class FileEntry {
        final String path;
        final String text;

        FileEntry(String path, String text) {
            this.path = path;
            this.text = text;
        }
    }

    private static final class UniqueGroup {
        final String text;
        final int len;
        final int[] freq;
        final List<String> paths;

        UniqueGroup(String text, List<String> paths) {
            this.text = text;
            this.len = text.length();
            this.paths = paths;
            this.freq = buildFreq(text);
        }

        private static int[] buildFreq(String s) {
            int[] f = new int[FREQ_BUCKETS];
            int n = s.length();
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                int idx = (c < 127) ? (c - 32) : (FREQ_BUCKETS - 1);
                f[idx]++;
            }
            return f;
        }
    }

    public static void main(String[] args) {
        String src = System.getProperty("user.dir")
                + File.separator + "src" + File.separator;
        Path root = Path.of(src);

        List<FileEntry> entries;
        try (Stream<Path> walk = Files.walk(root)) {
            entries = walk
                    .filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .parallel()
                    .map(p -> processFile(root, p))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Группируем полностью идентичные тексты (расстояние = 0)
        Map<String, List<String>> exactGroups = new HashMap<>(entries.size() * 2);
        for (FileEntry entry : entries) {
            exactGroups.computeIfAbsent(entry.text, k -> new ArrayList<>(2)).add(entry.path);
        }

        UniqueGroup[] groups = new UniqueGroup[exactGroups.size()];
        int gIdx = 0;
        for (Map.Entry<String, List<String>> e : exactGroups.entrySet()) {
            groups[gIdx++] = new UniqueGroup(e.getKey(), e.getValue());
        }

        // Сортируем уникальные тексты по длине для скользящего окна |len1 - len2| <= 9
        Arrays.sort(groups, Comparator.comparingInt(g -> g.len));

        // Параллельно ищем близкие пары среди уникальных текстов
        ConcurrentLinkedQueue<long[]> similarPairs = new ConcurrentLinkedQueue<>();
        ThreadLocal<int[][]> dpBuffers = ThreadLocal.withInitial(() -> new int[][]{new int[1024], new int[1024]});

        int uCount = groups.length;
        IntStream.range(0, uCount).parallel().forEach(i -> {
            UniqueGroup g1 = groups[i];
            int maxLen = g1.len + MAX_DIST;
            int[][] bufs = dpBuffers.get();

            for (int j = i + 1; j < uCount; j++) {
                UniqueGroup g2 = groups[j];
                if (g2.len > maxLen) {
                    break;
                }
                if (passFreqFilter(g1.freq, g2.freq) && isWithinLevenshtein(g1.text, g2.text, bufs)) {
                    similarPairs.add(new long[]{i, j});
                }
            }
        });

        // Собираем итоговую карту копий (ключи сразу отсортированы лексикографически)
        TreeMap<String, List<String>> copiesMap = new TreeMap<>();

        // 1. Точные совпадения внутри одной группы
        for (UniqueGroup group : groups) {
            if (group.paths.size() > 1) {
                for (String p1 : group.paths) {
                    List<String> list = copiesMap.computeIfAbsent(p1, k -> new ArrayList<>());
                    for (String p2 : group.paths) {
                        if (!p1.equals(p2)) {
                            list.add(p2);
                        }
                    }
                }
            }
        }

        // 2. Похожие пары между разными группами
        for (long[] pair : similarPairs) {
            UniqueGroup g1 = groups[(int) pair[0]];
            UniqueGroup g2 = groups[(int) pair[1]];
            for (String p1 : g1.paths) {
                copiesMap.computeIfAbsent(p1, k -> new ArrayList<>()).addAll(g2.paths);
            }
            for (String p2 : g2.paths) {
                copiesMap.computeIfAbsent(p2, k -> new ArrayList<>()).addAll(g1.paths);
            }
        }

        StringBuilder out = new StringBuilder(copiesMap.size() * 128);
        for (Map.Entry<String, List<String>> entry : copiesMap.entrySet()) {
            out.append(entry.getKey()).append('\n');
            List<String> copies = entry.getValue();
            Collections.sort(copies);
            for (String copy : copies) {
                out.append(copy).append('\n');
            }
        }
        System.out.print(out);
    }

    private static FileEntry processFile(Path root, Path file) {
        String content = readFileIgnoringMalformed(file);
        if (content == null || content.contains("@Test") || content.contains("org.junit.Test")) {
            return null;
        }
        String withoutComments = removeComments(content);
        String normalized = stripImportsAndNormalize(withoutComments);
        return new FileEntry(root.relativize(file).toString(), normalized);
    }

    private static String readFileIgnoringMalformed(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.IGNORE)
                    .onUnmappableCharacter(CodingErrorAction.IGNORE);
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static String removeComments(String text) {
        int len = text.length();
        StringBuilder sb = new StringBuilder(len);
        int state = 0; // 0=NORMAL, 1=LINE_COMMENT, 2=BLOCK_COMMENT, 3=STRING, 4=CHAR, 5=TEXT_BLOCK

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            switch (state) {
                case 0:
                    if (c == '/' && i + 1 < len) {
                        char next = text.charAt(i + 1);
                        if (next == '/') {
                            state = 1;
                            i++;
                        } else if (next == '*') {
                            state = 2;
                            i++;
                        } else {
                            sb.append(c);
                        }
                    } else if (c == '"') {
                        if (i + 2 < len && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
                            sb.append("\"\"\"");
                            i += 2;
                            state = 5;
                        } else {
                            sb.append(c);
                            state = 3;
                        }
                    } else if (c == '\'') {
                        sb.append(c);
                        state = 4;
                    } else {
                        sb.append(c);
                    }
                    break;
                case 1:
                    if (c == '\n' || c == '\r') {
                        sb.append(c);
                        state = 0;
                    }
                    break;
                case 2:
                    if (c == '*' && i + 1 < len && text.charAt(i + 1) == '/') {
                        i++;
                        state = 0;
                    }
                    break;
                case 3:
                    sb.append(c);
                    if (c == '\\' && i + 1 < len) {
                        sb.append(text.charAt(++i));
                    } else if (c == '"' || c == '\n') {
                        state = 0;
                    }
                    break;
                case 4:
                    sb.append(c);
                    if (c == '\\' && i + 1 < len) {
                        sb.append(text.charAt(++i));
                    } else if (c == '\'' || c == '\n') {
                        state = 0;
                    }
                    break;
                case 5:
                    sb.append(c);
                    if (c == '\\' && i + 1 < len) {
                        sb.append(text.charAt(++i));
                    } else if (c == '"' && i + 2 < len && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
                        sb.append("\"\"");
                        i += 2;
                        state = 0;
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static String stripImportsAndNormalize(String text) {
        int len = text.length();
        StringBuilder sb = new StringBuilder(len);
        boolean needSpace = false;
        int pos = 0;

        while (pos < len) {
            int lineEnd = text.indexOf('\n', pos);
            int nextPos = (lineEnd == -1) ? len : lineEnd + 1;
            int contentEnd = (lineEnd == -1) ? len : lineEnd;
            if (contentEnd > pos && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }

            if (!isPackageOrImport(text, pos, contentEnd)) {
                for (int i = pos; i < contentEnd; i++) {
                    char c = text.charAt(i);
                    if (c < 33) {
                        if (sb.length() > 0) {
                            needSpace = true;
                        }
                    } else {
                        if (needSpace) {
                            sb.append(' ');
                            needSpace = false;
                        }
                        sb.append(c);
                    }
                }
                if (sb.length() > 0) {
                    needSpace = true;
                }
            }
            pos = nextPos;
        }
        return sb.toString();
    }

    private static boolean isPackageOrImport(String text, int start, int end) {
        int i = start;
        while (i < end && text.charAt(i) < 33) {
            i++;
        }
        if (text.startsWith("package", i)) {
            int after = i + 7;
            return after == end || text.charAt(after) < 33;
        }
        if (text.startsWith("import", i)) {
            int after = i + 6;
            return after == end || text.charAt(after) < 33;
        }
        return false;
    }

    private static boolean passFreqFilter(int[] f1, int[] f2) {
        int pos = 0;
        int neg = 0;
        for (int i = 0; i < FREQ_BUCKETS; i++) {
            int diff = f1[i] - f2[i];
            if (diff > 0) {
                pos += diff;
                if (pos > MAX_DIST) {
                    return false;
                }
            } else if (diff < 0) {
                neg -= diff;
                if (neg > MAX_DIST) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isWithinLevenshtein(String a, String b, int[][] bufs) {
        int lenA = a.length();
        int lenB = b.length();

        int start = 0;
        int minLen = Math.min(lenA, lenB);
        while (start < minLen && a.charAt(start) == b.charAt(start)) {
            start++;
        }

        int endA = lenA;
        int endB = lenB;
        while (endA > start && endB > start && a.charAt(endA - 1) == b.charAt(endB - 1)) {
            endA--;
            endB--;
        }

        int n = endA - start;
        int m = endB - start;
        if (n == 0) {
            return m <= MAX_DIST;
        }
        if (m == 0) {
            return n <= MAX_DIST;
        }
        if (Math.abs(n - m) > MAX_DIST) {
            return false;
        }

        if (bufs[0].length <= m) {
            int newCap = Math.max(m + 1, bufs[0].length * 2);
            bufs[0] = new int[newCap];
            bufs[1] = new int[newCap];
        }

        int[] prev = bufs[0];
        int[] curr = bufs[1];
        final int inf = MAX_DIST + 1;

        int initLimit = Math.min(m, MAX_DIST);
        for (int j = 0; j <= initLimit; j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= n; i++) {
            char ca = a.charAt(start + i - 1);
            int from = Math.max(1, i - MAX_DIST);
            int to = Math.min(m, i + MAX_DIST);

            if (from == 1) {
                curr[0] = i;
            } else {
                curr[from - 1] = inf;
            }
            if (i + MAX_DIST <= m) {
                prev[i + MAX_DIST] = inf;
            }

            int rowMin = inf;
            for (int j = from; j <= to; j++) {
                int cost = (ca == b.charAt(start + j - 1)) ? 0 : 1;
                int v = prev[j - 1] + cost;
                int del = prev[j] + 1;
                if (del < v) {
                    v = del;
                }
                int ins = curr[j - 1] + 1;
                if (ins < v) {
                    v = ins;
                }
                curr[j] = v;
                if (v < rowMin) {
                    rowMin = v;
                }
            }

            if (rowMin > MAX_DIST) {
                return false;
            }

            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }

        return prev[m] <= MAX_DIST;
    }
}