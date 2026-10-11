package com.replyassistant.android;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Personal-only DeepSeek request path for the P6 text assistant. */
final class DirectChatClient {
    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final int MAX_OUTPUT_TOKENS = 1024;
    private static final Set<String> REPLY_TYPES = new HashSet<>(Arrays.asList(
            "轻松接梗", "互动选择", "悬念引导", "具体延展", "轻微暧昧"));

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status) { super("DeepSeek HTTP " + status); this.status = status; }
    }

    private DirectChatClient() { }

    static JSONObject generate(Context context, JSONObject request) throws Exception {
        String prompt = "你是暧昧对象持续聊天助手。根据当前任务生成 3 条同一种回复类型、具体自然且容易接话的微信候选。"
                + "只能使用用户提供的事实；路线 general 不得编造用户经历，尤其不能把用户没提供的‘我去过/我喜欢/我看过/我做过/我昨晚/我经常’写成既定事实。"
                + "三条候选必须在开场方式、问题角度或推进力度上至少有一项明显不同。"
                + "避免连续追问、空泛寒暄和过度施压。"
                + "可用回复类型：轻松接梗、互动选择、悬念引导、具体延展、轻微暧昧。"
                + "只返回 JSON，不要 Markdown，格式为："
                + "{\"recommended_type\":\"具体延展\",\"recommendation_reason\":\"理由\",\"replies\":[\"候选1\",\"候选2\",\"候选3\"],\"need_more_information\":false,\"message\":\"\"}。"
                + "如果信息不足，replies 可以为空并将 need_more_information 设为 true。\n"
                + request.toString();
        if (DirectAiSettings.nearBudget(context, prompt.length(), MAX_OUTPUT_TOKENS)) {
            throw new IllegalStateException("本机估算费用已接近 1 元试用预算，已暂停新的请求；请到 DeepSeek 平台核对实际账单");
        }

        JSONObject body = new JSONObject();
        body.put("model", "deepseek-flash");
        body.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", "你是暧昧对象聊天助手，只输出符合要求的 JSON。"))
                .put(new JSONObject().put("role", "user").put("content", prompt)));
        body.put("thinking", new JSONObject().put("type", "disabled"));
        body.put("response_format", new JSONObject().put("type", "json_object"));
        body.put("max_tokens", MAX_OUTPUT_TOKENS);
        body.put("stream", false);

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(30000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Authorization", "Bearer " + DirectAiSettings.readKey(context));
            byte[] encoded = body.toString().getBytes("UTF-8");
            try (OutputStream output = connection.getOutputStream()) { output.write(encoded); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new ApiException(status);
            JSONObject apiResponse = new JSONObject(readLimited(connection.getInputStream()));

            // Record usage before validating the model's text: malformed output can still be billed.
            JSONObject usage = apiResponse.optJSONObject("usage");
            long fallbackPromptTokens = Math.max(1L, prompt.length() * 2L);
            long promptTokens = usage == null ? fallbackPromptTokens
                    : usage.optLong("prompt_tokens", fallbackPromptTokens);
            long completionTokens = usage == null ? MAX_OUTPUT_TOKENS
                    : usage.optLong("completion_tokens", MAX_OUTPUT_TOKENS);
            if (promptTokens <= 0) promptTokens = fallbackPromptTokens;
            if (completionTokens <= 0) completionTokens = MAX_OUTPUT_TOKENS;
            DirectAiSettings.recordUsage(context, promptTokens, completionTokens);

            JSONArray choices = apiResponse.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new IOException("AI 没有返回候选");
            JSONObject choice = choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            String content = message == null ? "" : message.optString("content", "").trim();
            if (content.startsWith("```")) {
                content = content.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
            }
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                content = content.substring(jsonStart, jsonEnd + 1).trim();
            }
            if (content.isEmpty()) throw new IOException("AI 返回了空内容");
            return validateReply(request, new JSONObject(content));
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(InputStream stream) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
            char[] buffer = new char[2048]; int count;
            while ((count = reader.read(buffer)) != -1) {
                if (result.length() + count > 131072) throw new IOException("AI 返回内容过长");
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
    }

    private static JSONObject validateReply(JSONObject request, JSONObject answer) throws Exception {
        boolean moreInformation = answer.optBoolean("need_more_information", false);
        String type = answer.optString("recommended_type", "").trim();
        if (!type.isEmpty() && !REPLY_TYPES.contains(type)) throw new IOException("AI 返回的回复类型无效");
        String requested = request.optString("reply_type", "");
        if (!moreInformation && (type.isEmpty() || (!requested.isEmpty() && !requested.equals(type)))) {
            throw new IOException("AI 返回的回复类型与选择不一致");
        }
        JSONArray replies = answer.optJSONArray("replies");
        if (replies == null || (!moreInformation && replies.length() != 3) || replies.length() > 3) {
            throw new IOException("AI 没有返回三条完整候选");
        }
        JSONArray clean = new JSONArray();
        for (int i = 0; i < replies.length(); i++) {
            Object reply = replies.opt(i);
            if (!(reply instanceof String)) throw new IOException("AI 返回了非文字候选");
            String value = ((String) reply).trim();
            if (value.isEmpty()) throw new IOException("AI 返回了空候选");
            clean.put(value);
        }
        return new JSONObject().put("recommended_type", type)
                .put("recommendation_reason", answer.optString("recommendation_reason", ""))
                .put("replies", clean)
                .put("need_more_information", moreInformation)
                .put("message", answer.optString("message", ""));
    }
}
