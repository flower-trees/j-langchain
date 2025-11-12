package org.salt.jlangchain.rag.tools.mcp.server;

import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.rag.tools.mcp.server.config.ServerConfig;
import org.salt.jlangchain.rag.tools.mcp.server.param.McpException;
import org.salt.jlangchain.rag.tools.mcp.server.param.McpRequest;
import org.salt.jlangchain.rag.tools.mcp.server.param.McpResponse;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class McpServerConnection extends AbstractMcpConnection {

    private Process serverProcess;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private BufferedReader stderr;

    public McpServerConnection(String serverName, ServerConfig config) {
        super(serverName, config);
    }

    @Override
    public ConnectionType getConnectionType() {
        return ConnectionType.STDIO;
    }

    @Override
    public void connect() throws IOException {
        List<String> command = new ArrayList<>();
        command.add(config.command);
        command.addAll(config.args);

        ProcessBuilder pb = new ProcessBuilder(command);

        if (config.env != null) {
            pb.environment().putAll(config.env);
        }

        serverProcess = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(serverProcess.getOutputStream()));
        stdout = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()));
        stderr = new BufferedReader(new InputStreamReader(serverProcess.getErrorStream()));

        connected = true;

        startErrorListener();

        try {
            performHandshake();
        } catch (Exception e) {
            throw new IOException("Handshake failed", e);
        }
    }

    private void startErrorListener() {
        Thread errorThread = new Thread(() -> {
            try {
                String line;
                while ((line = stderr.readLine()) != null) {
                    if (line.isEmpty() || line.contains("error")) {
                        log.error("[{} ERROR]: {}", serverName, line);
                        lastError = line;
                    } else {
                        log.info("[{} INFO]: {}", serverName, line);
                    }
                }
            } catch (IOException e) {
                log.error("[{}] Error reading stderr: {}", serverName, e.getMessage());
                lastError = "Error reading stderr: " + e.getMessage();
            }
        });
        errorThread.setDaemon(true);
        errorThread.start();
    }

    @Override
    public synchronized McpResponse sendRequest(String method, Object params) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to server: " + serverName);
        }

        McpRequest request = new McpRequest();
        request.jsonrpc = "2.0";
        request.id = nextRequestId();
        request.method = method;
        request.params = params;

        String requestJson = mapper.writeValueAsString(request);
        stdin.write(requestJson + "\n");
        stdin.flush();

        String responseJson = stdout.readLine();
        if (responseJson == null) {
            throw new IOException("Server closed connection: " + serverName);
        }

        McpResponse response = mapper.readValue(responseJson, McpResponse.class);

        if (response.error != null) {
            throw new McpException(response.error.code, response.error.message, response.error.data);
        }

        return response;
    }

    @Override
    protected void sendNotification(String method, Object params) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to server: " + serverName);
        }

        Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);

        String notificationJson = mapper.writeValueAsString(notification);
        stdin.write(notificationJson + "\n");
        stdin.flush();

        log.debug("[{}] Sent notification: {}", serverName, method);
    }

    @Override
    public boolean isConnected() {
        return connected && serverProcess != null && serverProcess.isAlive();
    }

    @Override
    public void close() {
        connected = false;
        try {
            if (stdin != null) stdin.close();
            if (stdout != null) stdout.close();
            if (stderr != null) stderr.close();
            if (serverProcess != null) {
                serverProcess.destroy();
                if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
                    serverProcess.destroyForcibly();
                }
            }
        } catch (Exception e) {
            log.error("[{}] Error closing connection: {}", serverName, e.getMessage());
        }
        log.info("[{}] Connection closed", serverName);
    }
}