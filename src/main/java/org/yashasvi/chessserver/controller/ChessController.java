package org.yashasvi.chessserver.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yashasvi.chessserver.util.ChessFetcher;

import java.sql.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/chess")
public class ChessController {
    @Value("${spring.datasource.url}")
    private String DB_URL;

    @Value("${spring.datasource.username}")
    private String DB_USER;

    @Value("${spring.datasource.password}")
    private String DB_PASS;

    @Autowired
    private ChessFetcher fetcher;

    @PostConstruct
    public void initTable() {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS); Statement stmt = conn.createStatement()) {

            String sql = """
                        CREATE TABLE IF NOT EXISTS chess_games (
                            id SERIAL PRIMARY KEY,
                            username VARCHAR(255) NOT NULL,
                            data JSONB NOT NULL,
                            fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                    """;
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @GetMapping("/profile/{username}")
    public ResponseEntity<JsonNode> getProfile(@PathVariable String username) throws Exception {
        String url = "https://api.chess.com/pub/player/" + username;
        JsonNode response = fetcher.fetchJson(url);

        int statusCode = response.get("status_code").asInt();
        JsonNode result = response.get("result");

        if (statusCode == 200) {
            return ResponseEntity.ok(result);
        } else if (statusCode == 404) {
            ObjectNode error = fetcher.getMapper().createObjectNode();
            error.put("error", "User not found");
            error.put("status_code", 404);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        } else {
            ObjectNode error = fetcher.getMapper().createObjectNode();
            error.put("error", "Failed to fetch profile");
            error.put("status_code", statusCode);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/games/{username}")
    public ResponseEntity<JsonNode> getRecentGames(@PathVariable String username) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {

            // 1️⃣ Check cache first
            String selectSQL = "SELECT data, fetched_at FROM chess_games WHERE username = ? ORDER BY fetched_at DESC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(selectSQL)) {
                ps.setString(1, username.toLowerCase());
                ResultSet rs = ps.executeQuery();

                if (rs.next()) {
                    Timestamp fetchedAt = rs.getTimestamp("fetched_at");
                    long ageSeconds = Instant.now().getEpochSecond() - fetchedAt.toInstant().getEpochSecond();

                    // Reuse cache if recent (< 1 hour old)
                    if (ageSeconds < 3600) {
                        JsonNode cached = fetcher.getMapper().readTree(rs.getString("data"));
                        return ResponseEntity.ok(cached);
                    }
                }
            }

            // 2️⃣ Fetch fresh data from Chess.com API
            JsonNode response = fetcher.getRecentGames(username);
            int statusCode = response.get("status_code").asInt();
            JsonNode result = response.get("result");

            if (statusCode == 200) {
                // 3️⃣ Store / update in DB
                String insertSQL = """
                            INSERT INTO chess_games (username, data, fetched_at)
                            VALUES (?, ?::jsonb, CURRENT_TIMESTAMP)
                        """;
                try (PreparedStatement ps = conn.prepareStatement(insertSQL)) {
                    ps.setString(1, username.toLowerCase());
                    ps.setString(2, result.toString());
                    ps.executeUpdate();
                }

                return ResponseEntity.ok(result);
            } else if (statusCode == 404) {
                ObjectNode error = fetcher.getMapper().createObjectNode();
                error.put("error", "User not found");
                error.put("status_code", 404);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
            } else {
                ObjectNode error = fetcher.getMapper().createObjectNode();
                error.put("error", "Failed to fetch games");
                error.put("status_code", statusCode);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
            }

        } catch (Exception e) {
            e.printStackTrace();
            ObjectNode error = fetcher.getMapper().createObjectNode();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
