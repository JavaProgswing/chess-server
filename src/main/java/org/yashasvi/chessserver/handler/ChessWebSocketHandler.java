package org.yashasvi.chessserver.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.yashasvi.chessserver.selenium.ChessAutomator;
import org.yashasvi.chessserver.selenium.ChessGameAttrs;
import org.yashasvi.chessserver.selenium.ChessSide;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class ChessWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // Executor for async tasks (engine moves, bot loading)
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    // Ping scheduler
    private final ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor();

    public ChessWebSocketHandler() {
        keepAliveScheduler.scheduleAtFixedRate(() -> {
            for (WebSocketSession session : sessions.values()) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(new PingMessage(ByteBuffer.wrap("".getBytes())));
                    } catch (Exception e) {
                        System.err.println("[WS] Could not ping session " + session.getId() + ": " + e.getMessage());
                        sessions.remove(session.getId());
                        try {
                            session.close();
                        } catch (Exception ignored) {
                        }
                    }
                } else {
                    sessions.remove(session.getId());
                }
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        System.out.println("[WS] Client " + session.getId() + " connected.");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        asyncExecutor.submit(() -> processMessage(session, message));
    }

    private void processMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode data = mapper.readTree(message.getPayload());
            String action = data.path("action").asText();

            switch (action) {
                case "ping" -> safeSend(session, "{\"type\":\"ping\", \"status\":\"Ok\"}");

                case "init" -> handleInit(session, data);

                case "next_move" -> handleNextMove(session, data);

                case "promote" -> handlePromote(session, data);

                case "select_bot" -> handleSelectBot(session, data);

                case "undo" -> handleUndo(session);

                default -> safeSend(session, "{\"error\":\"Unknown action\"}");
            }
        } catch (Exception e) {
            e.printStackTrace();
            safeSend(session, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Sends a message safely only if session is open
     */
    private void safeSend(WebSocketSession session, String payload) {
        if (session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(payload));
            } catch (Exception e) {
                System.err.println("[WS] Failed to send message: " + e.getMessage());
                sessions.remove(session.getId());
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Handles initialization
     */
    private void handleInit(WebSocketSession session, JsonNode data) {
        asyncExecutor.submit(() -> {
            CountDownLatch botsLoadedLatch = new CountDownLatch(1);

            try {
                String pgn = data.has("pgn") && !data.get("pgn").isNull() ? data.get("pgn").asText() : null;

                int moveNo = data.has("move_no") && !data.get("move_no").isNull() ? data.get("move_no").asInt() : -1;

                ChessSide side;
                ChessAutomator automator;

                if (pgn != null) {
                    automator = new ChessAutomator(new ChessGameAttrs(pgn, moveNo));
                    side = automator.getSide();
                } else {
                    String sideStr = data.has("side") ? data.get("side").asText().toLowerCase() : "white";
                    side = sideStr.equals("black") ? ChessSide.BLACK : ChessSide.WHITE;
                    automator = new ChessAutomator(side);
                }

                session.getAttributes().put("automator", automator);

                // ---------------- BOT LOADING (ASYNC) ----------------
                asyncExecutor.submit(() -> {
                    try {
                        automator.loadBotList();
                        while (!ChessAutomator.isLoaded()) Thread.sleep(250);

                        ObjectNode response = mapper.createObjectNode();
                        response.put("status", "Initialized as " + side.name());
                        response.put("type", "init");
                        response.put("side", side.name());

                        var botsArray = mapper.createArrayNode();
                        for (Map<String, Object> bot : ChessAutomator.BOTS) {
                            ObjectNode b = mapper.createObjectNode();
                            b.put("id", (Integer) bot.get("id"));
                            b.put("name", (String) bot.get("name"));
                            if (bot.get("rating") != null) b.put("rating", bot.get("rating").toString());
                            if (bot.get("avatar") != null) b.put("avatar", (String) bot.get("avatar"));
                            b.put("is_engine", (Boolean) bot.get("is_engine"));
                            botsArray.add(b);
                        }

                        response.set("bots", botsArray);
                        response.set("current_bot", mapper.valueToTree(automator.getSelectedBot()));

                        ObjectNode stateWrapper = mapper.createObjectNode();
                        stateWrapper.setAll((ObjectNode) mapper.valueToTree(automator.getReadableBoardState(automator.getInitialState())));
                        response.set("state", stateWrapper);

                        safeSend(session, response.toString());

                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        // Always release the latch so the awaiting thread does not block indefinitely
                        // if bot loading fails or an exception is thrown in this task.
                        botsLoadedLatch.countDown();
                    }
                });

                // ---------------- ENGINE HANDLING ----------------
                while (automator.getEngineResult() == null) Thread.sleep(100);

                botsLoadedLatch.await();

                if ((Boolean) automator.getEngineResult().get("engine_active")) {
                    Map<String, Object> move = automator.getNextBestMove(null);
                    if (move != null) {
                        ObjectNode response = mapper.createObjectNode();
                        ObjectNode moveNode = mapper.createObjectNode();
                        moveNode.put("piece", (String) move.get("piece"));
                        moveNode.put("from", (String) move.get("from"));
                        moveNode.put("to", (String) move.get("to"));

                        response.set("move", moveNode);
                        response.put("type", "engine_move");
                        response.put("status", move.get("piece") + " to " + move.get("to"));

                        ObjectNode stateWrapper = mapper.createObjectNode();
                        stateWrapper.setAll((ObjectNode) mapper.valueToTree(move.get("state")));
                        response.set("state", stateWrapper);

                        safeSend(session, response.toString());
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                safeSend(session, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
    }

    private void handleNextMove(WebSocketSession session, JsonNode data) {
        asyncExecutor.submit(() -> {
            try {
                ChessAutomator automator = (ChessAutomator) session.getAttributes().get("automator");
                if (automator == null) {
                    safeSend(session, "{\"error\":\"Bot not initialized.\"}");
                    return;
                }

                String opponentMove = data.has("opponent_move") && !data.get("opponent_move").isNull() ? data.get("opponent_move").asText() : null;
                if (opponentMove == null) {
                    safeSend(session, "{\"error\":\"Opponent move cannot be null.\"}");
                    return;
                }

                Map<String, Object> move = automator.getNextBestMove(opponentMove);
                ObjectNode response = mapper.createObjectNode();

                if (move != null) {
                    ObjectNode moveNode = mapper.createObjectNode();
                    moveNode.put("piece", (String) move.get("piece"));
                    moveNode.put("from", (String) move.get("from"));
                    moveNode.put("to", (String) move.get("to"));
                    response.set("move", moveNode);
                    response.put("type", "engine_move");
                    response.put("status", move.get("piece") + " to " + move.get("to"));
                    ObjectNode stateWrapper = mapper.createObjectNode();
                    stateWrapper.setAll((ObjectNode) mapper.valueToTree(move.get("state")));
                    response.set("state", stateWrapper);
                } else {
                    response.put("error", "No valid moves found or game over.");
                }
                safeSend(session, response.toString());

            } catch (Exception e) {
                e.printStackTrace();
                safeSend(session, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
    }

    private void handlePromote(WebSocketSession session, JsonNode data) {
        asyncExecutor.submit(() -> {
            try {
                ChessAutomator automator = (ChessAutomator) session.getAttributes().get("automator");
                if (automator == null) {
                    safeSend(session, "{\"error\":\"Bot not initialized.\"}");
                    return;
                }

                String piece = data.has("promote_to") ? data.get("promote_to").asText() : "q";
                automator.completePromotion(piece);
                ObjectNode response = mapper.createObjectNode();
                response.put("status", "Promoted to " + piece.toUpperCase());
                response.put("type", "promote");
                response.put("piece", piece);
                safeSend(session, response.toString());
            } catch (Exception e) {
                e.printStackTrace();
                safeSend(session, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
    }

    private void handleSelectBot(WebSocketSession session, JsonNode data) {
        asyncExecutor.submit(() -> {
            try {
                ChessAutomator automator = (ChessAutomator) session.getAttributes().get("automator");
                if (automator == null) {
                    safeSend(session, "{\"error\":\"Bot not initialized.\"}");
                    return;
                }

                int botId = data.has("bot_id") ? data.get("bot_id").asInt() : 0;
                Integer engineLevel = data.has("engine_level") && !data.get("engine_level").isNull() ? data.get("engine_level").asInt() : null;
                automator.selectBot(botId, engineLevel);

                ObjectNode response = mapper.createObjectNode();
                response.put("status", "Selected bot: " + automator.getSelectedBot().get("name"));
                response.put("type", "select_bot");
                response.put("current_bot", String.format("%s (Rating: %s)", automator.getSelectedBot().get("name"), automator.getSelectedBot().get("rating")));
                safeSend(session, response.toString());
            } catch (Exception e) {
                e.printStackTrace();
                safeSend(session, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
    }

    private void handleUndo(WebSocketSession session) {
        asyncExecutor.submit(() -> {
            try {
                ChessAutomator automator = (ChessAutomator) session.getAttributes().get("automator");
                if (automator == null) {
                    safeSend(session, "{\"error\":\"Bot not initialized.\"}");
                    return;
                }
                automator.undoLastMove();
                ObjectNode response = mapper.createObjectNode();
                response.put("status", "Undid last move.");
                response.put("type", "undo");
                safeSend(session, response.toString());
            } catch (Exception e) {
                e.printStackTrace();
                safeSend(session, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object obj = session.getAttributes().get("automator");
        if (obj instanceof ChessAutomator automator) {
            try {
                automator.quit();
            } catch (Exception ignored) {
            }
        }
        sessions.remove(session.getId());
        System.out.println("[WS] Client " + session.getId() + " disconnected.");
    }
}
