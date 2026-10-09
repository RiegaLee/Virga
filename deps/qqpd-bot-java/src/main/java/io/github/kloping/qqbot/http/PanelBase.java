package io.github.kloping.qqbot.http;

import io.github.kloping.qqbot.Resource;
import io.github.kloping.qqbot.Start0;
import io.github.kloping.qqbot.Starter;
import io.github.kloping.qqbot.http.data.PanelDefinition;
import io.github.kloping.qqbot.http.data.PanelListResult;
import io.github.kloping.qqbot.http.data.PanelRequest;
import io.github.kloping.qqbot.http.data.PanelRecord;
import io.github.kloping.qqbot.http.data.PanelResult;
import io.github.kloping.qqbot.http.data.PanelTargetRequest;
import io.github.kloping.spt.annotations.AutoStand;
import io.github.kloping.spt.annotations.Entity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** QQ 指令面板 API。 */
@Entity
public class PanelBase {
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 15_000;

    @AutoStand
    private Start0 start0;

    @AutoStand
    private Starter.Config config;

    /** 查询指定作用域下的指令面板。 */
    public PanelListResult list(String scope, int limit) throws IOException {
        String path = "/v2/panels?scope=" + encode(scope) + "&limit=" + limit;
        String response = execute("GET", path, null);
        if (response.isEmpty()) return new PanelListResult();
        return Resource.GSON.fromJson(response, PanelListResult.class);
    }

    /** 创建指令面板。 */
    public PanelResult create(PanelRequest request) throws IOException {
        String response = execute("POST", "/v2/panels", request);
        if (response.isEmpty()) return new PanelResult();
        return Resource.GSON.fromJson(response, PanelResult.class);
    }

    /** 查询指定指令面板详情，包括平台返回时的关联对象。 */
    public PanelRecord get(String panelId) throws IOException {
        String response = execute("GET", "/v2/panels/" + encode(panelId), null);
        if (response.isEmpty()) return new PanelRecord();
        return Resource.GSON.fromJson(response, PanelRecord.class);
    }

    /** 更新指定指令面板，保留原 panel_id。 */
    public PanelResult update(String panelId, PanelDefinition panel) throws IOException {
        String response = execute(
                "PUT",
                "/v2/panels/" + encode(panelId),
                Collections.singletonMap("panel", panel)
        );
        if (response.isEmpty()) return new PanelResult();
        return Resource.GSON.fromJson(response, PanelResult.class);
    }

    /** 添加或移除指定指令面板关联的群。 */
    public void updateTargets(String panelId, String operation, List<String> groupOpenIds) throws IOException {
        execute(
                "PUT",
                "/v2/panels/" + encode(panelId) + "/target",
                new PanelTargetRequest(operation, groupOpenIds)
        );
    }

    /** 删除指定指令面板。 */
    public void delete(String panelId) throws IOException {
        execute("DELETE", "/v2/panels/" + encode(panelId), null);
    }

    private String execute(String method, String path, Object requestBody) throws IOException {
        String baseUrl = config.isSandbox() ? Starter.SANDBOX_NET_MAIN : Starter.NET_MAIN;
        HttpURLConnection connection = (HttpURLConnection) new URL(trimTrailingSlash(baseUrl) + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");
            for (Map.Entry<String, String> header : start0.getHeaders().entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }

            if (requestBody != null) {
                byte[] body = Resource.GSON.toJson(requestBody).getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }

            int statusCode = connection.getResponseCode();
            String response = readResponse(connection, statusCode);
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("QQ panel API " + method + " " + path + " failed with HTTP "
                        + statusCode + (response.isEmpty() ? "" : ": " + response));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String readResponse(HttpURLConnection connection, int statusCode) throws IOException {
        InputStream input = statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (input == null) return "";

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
