package com.kzagent.kagent.mcp;

import java.nio.file.*;
import java.io.*;
import java.util.regex.*;

/** Separate JVM fixture: no Node/Python dependency in portable test runs. */
public final class McpFixture {
    public static void main(String[] args) throws Exception {
        Path log = Path.of(args[0]);
        Files.writeString(log, "PID:" + ProcessHandle.current().pid() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        String version = args.length > 1 ? args[1] : "2025-11-25";
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            Matcher method = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"").matcher(line);
            if (!method.find()) continue;
            String name = method.group(1);
            Files.writeString(log, name + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Matcher id = Pattern.compile("\"id\"\\s*:\\s*(\"[^\"]*\"|[0-9]+)").matcher(line);
            if (!id.find()) continue;
            String result;
            if (name.equals("initialize")) result = "{\"protocolVersion\":\"" + version + "\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
            else if (name.equals("tools/list")) result = line.contains("page2")
                ? "{\"tools\":[{\"name\":\"task\",\"description\":\"task\",\"inputSchema\":{\"type\":\"object\"},\"execution\":{\"taskSupport\":\"required\"}}]}"
                : "{\"tools\":[{\"name\":\"echo\",\"description\":\"fixture echo\",\"inputSchema\":{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false,\"oneOf\":[{},{}],\"$defs\":{\"x\":{\"type\":\"string\"}}}},{\"name\":\"slow\",\"description\":\"slow\",\"inputSchema\":{\"type\":\"object\"}}],\"nextCursor\":\"page2\"}";
            else if (name.equals("tools/call")) {
                if (line.contains("slow")) continue;
                result = "{\"content\":[{\"type\":\"text\",\"text\":\"fixture-ok\"}],\"structuredContent\":{\"ok\":true},\"isError\":false}";
            } else result = "{}";
            System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id.group(1) + ",\"result\":" + result + "}");
            System.out.flush();
        }
    }
}
