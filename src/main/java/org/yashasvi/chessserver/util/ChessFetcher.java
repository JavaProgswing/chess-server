package org.yashasvi.chessserver.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.pgn.PgnHolder;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class ChessFetcher {
    private final ObjectMapper mapper = new ObjectMapper();

    public ObjectMapper getMapper() {
        return mapper;
    }

    /**
     * Parse PGN using chesslib and return structured moves:
     * [{"piece":"p","from":"e2","to":"e4","pgn":"e4"}, ...]
     */
    private List<ObjectNode> extractStructuredMoves(String pgn) throws Exception {
        List<ObjectNode> structuredMoves = new ArrayList<>();

        // Temp file to load PGN
        File temp = File.createTempFile("game", ".pgn");
        Files.writeString(temp.toPath(), pgn, StandardCharsets.UTF_8);

        PgnHolder holder = new PgnHolder(temp.getAbsolutePath());
        holder.loadPgn();

        holder.getGames().forEach(game -> {
            Board board = new Board();
            var moveList = game.getHalfMoves();

            StringBuilder pgnSoFar = new StringBuilder();
            int moveNumber = 1;

            for (com.github.bhlangonijr.chesslib.move.Move move : moveList) {
                ObjectNode moveNode = mapper.createObjectNode();
                try {
                    String san = move.toString(); // SAN notation
                    String from = move.getFrom().toString();
                    String to = move.getTo().toString();
                    String piece = board.getPiece(move.getFrom()).getPieceType().name().toLowerCase();

                    moveNode.put("piece", piece);
                    moveNode.put("from", from);
                    moveNode.put("to", to);

                    // Add move number before WHITE moves
                    if (board.getSideToMove() == Side.WHITE) {
                        pgnSoFar.append(moveNumber).append(". ");
                    }

                    pgnSoFar.append(san).append(" ");
                    moveNode.put("pgn", pgnSoFar.toString().trim());

                    structuredMoves.add(moveNode);

                    board.doMove(move);

                    // Increment move number after BLACK move
                    if (board.getSideToMove() == Side.WHITE) {
                        moveNumber++;
                    }

                } catch (Exception e) {
                    throw new com.github.bhlangonijr.chesslib.pgn.PgnException("Error parsing move in PGN");
                }
            }
        });

        return structuredMoves;
    }

    public JsonNode getRecentGames(String username) throws Exception {
        String url = "https://api.chess.com/pub/player/" + username + "/games/archives";
        JsonNode archiveResponse = fetchJson(url);

        int statusCode = archiveResponse.get("status_code").asInt();
        JsonNode archivesRoot = archiveResponse.get("result");
        List<JsonNode> games = new ArrayList<>();

        if (statusCode == 200 && archivesRoot.has("archives") && !archivesRoot.get("archives").isEmpty()) {
            String latestMonth = archivesRoot.get("archives").get(archivesRoot.get("archives").size() - 1).asText();
            JsonNode archiveGamesResponse = getGamesFromArchive(latestMonth);
            games.addAll(StreamSupport.stream(archiveGamesResponse.get("result").spliterator(), false).collect(Collectors.toList()));
            statusCode = archiveGamesResponse.get("status_code").asInt(); // update to reflect latest fetch
        }

        ObjectNode finalResponse = mapper.createObjectNode();
        finalResponse.put("status_code", statusCode);
        finalResponse.set("result", mapper.valueToTree(games));
        return finalResponse;
    }


    private JsonNode getGamesFromArchive(String archiveUrl) throws Exception {
        JsonNode archiveResponse = fetchJson(archiveUrl);
        int statusCode = archiveResponse.get("status_code").asInt();
        JsonNode root = archiveResponse.get("result");

        List<JsonNode> games = new ArrayList<>();

        if (statusCode == 200 && root.has("games")) {
            for (JsonNode g : root.get("games")) {
                String pgn = g.has("pgn") ? g.get("pgn").asText() : "";
                try {
                    List<ObjectNode> moves = extractStructuredMoves(pgn);

                    if (g.isObject()) {
                        ((ObjectNode) g).putPOJO("moves", moves);
                    }
                    games.add(g);
                } catch (com.github.bhlangonijr.chesslib.pgn.PgnException e) {
                    // Skip games with PGN parsing issues
                }
            }
        }

        ObjectNode response = mapper.createObjectNode();
        response.put("status_code", statusCode);
        response.set("result", mapper.valueToTree(games));
        return response;
    }


    public JsonNode fetchJson(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        int statusCode = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();

        try (Scanner scanner = new Scanner(statusCode >= 200 && statusCode < 300 ? conn.getInputStream() : conn.getErrorStream() != null ? conn.getErrorStream() : InputStream.nullInputStream())) {
            while (scanner.hasNextLine()) {
                sb.append(scanner.nextLine());
            }
        }

        ObjectNode responseNode = mapper.createObjectNode();
        responseNode.put("status_code", statusCode);

        try {
            JsonNode result = sb.length() > 0 ? mapper.readTree(sb.toString()) : mapper.createObjectNode();
            responseNode.set("result", result);
        } catch (Exception e) {
            responseNode.set("result", mapper.createObjectNode());
        }

        return responseNode;
    }

}
