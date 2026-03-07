package yelf42.craftword;

import com.google.gson.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.ByteArrayInputStream;
import java.util.*;

public class CrosswordParser {

    private static final Gson GSON = new Gson();

    // --- Universal ---

    private static class UniversalJson {
        String Title, Author, Editor, Copyright, AllAnswer, AcrossClue, DownClue;
        int Width, Height;
    }

    public static Crossword parseUniversal(String site, String date, String json) {
        UniversalJson u = GSON.fromJson(json, UniversalJson.class);
        char[][] grid = buildGrid(u.AllAnswer, u.Width, u.Height);
        return new Crossword(u.Title, u.Author, u.Editor, u.Copyright, date, site,
                u.Width, u.Height, grid,
                parseDelimitedClues(u.AcrossClue),
                parseDelimitedClues(u.DownClue),
                buildAcrossIndex(grid, u.Width, u.Height),
                buildDownIndex(grid, u.Width, u.Height));
    }

    // --- WaPo ---

    private static class WaPoJson {
        String puzzleId, title, creator, copyright;
        int width;
        List<WaPoCell> cells;
        List<WaPoWord> words;
    }

    private static class WaPoCell {
        String answer, number, type;
    }

    private static class WaPoWord {
        String direction, clue;
        List<Integer> indexes;
    }

    public static Crossword parseWaPo(String site, String date, String json) {
        WaPoJson w = GSON.fromJson(json, WaPoJson.class);

        List<Character> cellChars = new ArrayList<>();
        Map<Integer, Integer> cellNumberMap = new HashMap<>();

        for (int i = 0; i < w.cells.size(); i++) {
            WaPoCell cell = w.cells.get(i);
            if ("locked".equals(cell.type)) {
                cellChars.add('.');
            } else {
                cellChars.add(cell.answer != null && !cell.answer.isEmpty() ? cell.answer.charAt(0) : '.');
                if (cell.number != null && !cell.number.isEmpty()) {
                    cellNumberMap.put(i, Integer.parseInt(cell.number));
                }
            }
        }

        int height = cellChars.size() / w.width;
        char[][] grid = new char[height][w.width];
        for (int i = 0; i < cellChars.size(); i++) {
            grid[i / w.width][i % w.width] = cellChars.get(i);
        }

        Map<Integer, Clue> acrossClues = new HashMap<>();
        Map<Integer, Clue> downClues = new HashMap<>();
        int[][] acrossIndex = new int[height][w.width];
        int[][] downIndex = new int[height][w.width];
        for (int[] row : acrossIndex) Arrays.fill(row, -1);
        for (int[] row : downIndex) Arrays.fill(row, -1);

        for (WaPoWord word : w.words) {
            if (word.indexes == null || word.indexes.isEmpty()) continue;
            int clueNumber = cellNumberMap.getOrDefault(word.indexes.get(0), -1);
            if (clueNumber == -1) continue;
            Clue clue = new Clue(clueNumber, word.clue);
            if ("across".equals(word.direction)) {
                acrossClues.put(clueNumber, clue);
                for (int idx : word.indexes) acrossIndex[idx / w.width][idx % w.width] = clueNumber;
            } else {
                downClues.put(clueNumber, clue);
                for (int idx : word.indexes) downIndex[idx / w.width][idx % w.width] = clueNumber;
            }
        }

        return new Crossword(w.title, w.creator, "", w.copyright, date, site,
                w.width, height, grid, acrossClues, downClues, acrossIndex, downIndex);
    }

    // --- USA Today (XML, unchanged) ---

    public static Crossword parseUSAToday(String site, String date, String xml) {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes()));
            doc.getDocumentElement().normalize();

            String title = getXmlAttr(doc, "Title");
            String author = getXmlAttr(doc, "Author");
            String editor = getXmlAttr(doc, "Editor");
            String copyright = getXmlAttr(doc, "Copyright");
            int width = Integer.parseInt(getXmlAttr(doc, "Width"));
            int height = Integer.parseInt(getXmlAttr(doc, "Height"));
            String allAnswer = getXmlAttr(doc, "AllAnswer").replace("-", ".");

            char[][] grid = buildGrid(allAnswer, width, height);

            return new Crossword(title, author, editor, copyright, date, site,
                    width, height, grid,
                    parseXmlClues(doc, "across"),
                    parseXmlClues(doc, "down"),
                    buildAcrossIndex(grid, width, height),
                    buildDownIndex(grid, width, height));

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse USA Today XML: " + e.getMessage());
        }
    }

    // --- Grid builders (unchanged) ---

    private static char[][] buildGrid(String allAnswer, int width, int height) {
        char[][] grid = new char[height][width];
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++) {
                char c = allAnswer.charAt(row * width + col);
                grid[row][col] = (c == '-') ? '.' : c;
            }
        return grid;
    }

    private static int[][] buildAcrossIndex(char[][] grid, int width, int height) {
        int[][] index = new int[height][width];
        for (int[] row : index) Arrays.fill(row, -1);
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++) {
                if (grid[row][col] == '.') continue;
                boolean startsWord = (col == 0 || grid[row][col - 1] == '.')
                        && (col + 1 < width && grid[row][col + 1] != '.');
                if (startsWord) {
                    int num = calcClueNumber(grid, row, col, width, height);
                    int c = col;
                    while (c < width && grid[row][c] != '.') index[row][c++] = num;
                }
            }
        return index;
    }

    private static int[][] buildDownIndex(char[][] grid, int width, int height) {
        int[][] index = new int[height][width];
        for (int[] row : index) Arrays.fill(row, -1);
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++) {
                if (grid[row][col] == '.') continue;
                boolean startsWord = (row == 0 || grid[row - 1][col] == '.')
                        && (row + 1 < height && grid[row + 1][col] != '.');
                if (startsWord) {
                    int num = calcClueNumber(grid, row, col, width, height);
                    int r = row;
                    while (r < height && grid[r][col] != '.') index[r++][col] = num;
                }
            }
        return index;
    }

    private static int calcClueNumber(char[][] grid, int targetRow, int targetCol, int width, int height) {
        int number = 0;
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++) {
                if (grid[row][col] == '.') continue;
                boolean startsAcross = (col == 0 || grid[row][col - 1] == '.')
                        && (col + 1 < width && grid[row][col + 1] != '.');
                boolean startsDown = (row == 0 || grid[row - 1][col] == '.')
                        && (row + 1 < height && grid[row + 1][col] != '.');
                if (startsAcross || startsDown) number++;
                if (row == targetRow && col == targetCol) return number;
            }
        return -1;
    }

    // --- Clue parsers ---

    private static Map<Integer, Clue> parseDelimitedClues(String raw) {
        Map<Integer, Clue> clues = new HashMap<>();
        if (raw == null) return clues;
        for (String line : raw.split("\\\\n|\\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|", 2);
            if (parts.length < 2) continue;
            try {
                int number = Integer.parseInt(parts[0].trim());
                clues.put(number, new Clue(number, parts[1].trim()));
            } catch (NumberFormatException ignored) {}
        }
        return clues;
    }

    private static Map<Integer, Clue> parseXmlClues(Document doc, String direction) {
        Map<Integer, Clue> clues = new HashMap<>();
        NodeList directionNodes = doc.getElementsByTagName(direction);
        if (directionNodes.getLength() == 0) return clues;
        NodeList clueNodes = directionNodes.item(0).getChildNodes();
        for (int i = 0; i < clueNodes.getLength(); i++) {
            Node node = clueNodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            int number = Integer.parseInt(el.getAttribute("cn"));
            String text = java.net.URLDecoder.decode(el.getAttribute("c"), java.nio.charset.StandardCharsets.UTF_8);
            clues.put(number, new Clue(number, text));
        }
        return clues;
    }

    private static String getXmlAttr(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        return ((Element) nodes.item(0)).getAttribute("v");
    }
}

