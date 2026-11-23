package by.it.group510902.shust.lesson15;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SourceScannerB {

    private static final class FileSizeInfo {
        final int size;
        final String relativePath;

        FileSizeInfo(int size, String relativePath) {
            this.size = size;
            this.relativePath = relativePath;
        }
    }

    public static void main(String[] args) {
        String src = System.getProperty("user.dir")
                + File.separator + "src" + File.separator;
        Path root = Path.of(src);

        List<FileSizeInfo> results;
        try (Stream<Path> walk = Files.walk(root)) {
            results = walk
                    .filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
                    .parallel()
                    .map(p -> processFile(root, p))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        results.sort(Comparator.comparingInt((FileSizeInfo f) -> f.size)
                .thenComparing(f -> f.relativePath));

        StringBuilder out = new StringBuilder(results.size() * 48);
        for (FileSizeInfo info : results) {
            out.append(info.size).append(' ').append(info.relativePath).append('\n');
        }
        System.out.print(out);
    }

    private static FileSizeInfo processFile(Path root, Path file) {
        String content = readFileIgnoringMalformed(file);
        if (content == null || content.contains("@Test") || content.contains("org.junit.Test")) {
            return null;
        }

        String withoutComments = removeComments(content);
        StringBuilder cleaned = removePackageImportsAndEmptyLines(withoutComments);

        int start = 0;
        int end = cleaned.length();
        while (start < end && cleaned.charAt(start) < 33) {
            start++;
        }
        while (end > start && cleaned.charAt(end - 1) < 33) {
            end--;
        }

        int byteSize = utf8ByteLength(cleaned, start, end);
        String relPath = root.relativize(file).toString();
        return new FileSizeInfo(byteSize, relPath);
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

    private static StringBuilder removePackageImportsAndEmptyLines(String text) {
        int len = text.length();
        StringBuilder sb = new StringBuilder(len);
        int pos = 0;
        while (pos < len) {
            int lineEnd = text.indexOf('\n', pos);
            int nextPos = (lineEnd == -1) ? len : lineEnd + 1;
            int contentEnd = (lineEnd == -1) ? len : lineEnd;
            if (contentEnd > pos && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }

            int firstNonWs = pos;
            while (firstNonWs < contentEnd && text.charAt(firstNonWs) < 33) {
                firstNonWs++;
            }

            if (firstNonWs < contentEnd && !isPackageOrImportAt(text, firstNonWs, contentEnd)) {
                sb.append(text, pos, contentEnd).append('\n');
            }
            pos = nextPos;
        }
        return sb;
    }

    private static boolean isPackageOrImportAt(String text, int start, int end) {
        if (text.startsWith("package", start)) {
            int after = start + 7;
            return after == end || text.charAt(after) < 33;
        }
        if (text.startsWith("import", start)) {
            int after = start + 6;
            return after == end || text.charAt(after) < 33;
        }
        return false;
    }

    private static int utf8ByteLength(CharSequence seq, int start, int end) {
        int bytes = 0;
        for (int i = start; i < end; i++) {
            char c = seq.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(seq.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}