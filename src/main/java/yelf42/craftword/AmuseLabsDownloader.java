package yelf42.craftword;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Arrays;

public class AmuseLabsDownloader {

    public static String fetchAmuseLabsToken(String pickerUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(pickerUrl).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        reader.close();
        String html = sb.toString();

        // Strategy 1: pickerParams.rawsps = '...'
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("pickerParams\\.rawsps\\s*=\\s*'([^']+)'")
                .matcher(html);
        if (m1.find()) {
            return extractLoadToken(m1.group(1));
        }

        // Strategy 2: <script id="params">{"rawsps":"..."}</script>
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("<script[^>]+id=\"params\"[^>]*>([^<]+)</script>")
                .matcher(html);
        if (m2.find()) {
            JsonObject params = JsonParser.parseString(m2.group(1)).getAsJsonObject();
            if (params.has("rawsps")) {
                return extractLoadToken(params.get("rawsps").getAsString());
            }
        }

        return null; // token optional — puzzle may still load without it
    }

    public static String extractLoadToken(String rawsps) throws Exception {
        String decoded = new String(java.util.Base64.getDecoder().decode(rawsps));
        JsonObject obj = JsonParser.parseString(decoded).getAsJsonObject();
        return obj.has("loadToken") ? obj.get("loadToken").getAsString() : null;
    }

    public static String extractAndDeobfuscateRawc(String html) throws Exception {
        String rawc = null;

        // Strategy 1: window.rawc = '...' or window.puzzleEnv.rawc = '...'
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("window(?:\\.puzzleEnv)?\\.rawc\\s*=\\s*'([^']+)'")
                .matcher(html);
        if (m1.find()) rawc = m1.group(1);

        // Strategy 2: <script id="params">{"rawc":"..."}</script>
        if (rawc == null) {
            java.util.regex.Matcher m2 = java.util.regex.Pattern
                    .compile("<script[^>]+id=\"params\"[^>]*>([^<]+)</script>")
                    .matcher(html);
            if (m2.find()) {
                JsonObject params = JsonParser.parseString(m2.group(1)).getAsJsonObject();
                if (params.has("rawc")) rawc = params.get("rawc").getAsString();
            }
        }

        if (rawc == null) throw new Exception("Could not find rawc in AmuseLabs page");

        return deobfuscateRawc(rawc);
    }

    public static String deobfuscateRawc(String rawc) throws Exception {
        // Find first key digit heuristic ("ye" or "we" reversed = start of base64 JSON)
        int yePos = rawc.indexOf("ye");
        int wePos = rawc.indexOf("we");
        if (yePos == -1) yePos = rawc.length();
        if (wePos == -1) wePos = rawc.length();
        int firstKeyDigit = Math.min(yePos, wePos) + 2;

        // BFS over candidate keys (7 digits, each 2-20)
        java.util.Deque<int[]> queue = new java.util.ArrayDeque<>();
        if (firstKeyDigit <= 20) {
            queue.add(new int[]{firstKeyDigit});
        } else {
            queue.add(new int[]{});
        }

        while (!queue.isEmpty()) {
            int[] prefix = queue.poll();

            if (prefix.length == 7) {
                String result = deobfuscateWithKey(rawc, prefix);
                if (!result.isEmpty()) {
                    try {
                        JsonParser.parseString(result); // validate JSON
                        return result;
                    } catch (Exception ignored) {}
                }
                continue;
            }

            for (int next = 2; next <= 20; next++) {
                int[] candidate = Arrays.copyOf(prefix, prefix.length + 1);
                candidate[prefix.length] = next;

                int remaining = 7 - candidate.length;
                int minSpacing = 2 * remaining;
                int maxSpacing = 20 * remaining;

                for (int spacing = minSpacing; spacing <= maxSpacing; spacing++) {
                    if (isValidKeyPrefix(rawc, candidate, spacing)) {
                        queue.add(candidate);
                        break;
                    }
                }
            }
        }

        throw new Exception("Failed to deobfuscate AmuseLabs rawc");
    }

    public static String deobfuscateWithKey(String rawc, int[] key) {
        try {
            char[] buffer = rawc.toCharArray();
            int i = 0, segCount = 0;
            while (i < buffer.length - 1) {
                int len = Math.min(key[segCount % key.length], buffer.length - i);
                segCount++;
                int left = i, right = i + len - 1;
                while (left < right) {
                    char tmp = buffer[left]; buffer[left] = buffer[right]; buffer[right] = tmp;
                    left++; right--;
                }
                i += len;
            }
            byte[] decoded = java.util.Base64.getDecoder().decode(new String(buffer));
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isValidKeyPrefix(String rawc, int[] keyPrefix, int spacing) {
        try {
            int pos = 0;
            while (pos < rawc.length()) {
                int startPos = pos;
                StringBuilder chunk = new StringBuilder();
                int keyIndex = 0;
                while (keyIndex < keyPrefix.length && pos < rawc.length()) {
                    int len = Math.min(keyPrefix[keyIndex], rawc.length() - pos);
                    chunk.append(new StringBuilder(rawc.substring(pos, pos + len)).reverse());
                    pos += len;
                    keyIndex++;
                }
                int b64Start = ((startPos + 3) / 4) * 4 - startPos;
                int b64End = (pos / 4) * 4 - startPos;
                if (b64Start < chunk.length() && b64End > b64Start) {
                    String b64Chunk = chunk.substring(b64Start, Math.min(b64End, chunk.length()));
                    try {
                        byte[] decoded = java.util.Base64.getDecoder().decode(b64Chunk);
                        for (byte b : decoded) {
                            int v = b & 0xFF;
                            if ((v < 32 && v != 0x09 && v != 0x0A && v != 0x0D)
                                    || v == 0xC0 || v == 0xC1 || v >= 0xF5) return false;
                        }
                    } catch (Exception e) { return false; }
                }
                pos += spacing;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
