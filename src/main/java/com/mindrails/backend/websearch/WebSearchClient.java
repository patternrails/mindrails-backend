package com.mindrails.backend.websearch;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.tool.ToolFailure;
import dev.langchain4j.invocation.InvocationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public final class WebSearchClient implements AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final int RESULT_BUDGET = 24_000;
    private static final String INVALID_RESPONSE = "Web search returned an invalid response";
    private final WebSearchProviderRegistry registry;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    public WebSearchClient(WebSearchProviderRegistry registry, ObjectMapper json) {
        this.registry = registry;
        this.json = json;
    }

    public record Page(String title, String url, String snippet, String summary, String siteName, String datePublished) {}
    public record Result(String query, long totalEstimatedMatches, List<Page> pages, boolean truncated) {}

    public Result search(String query, String freshness, Integer count, Boolean summary,
            String include, String exclude, InvocationContext context) {
        LlmClient.checkCancelled(context);
        var connection = registry.activeConnection().orElseThrow(
                () -> new ToolFailure("No active web search provider. Ask an administrator to configure one."));
        return search(connection, query, freshness, count, summary, include, exclude, context);
    }

    Result search(WebSearchProviderRegistry.Connection connection, String query, String freshness, Integer count,
            Boolean summary, String include, String exclude, InvocationContext context) {
        var provider = connection.provider();
        var search = WebSearchQuery.create(provider, query, freshness, count, summary, include, exclude);
        Map<String, Object> payload = switch (provider.type()) {
            case WebSearchProvider.OPENAI -> openAiRequest(provider, search);
            case WebSearchProvider.DEEPSEEK -> deepSeekRequest(provider, search);
            case WebSearchProvider.BOCHA -> bochaRequest(search);
            default -> throw new ToolFailure("Unsupported web search provider");
        };
        LlmClient.checkCancelled(context);
        JsonNode response = post(connection, payload, context);
        Result result = switch (provider.type()) {
            case WebSearchProvider.OPENAI -> openAiResult(search, response);
            case WebSearchProvider.DEEPSEEK -> rankedResult(search, response.path("results"), 0, false);
            case WebSearchProvider.BOCHA -> bochaResult(search, response);
            default -> throw new ToolFailure("Unsupported web search provider");
        };
        LlmClient.checkCancelled(context);
        return result;
    }

    private static Map<String, Object> bochaRequest(WebSearchQuery search) {
        var body = new LinkedHashMap<String, Object>();
        body.put("query", search.text());
        body.put("freshness", search.freshness());
        body.put("count", search.count());
        body.put("summary", search.summary());
        if (!search.include().isEmpty()) body.put("include", String.join("|", search.include()));
        if (!search.exclude().isEmpty()) body.put("exclude", String.join("|", search.exclude()));
        return body;
    }

    private static Map<String, Object> deepSeekRequest(WebSearchProvider provider, WebSearchQuery search) {
        var query = new StringBuilder(search.text());
        if (!search.include().isEmpty()) {
            query.append(" ").append(String.join(" OR ", search.include().stream().map(site -> "site:" + site).toList()));
        }
        for (String site : search.exclude()) query.append(" -site:").append(site);
        var dates = search.dates();
        if (dates != null) {
            query.append(" after:").append(dates.start().minusDays(1));
            query.append(" before:").append(dates.end().plusDays(1));
        }
        if (query.codePointCount(0, query.length()) > 128) {
            throw new IllegalArgumentException("DeepSeek search query, including filters, must contain at most 128 characters");
        }
        return Map.of("query", query.toString(), "count", search.count(),
                "need_content", search.summary() && Boolean.TRUE.equals(provider.options().get("need_content")));
    }

    private static Map<String, Object> openAiRequest(WebSearchProvider provider, WebSearchQuery search) {
        var filters = new LinkedHashMap<String, Object>();
        if (!search.include().isEmpty()) filters.put("allowed_domains", search.include());
        if (!search.exclude().isEmpty()) filters.put("blocked_domains", search.exclude());
        var tool = new LinkedHashMap<String, Object>();
        tool.put("type", "web_search");
        tool.putAll(provider.options());
        if (!filters.isEmpty()) tool.put("filters", filters);
        String instructions = "Search the public web for the supplied query and cite the sources.";
        var dates = search.dates();
        if (dates != null) {
            instructions += " Look for sources published between " + dates.start() + " and " + dates.end() + " inclusive.";
        }
        return Map.of("model", provider.modelName(), "input", search.text(), "instructions", instructions,
                "tools", List.of(tool), "tool_choice", "required", "store", false,
                "include", List.of("web_search_call.action.sources"));
    }

    private JsonNode post(WebSearchProviderRegistry.Connection connection, Map<String, Object> payload,
            InvocationContext context) {
        var provider = connection.provider();
        int timeoutMillis = provider.timeoutMillis();
        var request = HttpRequest.newBuilder(URI.create(provider.endpoint()))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("Authorization", "Bearer " + connection.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        var future = http.sendAsync(request, info -> new LimitedResponseBody());
        try {
            HttpResponse<String> response;
            while (true) {
                LlmClient.checkCancelled(context);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new ToolFailure("Web search timed out; retry later");
                try {
                    response = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                    break;
                } catch (TimeoutException pending) {
                    // Check cancellation and the total request deadline between waits.
                }
            }
            if (response.statusCode() != 200) throw new ToolFailure(error(response.statusCode()));
            try {
                return json.readTree(response.body());
            } catch (tools.jackson.core.JacksonException invalid) {
                throw new ToolFailure(INVALID_RESPONSE);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Web search interrupted");
        } catch (ExecutionException unavailable) {
            if (unavailable.getCause() instanceof ResponseTooLarge) {
                throw new ToolFailure("Web search response exceeded the size limit");
            }
            throw new ToolFailure("Web search failed or timed out; retry later");
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private static Result bochaResult(WebSearchQuery search, JsonNode response) {
        if (!"200".equals(response.path("code").asText())) {
            throw new ToolFailure("Web search returned an unsuccessful response");
        }
        var data = response.path("data");
        if (!data.isObject()) throw new ToolFailure(INVALID_RESPONSE);
        var webPages = data.path("webPages");
        if (webPages.isMissingNode() || webPages.isNull()) return result(search, 0, List.of());
        return rankedResult(search, webPages.path("value"), webPages.path("totalEstimatedMatches").asLong(0), true);
    }

    private static Result rankedResult(WebSearchQuery search, JsonNode items, long total, boolean bocha) {
        if (!items.isArray()) throw new ToolFailure(INVALID_RESPONSE);
        var pages = new ArrayList<Page>();
        for (var item : items) {
            String excerpt = item.path("summary").asText("");
            if (!bocha && excerpt.isBlank()) excerpt = item.path("content").asText("");
            pages.add(new Page(item.path(bocha ? "name" : "title").asText(""), item.path("url").asText(""),
                    item.path("snippet").asText(""), excerpt, item.path(bocha ? "siteName" : "site_name").asText(""),
                    item.path(bocha ? "datePublished" : "date").asText("")));
        }
        return result(search, total, pages);
    }

    private static Result openAiResult(WebSearchQuery search, JsonNode response) {
        var output = response.path("output");
        if (!"completed".equals(response.path("status").asText()) || !output.isArray()) {
            throw new ToolFailure("OpenAI did not complete the web search request");
        }
        var cited = new ArrayList<Page>();
        var sources = new ArrayList<Page>();
        boolean searched = false;
        for (var item : output) {
            switch (item.path("type").asText()) {
                case "web_search_call" -> {
                    searched |= "completed".equals(item.path("status").asText());
                    for (var source : item.path("action").path("sources")) sources.add(source(source));
                }
                case "message" -> {
                    for (var content : item.path("content")) {
                        for (var annotation : content.path("annotations")) {
                            if ("url_citation".equals(annotation.path("type").asText())) cited.add(source(annotation));
                        }
                    }
                }
                default -> { }
            }
        }
        if (!searched) throw new ToolFailure("OpenAI did not complete a web search");
        cited.addAll(sources);
        return result(search, 0, cited);
    }

    private static Page source(JsonNode source) {
        return new Page(source.path("title").asText(""), source.path("url").asText(""), "", "", "", "");
    }

    private static Result result(WebSearchQuery search, long total, List<Page> candidates) {
        var urls = new HashSet<String>();
        var pages = new ArrayList<Page>();
        int remaining = RESULT_BUDGET;
        boolean truncated = false;
        for (Page candidate : candidates) {
            String url = candidate.url();
            if ((!url.startsWith("https://") && !url.startsWith("http://")) || !urls.add(url)) continue;
            var page = new Page(clip(candidate.title(), 300), url, clip(candidate.snippet(), 1000),
                    search.summary() ? clip(candidate.summary(), 2500) : "",
                    clip(candidate.siteName(), 200), clip(candidate.datePublished(), 100));
            int chars = page.title().length() + url.length() + page.snippet().length() + page.summary().length()
                    + page.siteName().length() + page.datePublished().length();
            if (pages.size() >= search.count() || remaining < chars) {
                truncated = true;
                break;
            }
            remaining -= chars;
            pages.add(page);
        }
        return new Result(search.text(), Math.max(0, total), List.copyOf(pages), truncated);
    }

    private static String error(int status) {
        return switch (status) {
            case 401 -> "Web search authentication failed";
            case 402, 403 -> "Web search access denied or balance exhausted";
            case 429 -> "Web search rate limited; retry later";
            default -> "Web search request failed (HTTP " + status + ")";
        };
    }

    private static String clip(String value, int limit) {
        if (value.length() <= limit) return value;
        int end = limit - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }

    private static final class LimitedResponseBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate =
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private long received;

        @Override
        public CompletionStage<String> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > MAX_RESPONSE_BYTES) {
                subscription.cancel();
                delegate.onError(new ResponseTooLarge());
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }

    private static final class ResponseTooLarge extends IOException {}

    @Override public void close() {
        http.close();
    }
}
