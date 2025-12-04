package org.yashasvi.chessserver.selenium;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ChessAutomator {
    // Shared bot metadata
    public static final List<Map<String, Object>> BOTS = new ArrayList<>();
    private static boolean BOT_LOADED = false;
    private final ChessSide side;
    private final ChessGameAttrs attrs;
    private final WebDriver driver;
    private final WebDriverWait wait;
    private Map<String, Object> engineResult = null;
    private Map<String, Piece> initialState;
    private Map<String, Piece> pendingPromotion = null;
    private Map<String, String> selectedBot = null;
    private volatile boolean waitingForEngineMove = false;

    public ChessAutomator(ChessSide side) {
        this.side = side;
        this.attrs = null;
        // Setup driver (WebDriverManager handles chromedriver)
        // Use a local cache path to avoid permission issues in /home/container/.cache
        // or /root
        WebDriverManager.chromedriver().cachePath("selenium_cache").setup();

        this.driver = new ChromeDriver(getOptions());
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.openAnalysisAndStart();
        this.selectedBot = getCurrentBot();
    }

    public ChessAutomator(ChessGameAttrs attrs) {
        this.side = (attrs.getMoveNumber() % 2 == 0) ? ChessSide.BLACK : ChessSide.WHITE;
        this.attrs = attrs;
        // Setup driver (WebDriverManager handles chromedriver)
        // Use a local cache path to avoid permission issues in /home/container/.cache
        // or /root
        WebDriverManager.chromedriver().cachePath("selenium_cache").setup();

        this.driver = new ChromeDriver(getOptions());
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        this.openAnalysisAndStart();
        this.selectedBot = getCurrentBot();
    }

    private static ChromeOptions getOptions() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new"); // Essential for server environments
        options.addArguments("--disable-gpu");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--mute-audio");
        return options;
    }

    public static boolean isLoaded() {
        return BOT_LOADED;
    }

    public Map<String, Object> getEngineResult() {
        return engineResult;
    }

    public Map<String, Piece> getInitialState() {
        return initialState;
    }

    public ChessSide getSide() {
        return side;
    }

    private void openAnalysisAndStart() {
        driver.get("https://www.chess.com/analysis?tab=analysis");

        // File scrnshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        // System.out.println(scrnshot.getAbsolutePath());
        wait.until(ExpectedConditions.presenceOfElementLocated(By.id("board-controls-settings")));
        System.out.println("[INFO] Analysis page loaded.");

        if (attrs != null && attrs.getPgn() != null) {
            try {
                WebElement textarea = wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector("textarea.cc-textarea-component.load-from-pgn-textarea")));
                textarea.clear();
                textarea.sendKeys(attrs.getPgn());
                System.out.println("[INFO] PGN pasted into textarea.");
            } catch (Exception e) {
                System.out.printf("[ERROR] Failed to load PGN: %s%n", e.getMessage());
                throw new IllegalStateException("Failed to load game from PGN.");
            }
            int moveNumber = attrs.getMoveNumber();
            WebElement moveDiv = null;
            try {
                WebElement addButton = driver.findElement(By.xpath(
                        "//button[contains(@class, 'cc-button-primary')]//span[text()='Add Game(s)']/ancestor::button"));
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", addButton);
                System.out.println("[INFO] Add Game(s) button clicked.");
            } catch (Exception e) {
                System.out.printf("[ERROR] Failed to click Add Game(s) button: %s%n", e.getMessage());
            }
            if (moveNumber >= 0) {
                String dataNodeValue = "0-" + moveNumber;
                WebElement moveList = wait.until(
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.analysis-view-scrollable")));
                try {
                    moveDiv = moveList
                            .findElement(By.cssSelector("div.node.main-line-ply[data-node='" + dataNodeValue + "']"));
                } catch (org.openqa.selenium.NoSuchElementException e) {
                    System.out.printf("[WARN] No move div found for move number %d%n", moveNumber);
                }
            } else {
                List<WebElement> nodes = driver.findElements(By.cssSelector("div.node.main-line-ply"));
                int max = -1;
                for (WebElement node : nodes) {
                    String dataNode = node.getAttribute("data-node");
                    int num = Integer.parseInt(dataNode.split("-")[1]);
                    if (num > max) {
                        max = num;
                        moveDiv = node;
                    }
                }
            }

            if (moveDiv != null) {
                moveDiv.click();
                System.out.println("[INFO] Clicked move div: " + moveDiv.getAttribute("data-node"));
            }

            if (side == ChessSide.BLACK) {
                System.out.println("[INFO] Flipping board so user plays black and engine plays white.");
                WebElement settingsButton = driver.findElement(By.id("board-controls-settings"));
                new Actions(driver).moveToElement(settingsButton).perform();
                wait.until(ExpectedConditions.presenceOfElementLocated(By.id("board-controls-flip")));
                WebElement flipButton = driver.findElement(By.id("board-controls-flip"));
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", flipButton);
                System.out.println("[INFO] Board flipped.");
            }
        } else {
            if (side == ChessSide.WHITE) {
                System.out.println("[INFO] Flipping board so engine plays black and user plays white.");
                WebElement settingsButton = driver.findElement(By.id("board-controls-settings"));
                new Actions(driver).moveToElement(settingsButton).perform();
                wait.until(ExpectedConditions.presenceOfElementLocated(By.id("board-controls-flip")));
                WebElement flipButton = driver.findElement(By.id("board-controls-flip"));
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", flipButton);
                System.out.println("[INFO] Board flipped.");
            }
        }
        wait.until(
                ExpectedConditions.elementToBeClickable(By.cssSelector("button[aria-label='Practice vs Computer']")));
        this.initialState = getInitialBoardState();

        WebElement practiceButton = driver.findElement(By.cssSelector("button[aria-label='Practice vs Computer']"));
        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", practiceButton);
        System.out.println("[INFO] Practice vs Computer button clicked.");

        // Switch to newest tab
        List<String> handles = new ArrayList<>(driver.getWindowHandles());
        driver.switchTo().window(handles.get(handles.size() - 1));
        System.out.println("[INFO] Switched to new Practice tab.");

        wait.until(ExpectedConditions.presenceOfElementLocated(By.id("board-board")));
        CompletableFuture.runAsync(() -> {
            Map<String, Object> result = new HashMap<>();
            try {
                result.put("move", waitForEngineMove(this.initialState, 15));
                result.put("engine_active", true);
            } catch (TimeoutException ignored) {
                result.put("engine_active", false);
            }
            engineResult = result;
        });
    }

    public Map<String, String> getSelectedBot() {
        return selectedBot;
    }

    public synchronized void loadBotList() throws Exception {
        if (BOT_LOADED)
            return;

        try {
            WebElement changeBotButton = wait
                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector("button[aria-label='Change Bot']")));
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", changeBotButton);

            WebElement scrollContainer = wait
                    .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.bot-selection-scroll")));

            Set<String> seenBots = new HashSet<>();
            BOTS.clear();

            while (true) {
                List<WebElement> tiles = driver.findElements(By.cssSelector("li.bot-component"));
                try {
                    for (WebElement tile : tiles) {
                        String name = tile.getAttribute("data-bot-name");
                        if (name == null)
                            continue;
                        if (seenBots.contains(name))
                            continue;

                        List<WebElement> lockElements = tile
                                .findElements(By.cssSelector("span.cc-icon-glyph.cc-icon-small.bot-lock"));
                        if (!lockElements.isEmpty()) {
                            seenBots.add(name);
                            continue;
                        }

                        WebElement nameEl = driver.findElement(By.cssSelector("span.selected-bot-name"));
                        String initialName = nameEl.getText().trim();
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", tile);

                        if (initialName.equals(name)) {
                            seenBots.add(name);
                            continue;
                        }
                        wait.until(d -> {
                            String text = d.findElement(By.cssSelector("span.selected-bot-name")).getText().trim();
                            return !text.equals(initialName);
                        });

                        String classification = tile.getAttribute("data-bot-classification");
                        boolean isEngine = classification != null && classification.equalsIgnoreCase("engine");
                        String avatarUrl;
                        try {
                            WebElement imgEl = tile.findElement(By.cssSelector("img.bot-img"));
                            avatarUrl = imgEl.getAttribute("src");
                        } catch (Exception ex) {
                            avatarUrl = null;
                            System.out.printf("[WARN] No avatar for bot '%s'%n", name);
                        }

                        if (isEngine) {
                            WebElement slider = wait.until(ExpectedConditions
                                    .presenceOfElementLocated(By.cssSelector("input.slider-input[type='range']")));
                            setSliderValue(slider, Integer.parseInt(slider.getAttribute("min")));
                            String ratingStart = driver.findElement(By.cssSelector("span.selected-bot-rating"))
                                    .getText().trim().replace("(", "").replace(")", "");
                            setSliderValue(slider, Integer.parseInt(slider.getAttribute("max")));
                            String ratingEnd = driver.findElement(By.cssSelector("span.selected-bot-rating")).getText()
                                    .trim().replace("(", "").replace(")", "");
                            String engineName = "Engine (" + ratingStart + "-" + ratingEnd + ")";
                            Map<String, Object> botInfo = new HashMap<>();
                            botInfo.put("id", BOTS.size());
                            botInfo.put("name", engineName);
                            botInfo.put("rating", ratingEnd);
                            botInfo.put("classification", classification.toLowerCase());
                            botInfo.put("is_engine", true);
                            botInfo.put("avatar", avatarUrl);
                            BOTS.add(botInfo);
                            seenBots.add(engineName);
                        } else {
                            String rating = driver.findElement(By.cssSelector("span.selected-bot-rating")).getText()
                                    .trim().replace("(", "").replace(")", "");
                            Map<String, Object> botInfo = new HashMap<>();
                            botInfo.put("id", BOTS.size());
                            botInfo.put("name", name);
                            botInfo.put("rating", rating);
                            botInfo.put("classification", classification.toLowerCase());
                            botInfo.put("is_engine", false);
                            botInfo.put("avatar", avatarUrl);
                            BOTS.add(botInfo);
                            seenBots.add(name);
                        }
                    }
                } catch (StaleElementReferenceException e) {
                    continue;
                }

                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollTop += 1200;", scrollContainer);
                Boolean isAtBottom = (Boolean) ((JavascriptExecutor) driver).executeScript(
                        "let el = arguments[0]; return el.scrollTop + el.offsetHeight >= el.scrollHeight - 5;",
                        scrollContainer);

                if (Boolean.TRUE.equals(isAtBottom))
                    break;
            }

            try {
                WebElement backButton = driver.findElement(By.cssSelector("button.selection-menu-back"));
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", backButton);
            } catch (Exception ignore) {
            }

            BOT_LOADED = true;
            System.out.printf("[INFO] %d bots loaded.%n", BOTS.size());
        } catch (Exception e) {
            throw new Exception("[ERROR] Failed to load bot list: " + e.getMessage(), e);
        }
    }

    private void setSliderValue(WebElement sliderElement, int targetValue) {
        int minVal = Integer.parseInt(sliderElement.getAttribute("min"));
        int maxVal = Integer.parseInt(sliderElement.getAttribute("max"));
        if (targetValue < minVal || targetValue > maxVal) {
            throw new IllegalArgumentException("Value out of range");
        }
        JavascriptExecutor js = (JavascriptExecutor) driver;
        int initialRating;
        try {
            initialRating = Integer.parseInt(
                    wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("span.selected-bot-rating")))
                            .getText().replace("(", "").replace(")", ""));
        } catch (NumberFormatException e) {
            System.out.println("[WARN] Initial rating is not a number, skipping.");
            return;
        }
        if (Integer.parseInt(Objects.requireNonNull(sliderElement.getAttribute("value"))) == targetValue) {
            System.out.println("[INFO] Slider already at target value: " + targetValue);
            return;
        }
        System.out.println("[INFO] Moving slider to " + targetValue + " (range: " + minVal + "-" + maxVal + ")");
        js.executeScript("""
                    const slider = arguments[0];
                    const value = arguments[1];
                    slider.value = value;
                    slider.setAttribute('value', value);
                    slider.dispatchEvent(new Event('input', { bubbles: true }));
                    slider.dispatchEvent(new Event('change', { bubbles: true }));
                """, sliderElement, targetValue);
        wait.until(d -> {
            String newRating = d.findElement(By.cssSelector("span.selected-bot-rating")).getText().replace("(", "")
                    .replace(")", "");
            return Integer.parseInt(newRating) != initialRating;
        });
        System.out.printf("[INFO] Slider set to %d%n", targetValue);
    }

    public void selectBot(int botId, Integer engineLevel) throws Exception {
        if (!BOT_LOADED) {
            System.out.println("[INFO] Loading bots list for the first time...");
            loadBotList();
        }
        if (botId < 0 || botId >= BOTS.size()) {
            throw new IllegalArgumentException("Bot ID out of range");
        }

        Map<String, Object> botToSelect = BOTS.get(botId);
        try {
            WebElement changeBotButton = wait
                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector("button[aria-label='Change Bot']")));
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", changeBotButton);

            WebElement scrollContainer = wait
                    .until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.bot-selection-scroll")));

            WebElement selectedTile = null;
            while (true) {
                List<WebElement> tiles = driver.findElements(By.cssSelector("li.bot-component"));
                for (WebElement tile : tiles) {
                    String name = tile.getAttribute("data-bot-name");
                    String classification = tile.getAttribute("data-bot-classification");
                    boolean isEngine = Boolean.TRUE.equals(botToSelect.get("is_engine"));

                    if ((name != null && name.equals(botToSelect.get("name")) && classification != null
                            && classification
                                    .equalsIgnoreCase(((String) botToSelect.getOrDefault("classification", ""))))
                            || (isEngine && "engine".equalsIgnoreCase(classification))) {
                        selectedTile = tile;
                        break;
                    }
                }

                if (selectedTile != null)
                    break;

                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollTop += 1200;", scrollContainer);
                Boolean isAtBottom = (Boolean) ((JavascriptExecutor) driver).executeScript(
                        "let el = arguments[0]; return el.scrollTop + el.offsetHeight >= el.scrollHeight - 5;",
                        scrollContainer);
                if (Boolean.TRUE.equals(isAtBottom))
                    break;
            }

            if (selectedTile == null)
                throw new Exception("Could not find bot in list");

            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", selectedTile);
            System.out.printf("[INFO] Bot tile clicked: %s%n", botToSelect.get("name"));

            if (Boolean.TRUE.equals(botToSelect.get("is_engine")) && engineLevel != null) {
                if (engineLevel < 1 || engineLevel > 25)
                    throw new IllegalArgumentException("Engine level must be between 1 and 25");
                int sliderValue = engineLevel - 1;
                WebElement slider = wait.until(ExpectedConditions
                        .presenceOfElementLocated(By.cssSelector("input.slider-input[type='range']")));
                setSliderValue(slider, sliderValue);
                System.out.printf("[INFO] Engine level set to %d%n", engineLevel);
            }

            WebElement chooseButton = wait.until(ExpectedConditions.elementToBeClickable(By
                    .xpath("//button[contains(@class, 'cc-button-primary')]//span[text()='Choose']/ancestor::button")));
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", chooseButton);
            System.out.println("[INFO] Bot selection confirmed.");
            this.selectedBot = getCurrentBot();

        } catch (Exception e) {
            throw new Exception("[ERROR] Failed to select bot: " + e.getMessage(), e);
        }
    }

    public Map<String, String> getCurrentBot() {
        try {
            WebElement container = wait
                    .until(ExpectedConditions.presenceOfElementLocated(By.className("player-row-component")));
            WebElement nameEl = container.findElement(By.cssSelector("[data-test-element='user-tagline-username']"));
            WebElement ratingEl = container.findElement(By.className("cc-user-rating-white"));
            Map<String, String> info = new HashMap<>();
            info.put("name", nameEl.getText().trim());
            info.put("rating", ratingEl.getText().trim().replace("(", "").replace(")", ""));
            WebElement avatarEl = container.findElement(By.cssSelector("img.cc-avatar-img"));
            info.put("avatar", avatarEl.getDomProperty("src"));
            return info;
        } catch (Exception e) {
            System.out.printf("[WARN] Failed to fetch current bot info: %s%n", e.getMessage());
            return null;
        }
    }

    public void undoLastMove() throws Exception {
        try {
            while (waitingForEngineMove) {
                System.out.println("[INFO] Waiting for engine move to complete before undoing.");
                Thread.sleep(1000);
            }
            List<WebElement> nodes = driver.findElements(By.cssSelector("div.node"));
            if (nodes.size() < 2) {
                throw new Exception("Not enough moves to undo.");
            }

            WebElement undoButton = wait
                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector("button[aria-label='Move Back']")));
            Map<String, Piece> previousState = getBoardState();
            new Actions(driver).moveToElement(undoButton).click().perform();

            while (true) {
                Map<String, Piece> currentState = getBoardState();
                if (!currentState.equals(previousState))
                    break;
                else {
                    System.out.println("[INFO] Waiting for board to update after undo...");
                    Thread.sleep(500);
                }
            }

            // Click again as Python did
            undoButton = wait
                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector("button[aria-label='Move Back']")));
            new Actions(driver).moveToElement(undoButton).click().perform();
            System.out.println("[INFO] Last move undone.");
        } catch (Exception e) {
            throw new Exception("[ERROR] Failed to undo last move: " + e.getMessage(), e);
        }
    }

    public Map<String, Piece> getInitialBoardState() {
        Map<String, Piece> board = new HashMap<>();
        List<WebElement> pieces = driver.findElements(By.cssSelector("#board-analysis-board .piece"));

        for (WebElement piece : pieces) {
            String[] classes = piece.getAttribute("class").split("\\s+");
            String pieceType = null, color = null, square = null;
            for (String cls : classes) {
                if (cls.startsWith("w")) {
                    color = "white";
                    pieceType = cls.substring(1, 2);
                } else if (cls.startsWith("b")) {
                    color = "black";
                    pieceType = cls.substring(1, 2);
                } else if (cls.startsWith("square-")) {
                    square = cls.split("-")[1];
                }
            }
            if (pieceType != null && color != null && square != null) {
                board.put(square, new Piece(pieceType, color));
            }
        }
        return board;
    }

    public Map<String, Piece> getBoardState() {
        Map<String, Piece> board = new HashMap<>();
        List<WebElement> pieces = driver.findElements(By.cssSelector("#board-board .piece"));

        for (WebElement piece : pieces) {
            String[] classes = piece.getAttribute("class").split("\\s+");
            String pieceType = null, color = null, square = null;
            for (String cls : classes) {
                if (cls.startsWith("w")) {
                    color = "white";
                    pieceType = cls.substring(1, 2);
                } else if (cls.startsWith("b")) {
                    color = "black";
                    pieceType = cls.substring(1, 2);
                } else if (cls.startsWith("square-")) {
                    square = cls.split("-")[1];
                }
            }
            if (pieceType != null && color != null && square != null) {
                board.put(square, new Piece(pieceType, color));
            }
        }
        return board;
    }

    public void printBoardState() {
        Map<String, Piece> boardState = getBoardState();

        List<String> sorted = new ArrayList<>(boardState.keySet());
        sorted.sort(Comparator.comparingInt(s -> squareSortKey(squareIndexToAlg(s))));

        System.out.println("\n[BOARD STATE]");
        for (String square : sorted) {
            Piece p = boardState.get(square);
            System.out.printf("%s: %s%n", squareIndexToAlg(square), p.toString());
        }
        System.out.println();
    }

    private int squareSortKey(String alg) {
        // file a..h -> 1..8, rank 1..8
        int file = alg.charAt(0) - 'a' + 1;
        int rank = Character.getNumericValue(alg.charAt(1));
        return (8 - rank) * 8 + file - 1;
    }

    public void saveBoardStateToFile(Map<String, Piece> boardState, String filename) {
        try (FileWriter fw = new FileWriter(filename, false)) {
            fw.write("[BOARD STATE]\n");
            List<String> sorted = new ArrayList<>(boardState.keySet());
            sorted.sort(Comparator.comparingInt(s -> squareSortKey(squareIndexToAlg(s))));
            for (String square : sorted) {
                Piece p = boardState.get(square);
                fw.write(String.format("%s: %s%n", squareIndexToAlg(square), p.toString()));
            }
            System.out.printf("[DEBUG] Board state saved to %s%n", filename);
        } catch (IOException e) {
            System.out.printf("[ERROR] Could not write board state to file: %s%n", e.getMessage());
        }
    }

    public Map<String, String> getReadableBoardState(Map<String, Piece> state) {
        Map<String, String> readableState = new HashMap<>();
        for (Map.Entry<String, Piece> entry : state.entrySet()) {
            String alg = squareIndexToAlg(entry.getKey());
            final char colorLetter = entry.getValue().getColor().charAt(0);
            final char pieceLetter = entry.getValue().getType().toUpperCase().charAt(0);
            readableState.put(alg, String.format("%c%c", colorLetter, pieceLetter));
        }
        return readableState;
    }

    public Map<String, Object> waitForEngineMove(Map<String, Piece> previousState, int timeoutSeconds)
            throws TimeoutException {
        System.out.println("[INFO] Waiting for engine move...");
        waitingForEngineMove = true;
        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - start < timeoutMs) {
            Map<String, Piece> currentState = getBoardState();

            List<String> removedSquares = new ArrayList<>();
            for (String sq : previousState.keySet())
                if (!currentState.containsKey(sq))
                    removedSquares.add(sq);

            List<String> addedSquares = new ArrayList<>();
            for (String sq : currentState.keySet())
                if (!previousState.containsKey(sq))
                    addedSquares.add(sq);

            List<String> changedSquares = new ArrayList<>();
            for (String sq : currentState.keySet()) {
                if (previousState.containsKey(sq) && !currentState.get(sq).equals(previousState.get(sq))) {
                    changedSquares.add(sq);
                }
            }

            // Castling detection
            if (removedSquares.size() == 2 && addedSquares.size() == 2) {
                List<Piece> movedPieces = new ArrayList<>();
                for (String rs : removedSquares)
                    movedPieces.add(previousState.get(rs));
                boolean hasKing = movedPieces.stream()
                        .anyMatch(p -> "k".equals(p.getType()) && side.value().equals(p.getColor()));
                boolean hasRook = movedPieces.stream()
                        .anyMatch(p -> "r".equals(p.getType()) && side.value().equals(p.getColor()));
                if (hasKing && hasRook) {
                    String kingFrom = removedSquares.stream().filter(s -> "k".equals(previousState.get(s).getType()))
                            .findFirst().orElse(null);
                    String kingTo = addedSquares.stream().filter(s -> "k".equals(currentState.get(s).getType()))
                            .findFirst().orElse(null);
                    waitingForEngineMove = false;
                    // File scrnshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                    // System.out.println(scrnshot.getAbsolutePath());
                    Map<String, Object> ret = new HashMap<>();
                    ret.put("type", "castling");
                    ret.put("piece", "k");
                    ret.put("from", squareIndexToAlg(kingFrom));
                    ret.put("to", squareIndexToAlg(kingTo));
                    ret.put("color", side.value());
                    ret.put("state", getReadableBoardState(currentState));
                    return ret;
                }
            }

            // En passant detection
            if (removedSquares.size() == 2 && addedSquares.size() == 1) {
                String captureSq = removedSquares.get(0);
                String pawnSq = removedSquares.get(1);
                Piece pawn = previousState.get(pawnSq);
                if (pawn != null && "p".equals(pawn.getType())) {
                    System.out.println("[EN PASSANT DETECTED]");
                    waitingForEngineMove = false;
                    // File scrnshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                    // System.out.println(scrnshot.getAbsolutePath());
                    Map<String, Object> ret = new HashMap<>();
                    ret.put("type", "en_passant");
                    ret.put("piece", "p");
                    ret.put("from", squareIndexToAlg(pawnSq));
                    ret.put("to", squareIndexToAlg(addedSquares.get(0)));
                    ret.put("color", pawn.getColor());
                    ret.put("state", getReadableBoardState(currentState));
                    return ret;
                }
            }

            // Normal/capture
            if (removedSquares.size() == 1 && (addedSquares.size() == 1 || changedSquares.size() == 1)) {
                String fromSq = removedSquares.get(0);
                String toSq = addedSquares.size() == 1 ? addedSquares.get(0) : changedSquares.get(0);
                Piece piece = previousState.get(fromSq);
                System.out.printf("[ENGINE MOVE DETECTED] %s from %s to %s%n", piece.getType().toUpperCase(),
                        squareIndexToAlg(fromSq), squareIndexToAlg(toSq));
                waitingForEngineMove = false;
                // File scrnshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                // System.out.println(scrnshot.getAbsolutePath());
                Map<String, Object> ret = new HashMap<>();
                ret.put("piece", piece.getType());
                ret.put("from", squareIndexToAlg(fromSq));
                ret.put("to", squareIndexToAlg(toSq));
                ret.put("color", piece.getColor());
                ret.put("state", getReadableBoardState(currentState));
                return ret;
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }

        System.out.println("[ERROR] Timeout waiting for engine move.");
        waitingForEngineMove = false;
        // File scrnshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        // System.out.println(scrnshot.getAbsolutePath());
        // saveBoardStateToFile(previousState, "engine_timeout_state.txt");
        // saveBoardStateToFile(getBoardState(), "engine_timeout_state_1.txt");
        throw new TimeoutException("No move detected within the timeout period.");
    }

    public void completePromotion(String promoteTo) throws Exception {
        if (this.pendingPromotion == null)
            throw new Exception("No pending promotion to complete.");
        Map<String, String> selectorMap = Map.of("q", ".promotion-piece.wq, .promotion-piece.bq", "r",
                ".promotion-piece.wr, .promotion-piece.br", "b", ".promotion-piece.wb, .promotion-piece.bb", "n",
                ".promotion-piece.wn, .promotion-piece.bn");
        if (!selectorMap.containsKey(promoteTo))
            throw new IllegalArgumentException("Invalid piece for promotion");
        try {
            WebElement pieceButton = driver.findElement(By.cssSelector(selectorMap.get(promoteTo)));
            pieceButton.click();
            System.out.printf("[PROMOTION COMPLETE] Promoted to %s%n", promoteTo.toUpperCase());
        } catch (Exception e) {
            throw new Exception("[ERROR] Failed to complete promotion: " + e.getMessage(), e);
        }
        this.pendingPromotion = null;
    }

    public Map<String, Object> getNextBestMove(String opponentMove) throws Exception {
        if (this.pendingPromotion != null) {
            throw new Exception(
                    "[ERROR] Pending promotion detected. Complete it using completePromotion() before proceeding.");
        }
        if (opponentMove == null) {
            return waitForEngineMove(this.initialState, 35);
        }
        System.out.printf("[INFO] Simulating opponent move: %s%n", opponentMove);
        Map<String, Piece> before = simulateOpponentMove(opponentMove);
        System.out.println("[INFO] Capturing fresh board state after opponent move...");
        return waitForEngineMove(before, 35);
    }

    public Map<String, Piece> simulateBoardState(Map<String, Piece> state, String move) {
        Map<String, Piece> newState = new HashMap<>(state);
        String fromAlg = move.substring(0, 2);
        String toAlg = move.substring(2, 4);
        String fromSq = algToSquareIndex(fromAlg);
        String toSq = algToSquareIndex(toAlg);
        if (!newState.containsKey(fromSq))
            throw new IllegalArgumentException("[SIMULATE] No piece at " + fromAlg + " (" + fromSq + ")");

        Piece piece = newState.get(fromSq);
        String color = piece.getColor();

        // Castling
        if ("k".equals(piece.getType()) && Math.abs(fromAlg.charAt(0) - toAlg.charAt(0)) == 2) {
            String rookFrom = algToSquareIndex(("white".equals(color)) ? (toAlg.charAt(0) == 'g' ? "h1" : "a1")
                    : (toAlg.charAt(0) == 'g' ? "h8" : "a8"));
            String rookTo = algToSquareIndex(("white".equals(color)) ? (toAlg.charAt(0) == 'g' ? "f1" : "d1")
                    : (toAlg.charAt(0) == 'g' ? "f8" : "d8"));
            if (newState.containsKey(rookFrom)) {
                newState.put(rookTo, newState.remove(rookFrom));
            }
        }

        // En passant
        if ("p".equals(piece.getType()) && fromAlg.charAt(0) != toAlg.charAt(0) && !newState.containsKey(toSq)) {
            int captureRank = ("white".equals(color)) ? Integer.parseInt(String.valueOf(toAlg.charAt(1))) - 1
                    : Integer.parseInt(String.valueOf(toAlg.charAt(1))) + 1;
            String captureAlg = toAlg.charAt(0) + String.valueOf(captureRank);
            String captureSq = algToSquareIndex(captureAlg);
            if (newState.containsKey(captureSq) && "p".equals(newState.get(captureSq).getType())) {
                newState.remove(captureSq);
            }
        }

        newState.put(toSq, piece);
        newState.remove(fromSq);
        return newState;
    }

    public Map<String, Piece> simulateOpponentMove(String move) {
        System.out.printf("[INFO] Intended move: %s%n", move);
        if (move.length() < 4)
            throw new IllegalArgumentException("Moves should be like 'e2e4'");

        String fromAlg = move.substring(0, 2);
        String toAlg = move.substring(2, 4);

        String fromSq = algToSquareIndex(fromAlg);
        String toSq = algToSquareIndex(toAlg);

        try {
            WebElement pieceEl = driver.findElement(By.cssSelector(".piece.square-" + fromSq));
            // System.out.printf("[DEBUG] Found piece at %s (square-%s)%n", fromAlg,
            // fromSq);
            new Actions(driver).moveToElement(pieceEl).click().perform();
        } catch (Exception e) {
            // File scrnshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            // System.out.println(scrnshot.getAbsolutePath());
            System.out.printf("[ERROR] Could not find piece at %s: %s%n", fromAlg, e.getMessage());
            throw new IllegalArgumentException("Could not find piece at " + fromAlg);
        }

        try {
            final String desiredHintSelector = String.format(".hint.square-%s, .capture-hint.square-%s", toSq, toSq);
            WebElement hintEl = (new WebDriverWait(driver, Duration.ofSeconds(3))).until(d -> {
                List<WebElement> elts = d.findElements(By.cssSelector(desiredHintSelector));
                return !elts.isEmpty() ? elts.get(0) : null;
            });

            Map<String, Piece> before = simulateBoardState(getBoardState(), move);
            new Actions(driver).moveToElement(hintEl).click().perform();
            System.out.printf("[INFO] Opponent move %s → %s completed via hint square.%n", fromAlg, toAlg);

            // Check for promotion window
            try {
                WebElement promotionWindow = driver.findElement(By.cssSelector(".promotion-window"));
                if (promotionWindow != null) {
                    this.pendingPromotion = new HashMap<>();
                    this.pendingPromotion.put("from", new Piece(fromAlg, fromAlg));
                    // Store minimal info
                    System.out.printf("[PROMOTION DETECTED] Promotion required at %s.%n", toAlg);
                }
            } catch (NoSuchElementException ignored) {
            }

            return before;
        } catch (Exception e) {
            // File scrnshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            // System.out.println(scrnshot.getAbsolutePath());
            System.out.printf("[ERROR] Failed to click hint square %s: %s%n", toAlg, e.getMessage());
            throw new IllegalArgumentException("Invalid move from " + fromAlg + " to " + toAlg);
        }
    }

    public String algToSquareIndex(String alg) {
        if (alg == null || alg.length() != 2 || "abcdefgh".indexOf(alg.charAt(0)) < 0
                || "12345678".indexOf(alg.charAt(1)) < 0) {
            throw new IllegalArgumentException("Invalid algebraic notation: " + alg);
        }
        int file = alg.charAt(0) - 'a' + 1;
        int rank = Character.getNumericValue(alg.charAt(1));
        return String.format("%d%d", file, rank);
    }

    public String squareIndexToAlg(String squareId) {
        if (squareId == null || squareId.length() != 2)
            return "?";
        Map<Character, Character> colMap = new HashMap<>();
        for (int i = 1; i <= 8; i++)
            colMap.put((char) ('0' + i), (char) ('a' + i - 1));
        char col = squareId.charAt(0);
        char row = squareId.charAt(1);
        return String.format("%c%c", colMap.getOrDefault(col, '?'), row);
    }

    public void quit() {
        try {
            driver.quit();
        } catch (Exception e) {
            System.out.printf("[ERROR] Failed to exit driver: ");
            e.printStackTrace();
        }
    }
}
