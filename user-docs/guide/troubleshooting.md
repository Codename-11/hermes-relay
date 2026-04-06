# Troubleshooting

## Can't connect to API server

- **Red dot in chat header**: API server is unreachable
- Check that Hermes is running: `hermes gateway`
- Verify `API_SERVER_ENABLED=true` in `~/.hermes/.env`
- Ensure `API_SERVER_HOST=0.0.0.0` (not `127.0.0.1`) for network access
- Check firewall rules on the server
- Try the URL in a browser: `http://your-server:8642/health`

## Messages not streaming

- Check your API key is correct
- Look for error banners in the chat — tap **Retry** to resend
- Check the Hermes server logs for errors

## "No internet connection" banner

- The app detected network loss via Android's ConnectivityManager
- Check your WiFi/mobile data connection
- The banner disappears automatically when connectivity returns

## Session history not loading

- The server must be reachable when switching sessions
- Large sessions may take a moment to load — watch for the loading indicator

## App crashes on startup

- Clear app data: Settings > Apps > Hermes Relay > Clear Data
- Re-enter your API server URL and key during onboarding
