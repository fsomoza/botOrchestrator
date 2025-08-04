# Bot Orchestrator

Java Maven project that fetches the top 20 USDC pairs from Binance and generates systemd service files for each TradingBot instance.

## Build

Create a `src/main/resources/config.properties` file with your Binance API credentials before packaging:

```
api.key=YOUR_API_KEY
api.secret=YOUR_SECRET_KEY
```

Then package the project:

```bash
mvn package
```

## Cleanup

To stop, disable, and remove all generated services and their systemd files, run the orchestrator with the `delete` flag:

```bash
java -jar target/bot-orchestrator-1.0-SNAPSHOT.jar delete
```
