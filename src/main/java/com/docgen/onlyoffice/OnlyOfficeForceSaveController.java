package com.docgen.onlyoffice;

import com.docgen.config.OnlyOfficeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Document Server's own save-icon/close behavior turned out unreliable
 * embedded in an iframe (observed opening a blank tab instead of saving).
 * This gives the frontend an explicit, awaitable "save now" — it calls
 * OnlyOffice's CommandService forcesave API, then blocks until
 * {@link OnlyOfficeCallbackController} confirms the resulting callback was
 * received and persisted, so the caller knows the save actually landed
 * before it refreshes the AI-editing preview.
 */
@RestController
@RequestMapping("/api/onlyoffice")
public class OnlyOfficeForceSaveController {

    private static final Logger log = LoggerFactory.getLogger(OnlyOfficeForceSaveController.class);
    private static final long WAIT_SECONDS = 10;

    private final OnlyOfficeProperties properties;
    private final OnlyOfficeJwtService jwtService;
    private final OnlyOfficeSaveCoordinator saveCoordinator;
    private final RestClient restClient = RestClient.create();

    public OnlyOfficeForceSaveController(
            OnlyOfficeProperties properties,
            OnlyOfficeJwtService jwtService,
            OnlyOfficeSaveCoordinator saveCoordinator) {
        this.properties = properties;
        this.jwtService = jwtService;
        this.saveCoordinator = saveCoordinator;
    }

    @PostMapping("/forcesave/{name}")
    public Map<String, Object> forceSave(@PathVariable("name") String name, @RequestBody Map<String, Object> body) {
        Object keyObj = body.get("key");
        String key = keyObj == null ? null : String.valueOf(keyObj);
        if (key == null || key.isBlank()) {
            return Map.of("status", "error", "detail", "Missing document key.");
        }

        CompletableFuture<Void> future = saveCoordinator.awaitNextSave(name);

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("c", "forcesave");
        command.put("key", key);
        String token = jwtService.signConfig(command);
        if (token != null) {
            command.put("token", token);
        }

        Map<?, ?> response;
        try {
            response = restClient.post()
                    .uri(properties.documentServerUrl() + "/coauthoring/CommandService.ashx")
                    .body(command)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            saveCoordinator.completeExceptionally(name, e);
            log.warn("OnlyOffice forcesave command failed for {}: {}", name, e.toString());
            return Map.of("status", "error", "detail", String.valueOf(e.getMessage()));
        }

        Object errorCode = response == null ? null : response.get("error");
        boolean accepted = errorCode instanceof Number n && n.intValue() == 0;
        if (!accepted) {
            saveCoordinator.completeExceptionally(name, new IllegalStateException("CommandService error " + errorCode));
            // error 4: "no changes were applied before the forcesave command" — not a real failure.
            if (errorCode instanceof Number n && n.intValue() == 4) {
                return Map.of("status", "no_changes");
            }
            return Map.of("status", "error", "detail", "Document Server rejected forcesave (error " + errorCode + ").");
        }

        try {
            future.get(WAIT_SECONDS, TimeUnit.SECONDS);
            return Map.of("status", "saved");
        } catch (TimeoutException e) {
            return Map.of("status", "timeout");
        } catch (ExecutionException | InterruptedException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Map.of("status", "error", "detail", String.valueOf(cause.getMessage()));
        }
    }
}
