package com.example;
import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.logging.Logger;
/**
 * Orchestrator to fetch top 20 USDC pairs by 24h quote volume and generate systemd service files for each TradingBot instance.
 * Each service will run the TradingBot JAR with the symbol as argument.
 *
 * Assumptions:
 * - TradingBot has been modified to accept symbol as args[0] (e.g., private static String SYMBOL = args.length > 0 ? args[0] : "SPKUSDC"; at the start of main).
 * - config.properties is in the classpath for API keys.
 * - The JAR is named "tradingbot.jar" and located in the working directory.
 * - Working directory is ~/trader_bots (created if needed).
 * - Run this program with sudo to automatically install and start the services.
 * - If not run with sudo, it will generate files but print manual installation instructions.
 * - Metric: 24h quote volume (in USDC) for active SPOT trading pairs ending with "USDC".
 */
public class BotOrchestrator {
    private static final Logger logger = Logger.getLogger(BotOrchestrator.class.getName());
    private static final int TOP_N = 20;
    private static final String QUOTE_ASSET = "USDC";
    private static final String TRADING_STATUS = "TRADING";
    private static final String JAR_NAME = "tradingbot.jar";
    public static void main(String[] args) throws Exception {
        // Determine real user and home (handles running with sudo)
        String sudoUser = System.getenv("SUDO_USER");
        String userName;
        String home;
        if (sudoUser != null && !sudoUser.isEmpty()) {
            userName = sudoUser;
            home = "/home/" + userName;
        } else {
            userName = System.getProperty("user.name");
            home = System.getProperty("user.home");
        }
        String currentUser = System.getProperty("user.name");
        boolean isRoot = "root".equals(currentUser);
        // Load API credentials (same as TradingBot)
        Properties props = new Properties();
        try (InputStream input = BotOrchestrator.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new IllegalStateException("config.properties not found");
            }
            props.load(input);
        }
        String apiKey = props.getProperty("api.key");
        String secretKey = props.getProperty("api.secret");
        if (apiKey == null || secretKey == null) {
            throw new IllegalStateException("API credentials missing in config.properties");
        }
        logger.info("Loaded API credentials from config.properties");
        SpotClient client = new SpotClientImpl(apiKey, secretKey);
        // Fetch all 24hr tickers
        String tickersJson = client.createMarket().ticker24H(new LinkedHashMap<>());
        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tickers = mapper.readValue(tickersJson, List.class);
        // Filter USDC pairs that are trading, sort by quoteVolume descending
        List<Map<String, Object>> usdcPairs = new ArrayList<>();
        for (Map<String, Object> ticker : tickers) {
            String symbol = (String) ticker.get("symbol");
            if (symbol != null && symbol.endsWith(QUOTE_ASSET) && TRADING_STATUS.equals(ticker.get("status"))) {
                usdcPairs.add(ticker);
            }
        }
        usdcPairs.sort((a, b) -> {
            double volA = Double.parseDouble((String) a.get("quoteVolume"));
            double volB = Double.parseDouble((String) b.get("quoteVolume"));
            return Double.compare(volB, volA); // Descending
        });
        // Take top 20
        List<String> topSymbols = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_N, usdcPairs.size()); i++) {
            String symbol = (String) usdcPairs.get(i).get("symbol");
            topSymbols.add(symbol);
            logger.info(String.format("Top %d: %s with quoteVolume %.2f USDC", i + 1, symbol,
                    Double.parseDouble((String) usdcPairs.get(i).get("quoteVolume"))));
        }
        // Working directory and JAR path
        String workingDir = home + "/trader_bots";
        String jarPath = workingDir + "/" + JAR_NAME;
        // Create working directory if not exists
        File dir = new File(workingDir);
        if (!dir.exists()) {
            if (dir.mkdirs()) {
                logger.info("Created working directory: " + workingDir);
            } else {
                throw new IOException("Failed to create working directory: " + workingDir);
            }
        }
        // Generate service files
        for (String symbol : topSymbols) {
            generateServiceFile(symbol, workingDir, jarPath, userName);
        }
        if (topSymbols.isEmpty()) {
            logger.info("No USDC pairs found. Exiting.");
            return;
        }
        // Automatic installation if running as root, else manual instructions
        if (isRoot) {
            try {
                // Copy all service files
                executeCommand("cp", workingDir + "/tradebot_*.service", "/etc/systemd/system/");
                // Reload daemon
                executeCommand("systemctl", "daemon-reload");
                // Enable and start each service
                for (String symbol : topSymbols) {
                    String serviceName = "tradebot_" + symbol.toLowerCase() + ".service";
                    executeCommand("systemctl", "enable", serviceName);
                    executeCommand("systemctl", "start", serviceName);
                }
                logger.info("Services installed, enabled, and started automatically.");
                logger.info("Monitor with: systemctl status tradebot_<symbol>.service (run as sudo if needed)");
                logger.info("Logs in: " + workingDir + "/output_<symbol>.log");
            } catch (Exception e) {
                logger.severe("Failed to install/manage services: " + e.getMessage());
            }
        } else {
            // Manual instructions if not root
            logger.warning("Not running as root. Services generated but not installed. Run this program with sudo for automatic installation.");
            logger.info("To install and run manually:\n" +
                    "sudo cp " + workingDir + "/tradebot_*.service /etc/systemd/system/\n" +
                    "sudo systemctl daemon-reload\n" +
                    "Then, for each service (or use a loop):\n" +
                    "sudo systemctl enable tradebot_<symbol>.service\n" +
                    "sudo systemctl start tradebot_<symbol>.service\n" +
                    "Monitor with: sudo systemctl status tradebot_<symbol>.service\n" +
                    "Logs in: " + workingDir + "/output_<symbol>.log");
        }
    }
    private static void generateServiceFile(String symbol, String workingDir, String jarPath, String userName) throws IOException {
        String serviceName = "tradebot_" + symbol.toLowerCase() + ".service";
        String filePath = workingDir + "/" + serviceName;
        String logPath = workingDir + "/output_" + symbol.toLowerCase() + ".log";
        String content = "[Unit]\n" +
                "Description=Trading Bot for " + symbol + "\n" +
                "After=network.target\n" +
                "\n" +
                "[Service]\n" +
                "User=" + userName + "\n" +
                "WorkingDirectory=" + workingDir + "\n" +
                "ExecStart=/usr/bin/java -jar " + jarPath + " " + symbol + "\n" +
                "StandardOutput=append:" + logPath + "\n" +
                "StandardError=append:" + logPath + "\n" +
                "Restart=on-failure\n" +
                "RestartSec=10\n" +
                "\n" +
                "[Install]\n" +
                "WantedBy=multi-user.target\n";
        try (FileWriter writer = new FileWriter(filePath)) {
            writer.write(content);
        }
        logger.info("Generated service file: " + filePath);
    }
    private static void executeCommand(String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.info(line);
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("Command '" + String.join(" ", command) + "' failed with exit code " + exit);
        }
    }
}
