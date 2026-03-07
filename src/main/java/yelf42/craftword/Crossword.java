package yelf42.craftword;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializer;

import java.util.Map;

public record Crossword(
        // Metadata
        String title,
        String author,
        String editor,
        String copyright,
        String date,
        String site,
        int width,
        int height,

        // Grid
        char[][] grid,

        // Clues stored once each
        Map<Integer, Clue> acrossClues,  // keyed by clue number
        Map<Integer, Clue> downClues,    // keyed by clue number

        // Lookup: given a cell, which clue number owns it?
        int[][] acrossIndex,  // acrossIndex[row][col] = clue number, or -1
        int[][] downIndex     // downIndex[row][col] = clue number, or -1
) {
    public String saveCrossword() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(this);
    }

    public static Crossword fromJson(String json) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(char[][].class, (JsonDeserializer<char[][]>) (element, type, ctx) -> {
                    JsonArray rows = element.getAsJsonArray();
                    char[][] grid = new char[rows.size()][];
                    for (int i = 0; i < rows.size(); i++) {
                        JsonArray row = rows.get(i).getAsJsonArray();
                        grid[i] = new char[row.size()];
                        for (int j = 0; j < row.size(); j++) {
                            grid[i][j] = row.get(j).getAsString().charAt(0);
                        }
                    }
                    return grid;
                })
                .create();

        return gson.fromJson(json, Crossword.class);
    }
}

record Clue(
        int number,
        String text
) {}
