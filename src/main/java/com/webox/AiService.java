package com.webox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class AiService {
 private static final Logger log = LoggerFactory.getLogger(AiService.class);

 public record Recommendation(Long dishId, String reason) {}

 private final String apiKey;
 private final String baseUrl;
 private final String model;
 private final int timeoutSeconds;
 private final ObjectMapper mapper = new ObjectMapper();
 private final HttpClient httpClient = HttpClient.newBuilder()
         .connectTimeout(Duration.ofSeconds(10))
         .build();

 public AiService(
         @Value("${webox.ai.api-key:}") String apiKey,
         @Value("${webox.ai.base-url:https://api.openai.com/v1}") String baseUrl,
         @Value("${webox.ai.model:gpt-4o-mini}") String model,
         @Value("${webox.ai.timeout-seconds:25}") int timeoutSeconds) {
  this.apiKey = apiKey == null ? "" : apiKey.trim();
  this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.trim().replaceAll("/+$", "");
  this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
  this.timeoutSeconds = timeoutSeconds <= 0 ? 25 : timeoutSeconds;
 }

 public List<Recommendation> recommend(Domain.User user, List<Domain.Dish> candidates, String userMessage) {
  if (candidates.isEmpty()) {
   return List.of();
  }

  // 1. If API key is configured, call external LLM
  if (!apiKey.isBlank()) {
   try {
    List<Recommendation> llmResults = callLlm(user, candidates, userMessage);
    if (!llmResults.isEmpty()) {
     log.info("LLM successfully generated {} recommendations for user {}", llmResults.size(), user.email);
     return llmResults;
    }
   } catch (Exception e) {
    log.warn("Failed to get recommendation from LLM ({}), falling back to heuristic engine", e.getMessage());
   }
  }

  // 2. Fallback to heuristic scoring engine
  return fallbackRecommendations(user, candidates, userMessage);
 }

 private List<Recommendation> callLlm(Domain.User user, List<Domain.Dish> candidates, String userMessage) throws Exception {
  Set<Long> validIds = new HashSet<>();
  List<Map<String, Object>> dishList = new ArrayList<>();
  for (Domain.Dish d : candidates) {
   validIds.add(d.id);
   Map<String, Object> item = new HashMap<>();
   item.put("id", d.id);
   item.put("name", d.name);
   item.put("category", d.category);
   item.put("protein", d.protein);
   item.put("spice", d.spice);
   item.put("price", d.price);
   item.put("description", d.description);
   dishList.add(item);
  }

  String candidateJson = mapper.writeValueAsString(dishList);
  String systemPrompt = "You are the AI meal recommendation assistant for the WeBox corporate dining platform.\n"
          + "Recommend 1 to 3 dishes from the CANDIDATE DISHES list below that best match the employee's request and preferences.\n\n"
          + "CANDIDATE DISHES (strictly select only from this list):\n"
          + candidateJson + "\n\n"
          + "EMPLOYEE PROFILE:\n"
          + "- Preferred Cuisines: " + (user.preferredCategories.isEmpty() ? "Any" : String.join(", ", user.preferredCategories)) + "\n"
          + "- Preferred Spice Level: " + user.spice + "\n"
          + "- Taste Intensity: " + (user.tasteIntensity == null ? "Regular" : user.tasteIntensity) + "\n\n"
          + "STRICT RULES:\n"
          + "1. ONLY recommend dishes that appear in the CANDIDATE DISHES list. Do NOT invent new dishes or dish IDs.\n"
          + "2. Provide a compelling, personalized reason in English for each recommended dish.\n"
          + "3. Format output strictly as a JSON object with this shape: {\"recommendations\": [{\"dishId\": 123, \"reason\": \"...\"}]}.\n"
          + "4. Output ONLY valid JSON, without any markdown formatting, backticks, or extra commentary.";

  Map<String, Object> requestPayload = new HashMap<>();
  requestPayload.put("model", model);
  requestPayload.put("temperature", 0.3);
  requestPayload.put("messages", List.of(
          Map.of("role", "system", "content", systemPrompt),
          Map.of("role", "user", "content", userMessage)
  ));

  String requestBody = mapper.writeValueAsString(requestPayload);
  HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(baseUrl + "/chat/completions"))
          .timeout(Duration.ofSeconds(timeoutSeconds))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + apiKey)
          .POST(HttpRequest.BodyPublishers.ofString(requestBody))
          .build();

  HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  if (response.statusCode() != 200) {
   throw new RuntimeException("LLM API returned status " + response.statusCode() + ": " + response.body());
  }

   String content = mapper.readTree(response.body()).path("choices").get(0).path("message").path("content").asText("").trim();
   if (content.contains("</think>")) {
    content = content.substring(content.indexOf("</think>") + "</think>".length()).trim();
   }
   if (content.startsWith("```")) {
    int firstNl = content.indexOf('\n');
    int lastTriple = content.lastIndexOf("```");
    if (firstNl != -1 && lastTriple > firstNl) {
     content = content.substring(firstNl + 1, lastTriple).trim();
    }
   }
   int start = content.indexOf('{');
   int end = content.lastIndexOf('}');
   if (start != -1 && end != -1 && end > start) {
    content = content.substring(start, end + 1);
   }

  JsonNode parsed = mapper.readTree(content);
  JsonNode recsNode = parsed.path("recommendations");
  List<Recommendation> result = new ArrayList<>();
  if (recsNode.isArray()) {
   for (JsonNode rec : recsNode) {
    long id = rec.path("dishId").asLong(-1);
    String reason = rec.path("reason").asText("");
    if (validIds.contains(id) && !reason.isBlank()) {
     result.add(new Recommendation(id, reason));
    }
   }
  }
  return result;
 }

 private List<Recommendation> fallbackRecommendations(Domain.User user, List<Domain.Dish> candidates, String userMessage) {
  String words = userMessage.toLowerCase(Locale.ROOT);
  return candidates.stream()
          .sorted(Comparator.<Domain.Dish>comparingInt(d -> scoreDish(d, user, words)).reversed())
          .limit(3)
          .map(d -> new Recommendation(d.id, buildReason(d, user, words)))
          .toList();
 }

 private int scoreDish(Domain.Dish d, Domain.User u, String words) {
  int score = u.preferredCategories.contains(d.category) ? 4 : 0;
  score += u.spice.equals(d.spice) ? 2 : 0;
  String text = (d.name + " " + d.description + " " + d.category + " " + d.protein).toLowerCase(Locale.ROOT);
  for (String word : words.split("\\W+")) {
   if (word.length() > 2 && text.contains(word)) score += 3;
  }
  if ((words.contains("light") || words.contains("low-fat")) && "Light Meal".equals(d.category)) score += 6;
  if ((words.contains("protein") || words.contains("high-protein")) && !"None".equals(d.protein)) score += 5;
  return score;
 }

 private String buildReason(Domain.Dish d, Domain.User u, String words) {
  List<String> reasons = new ArrayList<>();
  if (u.preferredCategories.contains(d.category)) reasons.add("matches your " + d.category + " preference");
  if (u.spice.equals(d.spice)) reasons.add("fits your " + d.spice.toLowerCase(Locale.ROOT) + " spice setting");
  if (words.contains("protein") && !"None".equals(d.protein)) reasons.add("offers " + d.protein.toLowerCase(Locale.ROOT) + " protein");
  if ("Light".equals(u.tasteIntensity) || words.contains("light")) reasons.add("suits a lighter meal");
  return "Recommended because it " + (reasons.isEmpty() ? "best matches your request" : String.join(" and ", reasons)) + ".";
 }
}
